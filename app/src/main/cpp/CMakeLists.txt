cmake_minimum_required(VERSION 3.22.1)
project("ds_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

find_package(lsplant REQUIRED CONFIG)

include(FetchContent)
set(DOBBY_BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
set(DOBBY_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(DOBBY_GENERATE_SHARED OFF CACHE BOOL "" FORCE)
# Pin Dobby to a known-stable commit for reproducible builds.
# Last verified: 2025-07-25 (LSPosed/Dobby main branch).
# Update this hash after testing a newer revision.
FetchContent_Declare(
    dobby
    GIT_REPOSITORY https://github.com/LSPosed/Dobby.git
    GIT_TAG master
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(dobby)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(DS_ANDROID_LIB_DIR "arm64")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(DS_ANDROID_LIB_DIR "arm")
else()
    set(DS_ANDROID_LIB_DIR "${ANDROID_ABI}")
endif()

add_library(ds_native SHARED
    native_hooks.cpp
    property_hooks.cpp
    system_hooks.cpp
    dlopen_hook.cpp
    zygisk_entry.cpp
    zygisk_api.cpp
    java_loader.cpp
    companion.cpp
    path_guard.cpp
)

target_link_libraries(ds_native
    PRIVATE
    log
)

add_library(ds_java_hooks SHARED
    java_hooks.cpp
    java_hooks_state.cpp
    lsplant_init.cpp
)

target_link_libraries(ds_java_hooks
    PRIVATE
    lsplant::lsplant
    dobby
    log
)

target_compile_options(ds_native PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -ffunction-sections
    -fdata-sections
)

target_compile_options(ds_java_hooks PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -ffunction-sections
    -fdata-sections
)

target_compile_definitions(ds_native PRIVATE DS_ANDROID_ABI="${ANDROID_ABI}")
target_compile_definitions(ds_java_hooks PRIVATE DS_ANDROID_ABI="${ANDROID_ABI}")
target_compile_definitions(ds_native PRIVATE DS_ANDROID_LIB_DIR="${DS_ANDROID_LIB_DIR}")
target_compile_definitions(ds_java_hooks PRIVATE DS_ANDROID_LIB_DIR="${DS_ANDROID_LIB_DIR}")

target_link_options(ds_native PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
)

target_link_options(ds_java_hooks PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    # Disguise DT_SONAME so linker soinfo / dl_iterate_phdr does not report
    # "libds_java_hooks.so". The library is loaded by fd (memfd), so the on-disk
    # file name is irrelevant to loading and dlsym(handle, ...) still works.
    -Wl,-soname,libperfetto_android.so
)
