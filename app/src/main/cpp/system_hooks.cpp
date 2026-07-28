#include "ds_state.h"

#include <dlfcn.h>
#include <cstring>
#include <ifaddrs.h>
#include <linux/if_packet.h>
#include <net/if.h>
#include <netinet/in.h>
#include <string>
#include <sys/socket.h>
#include <sys/utsname.h>
#include <unistd.h>

namespace ds {

namespace {

int (*orig_uname)(struct utsname*) = nullptr;
int (*orig_gethostname)(char*, size_t) = nullptr;
int (*orig_getifaddrs)(struct ifaddrs**) = nullptr;

std::string LookupOr(const char* key, const char* fallback) {
    std::string out;
    if (LookupProperty(key, out) && !out.empty()) return out;
    return fallback ? fallback : "";
}

int my_uname(struct utsname* u) {
    int rc = orig_uname ? orig_uname(u) : -1;
    if (u != nullptr) {
        std::string release = LookupOr("kernel.osrelease", "5.10.157-android13");
        std::string version = LookupOr("kernel.version",
                                       "#1 SMP PREEMPT Tue Dec  3 21:01:46 UTC 2024");
        std::string nodename = LookupOr("kernel.hostname", "localhost");

        // sysname/machine left as Linux/aarch64.
        snprintf(u->release, sizeof(u->release), "%s", release.c_str());
        snprintf(u->version, sizeof(u->version), "%s", version.c_str());
        snprintf(u->nodename, sizeof(u->nodename), "%s", nodename.c_str());
    }
    return rc;
}

int my_gethostname(char* name, size_t len) {
    if (name == nullptr || len == 0) {
        return orig_gethostname ? orig_gethostname(name, len) : -1;
    }
    std::string h = LookupOr("kernel.hostname", "localhost");
    size_t n = h.size();
    if (n + 1 > len) n = len - 1;
    memcpy(name, h.data(), n);
    name[n] = '\0';
    return 0;
}

bool ParseMac(const std::string& s, uint8_t out[6]) {
    if (s.size() < 17) return false;
    auto hex = [](char c, int& v) {
        if (c >= '0' && c <= '9') { v = c - '0'; return true; }
        if (c >= 'a' && c <= 'f') { v = c - 'a' + 10; return true; }
        if (c >= 'A' && c <= 'F') { v = c - 'A' + 10; return true; }
        return false;
    };
    for (int i = 0; i < 6; i++) {
        int hi = 0, lo = 0;
        if (!hex(s[i*3 + 0], hi)) return false;
        if (!hex(s[i*3 + 1], lo)) return false;
        out[i] = (uint8_t)((hi << 4) | lo);
    }
    return true;
}

int my_getifaddrs(struct ifaddrs** ifap) {
    int rc = orig_getifaddrs ? orig_getifaddrs(ifap) : -1;
    if (rc != 0 || ifap == nullptr || *ifap == nullptr) return rc;

    std::string wifiMac = LookupOr("wifi.mac", "");
    std::string btMac   = LookupOr("bluetooth.mac", "");
    uint8_t wifiBytes[6] = {0}, btBytes[6] = {0};
    bool haveWifi = !wifiMac.empty() && ParseMac(wifiMac, wifiBytes);
    bool haveBt   = !btMac.empty()   && ParseMac(btMac,   btBytes);

    for (struct ifaddrs* it = *ifap; it != nullptr; it = it->ifa_next) {
        if (it->ifa_name == nullptr || it->ifa_addr == nullptr) continue;
        if (it->ifa_addr->sa_family != AF_PACKET) continue;
        auto* sll = reinterpret_cast<struct sockaddr_ll*>(it->ifa_addr);
        if (sll->sll_halen != 6) continue;

        const char* n = it->ifa_name;
        if (haveWifi && (strncmp(n, "wlan", 4) == 0)) {
            memcpy(sll->sll_addr, wifiBytes, 6);
        } else if (haveBt && (strncmp(n, "bt", 2) == 0 || strncmp(n, "bnep", 4) == 0)) {
            memcpy(sll->sll_addr, btBytes, 6);
        } else if (strncmp(n, "lo", 2) == 0) {
            // loopback
        } else {
            memset(sll->sll_addr, 0, 6);
        }
    }
    return rc;
}

}  // namespace

void InstallSystemHooks(dev_t dev, ino_t inode) {
    bool has_uname = dlsym(RTLD_DEFAULT, "uname") != nullptr;
    bool has_gh = dlsym(RTLD_DEFAULT, "gethostname") != nullptr;
    bool has_gi = dlsym(RTLD_DEFAULT, "getifaddrs") != nullptr;
    bool ok_uname = has_uname && ZygiskPltHookRegister(dev, inode, "uname",
            reinterpret_cast<void*>(&my_uname),
            reinterpret_cast<void**>(&orig_uname));
    bool ok_gh = has_gh && ZygiskPltHookRegister(dev, inode, "gethostname",
            reinterpret_cast<void*>(&my_gethostname),
            reinterpret_cast<void**>(&orig_gethostname));
    bool ok_gi = has_gi && ZygiskPltHookRegister(dev, inode, "getifaddrs",
            reinterpret_cast<void*>(&my_getifaddrs),
            reinterpret_cast<void**>(&orig_getifaddrs));
    DS_LOGW("audit:system hooks: uname=%d/%d gethostname=%d/%d getifaddrs=%d/%d",
            ok_uname, has_uname, ok_gh, has_gh, ok_gi, has_gi);
}

}  // namespace ds
