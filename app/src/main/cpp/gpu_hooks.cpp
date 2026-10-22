#include "ds_state.h"

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <dlfcn.h>
#include <cstring>
#include <mutex>
#include <string>

namespace ds {

namespace {

const GLubyte* (*orig_glGetString)(GLenum) = nullptr;
const GLubyte* (*orig_glGetStringi)(GLenum, GLuint) = nullptr;
const char* (*orig_eglQueryString)(EGLDisplay, EGLint) = nullptr;
__eglMustCastToProperFunctionPointerType (*orig_eglGetProcAddress)(const char*) =
        nullptr;
void* (*orig_dlsym)(void*, const char*) = nullptr;
void* (*g_dlsym_fallback)(void*, const char*) = nullptr;

std::mutex g_gpu_mutex;
std::string g_gl_vendor;
std::string g_gl_renderer;
std::string g_gl_version;
std::string g_egl_vendor;
std::string g_egl_version;

void LoadGpuStrings() {
    std::lock_guard<std::mutex> lk(g_gpu_mutex);
    LookupProperty("gpu.vendor", g_gl_vendor);
    LookupProperty("gpu.renderer", g_gl_renderer);
    LookupProperty("gpu.gl_version", g_gl_version);
    LookupProperty("gpu.egl_vendor", g_egl_vendor);
    LookupProperty("gpu.egl_version", g_egl_version);
}

const GLubyte* GlString(const std::string& spoofed, const GLubyte* real) {
    if (spoofed.empty()) return real;
    return reinterpret_cast<const GLubyte*>(spoofed.c_str());
}

const GLubyte* my_glGetString(GLenum name) {
    const GLubyte* real = orig_glGetString ? orig_glGetString(name) : nullptr;
    std::lock_guard<std::mutex> lk(g_gpu_mutex);
    switch (name) {
        case GL_VENDOR:
            return GlString(g_gl_vendor, real);
        case GL_RENDERER:
            return GlString(g_gl_renderer, real);
        case GL_VERSION:
            return GlString(g_gl_version, real);
        default:
            return real;
    }
}

const GLubyte* my_glGetStringi(GLenum name, GLuint index) {
    return orig_glGetStringi ? orig_glGetStringi(name, index) : nullptr;
}

const char* my_eglQueryString(EGLDisplay display, EGLint name) {
    const char* real = orig_eglQueryString
            ? orig_eglQueryString(display, name) : nullptr;
    std::lock_guard<std::mutex> lk(g_gpu_mutex);
    switch (name) {
        case EGL_VENDOR:
            if (!g_egl_vendor.empty()) return g_egl_vendor.c_str();
            break;
        case EGL_VERSION:
            if (!g_egl_version.empty()) return g_egl_version.c_str();
            break;
        default:
            break;
    }
    return real;
}

__eglMustCastToProperFunctionPointerType my_eglGetProcAddress(
        const char* procName) {
    if (procName != nullptr) {
        if (strcmp(procName, "glGetString") == 0) {
            return reinterpret_cast<__eglMustCastToProperFunctionPointerType>(
                    &my_glGetString);
        }
        if (strcmp(procName, "glGetStringi") == 0) {
            return reinterpret_cast<__eglMustCastToProperFunctionPointerType>(
                    &my_glGetStringi);
        }
        if (strcmp(procName, "eglQueryString") == 0) {
            return reinterpret_cast<__eglMustCastToProperFunctionPointerType>(
                    &my_eglQueryString);
        }
    }
    return orig_eglGetProcAddress
            ? orig_eglGetProcAddress(procName) : nullptr;
}

void LoadDlsymFallback() {
    if (g_dlsym_fallback != nullptr) return;
    void* dl = dlopen("libdl.so", RTLD_NOW);
    if (dl == nullptr) return;
    g_dlsym_fallback = reinterpret_cast<void* (*)(void*, const char*)>(
            dlsym(dl, "dlsym"));
    dlclose(dl);
}

void* my_dlsym(void* handle, const char* symbol) {
    if (symbol != nullptr) {
        if (strcmp(symbol, "glGetString") == 0) {
            return reinterpret_cast<void*>(&my_glGetString);
        }
        if (strcmp(symbol, "glGetStringi") == 0) {
            return reinterpret_cast<void*>(&my_glGetStringi);
        }
        if (strcmp(symbol, "eglQueryString") == 0) {
            return reinterpret_cast<void*>(&my_eglQueryString);
        }
        if (strcmp(symbol, "eglGetProcAddress") == 0) {
            return reinterpret_cast<void*>(&my_eglGetProcAddress);
        }
    }
    if (orig_dlsym != nullptr) return orig_dlsym(handle, symbol);
    return g_dlsym_fallback ? g_dlsym_fallback(handle, symbol) : nullptr;
}

}  // namespace

void InstallGpuHooks() {
    LoadGpuStrings();
    LoadDlsymFallback();
    RegisterNativeHookGroup("glGetString",
                            reinterpret_cast<void*>(&my_glGetString),
                            reinterpret_cast<void**>(&orig_glGetString),
                            "/libGLESv2.so");
    RegisterNativeHookGroup("glGetStringi",
                            reinterpret_cast<void*>(&my_glGetStringi),
                            reinterpret_cast<void**>(&orig_glGetStringi),
                            "/libGLESv2.so");
    RegisterNativeHookGroup("eglQueryString",
                            reinterpret_cast<void*>(&my_eglQueryString),
                            reinterpret_cast<void**>(&orig_eglQueryString),
                            "/libEGL.so");
    RegisterNativeHookGroup("eglGetProcAddress",
                            reinterpret_cast<void*>(&my_eglGetProcAddress),
                            reinterpret_cast<void**>(&orig_eglGetProcAddress),
                            "/libEGL.so");
    RegisterNativeHookGroup("dlsym", reinterpret_cast<void*>(&my_dlsym),
                            reinterpret_cast<void**>(&orig_dlsym), "/libdl.so");
    DS_LOGW("audit:gpu hooks registered provider=/libGLESv2.so,/libEGL.so,/libdl.so");
}

}  // namespace ds
