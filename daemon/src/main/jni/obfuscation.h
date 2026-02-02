/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2022 LSPosed Contributors
 */

#pragma once
#include "slicer/writer.h"
#include "utils/jni_helper.hpp"
#include <fcntl.h>
#include <parallel_hashmap/phmap.h>
#include <string>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

// API 27+ uses android/sharedmem.h, API 26 uses ashmem
#if __ANDROID_API__ >= 27
#include <android/sharedmem.h>
#else
// ashmem compatibility definition
#define ASHMEM_NAME_LEN 256
#define ASHMEM_SET_NAME _IOW(0x77, 1, char[ASHMEM_NAME_LEN])
#define ASHMEM_SET_SIZE _IOW(0x77, 3, size_t)
#endif

#include <dlfcn.h>
#include <pthread.h>
#include <sys/system_properties.h>
#include <stdlib.h>
#include "logging.h"

typedef int (*ASharedMemory_create_func)(const char *name, size_t size);

static inline int get_android_api_level() {
    char prop_value[PROP_VALUE_MAX];
    if (__system_property_get("ro.build.version.sdk", prop_value) > 0) {
        return atoi(prop_value);
    }
    return 0;
}

static inline int compat_ashmem_create(const char *name, size_t size) {
    static ASharedMemory_create_func g_ASharedMemory_create = nullptr;
    static int g_api_level = 0;
    static pthread_once_t once_control = PTHREAD_ONCE_INIT;

    pthread_once(&once_control, []() {
        g_api_level = get_android_api_level();
        LOGD("Detected API Level: %d", g_api_level);

        if (g_api_level >= 27) {
            void *handle = dlopen("libandroid.so", RTLD_NOW);
            if (handle) {
                g_ASharedMemory_create = (ASharedMemory_create_func) dlsym(handle, "ASharedMemory_create");
                LOGI("Loaded ASharedMemory_create from libandroid.so");
            } else {
                LOGW("Failed to load libandroid.so for ASharedMemory_create");
            }
        } else {
            LOGI("API Level %d < 27, skipping ASharedMemory_create loading", g_api_level);
        }
    });

    if (g_ASharedMemory_create) {
        int fd = g_ASharedMemory_create(name, size);
        if (fd >= 0) {
            LOGD("Using ASharedMemory_create for %zu bytes", size);
            return fd;
        }
        LOGW("ASharedMemory_create failed with fd=%d, falling back to ashmem", fd);
    }

    // Fallback to direct ashmem access
    LOGD("Falling back to ashmem ioctl for %zu bytes", size);
    int fd = open("/dev/ashmem", O_RDWR);
    if (fd < 0)
        return -1;

    if (name && name[0]) {
        char buf[ASHMEM_NAME_LEN];
        strncpy(buf, name, ASHMEM_NAME_LEN - 1);
        buf[ASHMEM_NAME_LEN - 1] = '\0';
        ioctl(fd, ASHMEM_SET_NAME, buf);
    }

    if (ioctl(fd, ASHMEM_SET_SIZE, size) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

class WA: public dex::Writer::Allocator {
    // addr: {size, fd}
    phmap::flat_hash_map<void*, std::pair<size_t, int>> allocated_;
public:
    inline void* Allocate(size_t size) override {
        auto fd = compat_ashmem_create("", size);
        if (fd < 0)
          return nullptr;
        auto *mem = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (mem == MAP_FAILED) {
          close(fd);
          return nullptr;
        }
        allocated_[mem] = {size, fd};
        return mem;
    }
    inline void Free(void* ptr) override {
        auto alloc_data = allocated_.at(ptr);
        munmap(ptr, alloc_data.first);
        close(alloc_data.second);
        allocated_.erase(ptr);
    }
    inline int GetFd(void* ptr) {
        auto alloc_data = allocated_.find(ptr);
        if (alloc_data == allocated_.end()) return -1;
        return (*alloc_data).second.second;
    }
};
