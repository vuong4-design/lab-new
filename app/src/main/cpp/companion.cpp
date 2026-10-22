#include "zygisk.hpp"
#include "companion_protocol.h"
#include "companion_io.h"
#include "ds_state.h"

#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

// The companion runs in a root daemon process. It is the only side that can
// traverse the module app's sandbox, so it reads the spoof profile and scope
// list there and streams them back over the per-request socket.
//
// REGISTER_ZYGISK_COMPANION may invoke the handler concurrently on multiple
// threads, so all shared state is mutex-guarded.

namespace {

std::mutex g_cache_mutex;

struct FileCache {
    time_t mtime = 0;
    off_t size = -1;
    std::unordered_map<std::string, std::string> data;
    bool loaded = false;
};

FileCache g_profile_cache;
FileCache g_scope_cache;

std::mutex g_uptime_mutex;
long long g_uptime_target_seconds = 0;
long long g_uptime_offset_seconds = 0;

bool Truthy(const std::string& value) {
    return value == "1" || value == "true" || value == "TRUE"
            || value == "yes" || value == "YES"
            || value == "on" || value == "ON";
}

long long ParseLong(const std::string& value, long long fallback) {
    if (value.empty()) return fallback;
    char* end = nullptr;
    long long parsed = strtoll(value.c_str(), &end, 10);
    if (end == value.c_str() || *end != '\0') return fallback;
    return parsed;
}

uint64_t RandomU64() {
    uint64_t value = 0;
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        ssize_t n = read(fd, &value, sizeof(value));
        close(fd);
        if (n == static_cast<ssize_t>(sizeof(value))) return value;
    }
    auto now = std::chrono::steady_clock::now().time_since_epoch();
    value = static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(now).count());
    value ^= static_cast<uint64_t>(getpid()) << 32;
    value ^= static_cast<uint64_t>(time(nullptr));
    return value;
}

long long ReadRealUptimeSeconds() {
    int fd = open("/proc/uptime", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    char buf[128] = {};
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;
    char* end = nullptr;
    double uptime = strtod(buf, &end);
    if (end == buf || uptime < 0.0) return 0;
    return static_cast<long long>(uptime);
}

bool UptimeEnabled(const std::unordered_map<std::string, std::string>& profile) {
    auto it = profile.find("hooks.uptime_spoof");
    if (it == profile.end() || it->second.empty()) return true;
    return Truthy(it->second);
}

long long PickUptimeTargetSeconds(
        const std::unordered_map<std::string, std::string>& profile,
        long long& offsetOut) {
    long long minDays = ParseLong(
            profile.count("hooks.uptime_target_min_days")
                    ? profile.at("hooks.uptime_target_min_days") : std::string(),
            1);
    long long maxDays = ParseLong(
            profile.count("hooks.uptime_target_max_days")
                    ? profile.at("hooks.uptime_target_max_days") : std::string(),
            7);
    if (minDays < 1) minDays = 1;
    if (minDays > 30) minDays = 30;
    if (maxDays < 1) maxDays = 1;
    if (maxDays > 30) maxDays = 30;
    if (maxDays < minDays) maxDays = minDays;

    std::lock_guard<std::mutex> lock(g_uptime_mutex);
    if (g_uptime_target_seconds <= 0) {
        const long long minSeconds = minDays * 86400LL;
        const long long maxSeconds = maxDays * 86400LL;
        const uint64_t span = static_cast<uint64_t>(
                maxSeconds - minSeconds + 1);
        g_uptime_target_seconds = minSeconds
                + static_cast<long long>(RandomU64() % span);
        g_uptime_offset_seconds =
                g_uptime_target_seconds - ReadRealUptimeSeconds();
        DS_LOGW("audit:companion uptime target selected seconds=%lld days=%.2f offset=%lld",
                g_uptime_target_seconds,
                static_cast<double>(g_uptime_target_seconds) / 86400.0,
                g_uptime_offset_seconds);
    }
    offsetOut = g_uptime_offset_seconds;
    return g_uptime_target_seconds;
}

void InjectUptimeProfile(std::unordered_map<std::string, std::string>& profile) {
    if (!UptimeEnabled(profile)) return;
    long long offset = 0;
    long long target = PickUptimeTargetSeconds(profile, offset);
    long long realUptime = target - offset;
    profile["uptime.target_seconds"] = std::to_string(target);
    profile["uptime.offset_seconds"] = std::to_string(offset);
    DS_LOGW("audit:companion uptime profile target=%lld real=%lld offset=%lld",
            target, realUptime, offset);
}

// Returns true if the file at path is unchanged versus the cache stamp.
bool CacheFresh(const FileCache& cache, const struct stat& st) {
    return cache.loaded &&
           cache.mtime == st.st_mtime &&
           cache.size == st.st_size;
}

// Parse key=value lines ("#" comments, optional surrounding quotes on value).
// Mirrors ConfigManager.readConfigFile so both sides agree on the format.
void ParseKeyValueFile(int fd,
                       std::unordered_map<std::string, std::string>& out) {
    std::string content;
    char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        content.append(buf, (size_t)n);
    }

    size_t pos = 0;
    while (pos < content.size()) {
        size_t eol = content.find('\n', pos);
        if (eol == std::string::npos) eol = content.size();
        std::string line = content.substr(pos, eol - pos);
        pos = eol + 1;

        // trim leading/trailing whitespace
        size_t a = line.find_first_not_of(" \t\r");
        if (a == std::string::npos) continue;
        size_t b = line.find_last_not_of(" \t\r");
        line = line.substr(a, b - a + 1);

        if (line.empty() || line[0] == '#') continue;

        size_t eq = line.find('=');
        if (eq == std::string::npos || eq == 0) continue;
        std::string key = line.substr(0, eq);
        std::string value = line.substr(eq + 1);

        // trim key/value
        size_t kb = key.find_last_not_of(" \t");
        if (kb != std::string::npos) key = key.substr(0, kb + 1);
        size_t va = value.find_first_not_of(" \t");
        value = (va == std::string::npos) ? "" : value.substr(va);

        if (value.size() >= 2 && value.front() == '"' && value.back() == '"') {
            value = value.substr(1, value.size() - 2);
        }
        out.emplace(std::move(key), std::move(value));
    }
}

// Parse one-package-per-line scope list ("#" comments). Stored as keys with
// empty values so it reuses the same wire framing as the profile.
void ParseScopeFile(int fd,
                    std::unordered_map<std::string, std::string>& out) {
    std::string content;
    char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        content.append(buf, (size_t)n);
    }

    size_t pos = 0;
    while (pos < content.size()) {
        size_t eol = content.find('\n', pos);
        if (eol == std::string::npos) eol = content.size();
        std::string line = content.substr(pos, eol - pos);
        pos = eol + 1;

        size_t a = line.find_first_not_of(" \t\r");
        if (a == std::string::npos) continue;
        size_t b = line.find_last_not_of(" \t\r");
        line = line.substr(a, b - a + 1);

        if (line.empty() || line[0] == '#') continue;
        out.emplace(std::move(line), std::string());
    }
}

// Load a file into its cache if the on-disk mtime/size changed. Returns a copy
// of the parsed map under lock.
std::unordered_map<std::string, std::string> LoadCached(
        const char* label, const char* path, FileCache& cache,
        void (*parser)(int, std::unordered_map<std::string, std::string>&)) {
    std::lock_guard<std::mutex> lk(g_cache_mutex);

    struct stat st{};
    if (stat(path, &st) != 0) {
        DS_LOGW("audit:companion %s stat failed path=%s errno=%d %s cached=%d cachedSize=%zu",
                label, path, errno, strerror(errno), cache.loaded,
                (size_t)cache.data.size());
        // File missing: serve last known good cache if any, else empty.
        return cache.loaded ? cache.data
                            : std::unordered_map<std::string, std::string>{};
    }

    if (CacheFresh(cache, st)) {
        DS_LOGW("audit:companion %s cache hit path=%s fileSize=%ld parsed=%zu",
                label, path, (long)st.st_size, (size_t)cache.data.size());
        return cache.data;
    }

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        DS_LOGW("audit:companion %s open failed path=%s errno=%d %s cached=%d cachedSize=%zu",
                label, path, errno, strerror(errno), cache.loaded,
                (size_t)cache.data.size());
        return cache.loaded ? cache.data
                            : std::unordered_map<std::string, std::string>{};
    }

    std::unordered_map<std::string, std::string> parsed;
    parser(fd, parsed);
    close(fd);

    cache.data = std::move(parsed);
    cache.mtime = st.st_mtime;
    cache.size = st.st_size;
    cache.loaded = true;
    DS_LOGW("audit:companion %s loaded path=%s fileSize=%ld parsed=%zu",
            label, path, (long)st.st_size, (size_t)cache.data.size());
    return cache.data;
}

bool SendPairs(int client,
               const std::unordered_map<std::string, std::string>& pairs) {
    if (!ds::WriteInt(client, (int32_t)pairs.size())) return false;
    for (const auto& kv : pairs) {
        if (!ds::WriteString(client, kv.first)) return false;
        if (!ds::WriteString(client, kv.second)) return false;
    }
    return true;
}

void CompanionHandler(int client) {
    int32_t request = 0;
    while (ds::ReadInt(client, request)) {
        switch (request) {
            case ds::REQ_PROFILE: {
                auto profile = LoadCached("profile", ds::kProfilePath,
                                          g_profile_cache,
                                          &ParseKeyValueFile);
                InjectUptimeProfile(profile);
                DS_LOGW("audit:companion reply profile count=%zu",
                        (size_t)profile.size());
                if (!SendPairs(client, profile)) return;
                break;
            }
            case ds::REQ_SCOPE: {
                auto scope = LoadCached("scope", ds::kScopePath,
                                        g_scope_cache,
                                        &ParseScopeFile);
                DS_LOGW("audit:companion reply scope count=%zu",
                        (size_t)scope.size());
                if (!SendPairs(client, scope)) return;
                break;
            }
            default:
                // Unknown request: reply with zero pairs so the client unblocks.
                DS_LOGW("audit:companion unknown request=%d", request);
                if (!ds::WriteInt(client, 0)) return;
                break;
        }
    }
}

}  // namespace

REGISTER_ZYGISK_COMPANION(CompanionHandler)
