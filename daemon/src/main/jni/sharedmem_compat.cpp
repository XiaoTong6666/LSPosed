/*
 * SharedMemoryCompat Native Implementation
 *
 * API 26 Compatibility Layer: Implementing Shared Memory Functionality Using ashmem
 */

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#if __has_include(<linux/ashmem.h>)
#include <linux/ashmem.h>
#else

#define ASHMEM_NAME_LEN 256
#define ASHMEM_SET_NAME _IOW(0x77, 1, char[ASHMEM_NAME_LEN])
#define ASHMEM_SET_SIZE _IOW(0x77, 3, size_t)
#define ASHMEM_SET_PROT_MASK _IOW(0x77, 5, unsigned long)
#endif

#define LOG_TAG "SharedMemoryCompat"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const char* ASHMEM_DEVICE = "/dev/ashmem";

extern "C" {

/**
 * Create ashmem shared memory
 * @return fd on success, negative errno on failure
 */
JNIEXPORT jint JNICALL Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeCreate(JNIEnv* env, jclass clazz, jstring name, jint size) {
    int fd = open(ASHMEM_DEVICE, O_RDWR);
    if (fd < 0) {
        LOGE("Failed to open ashmem device: %s", strerror(errno));
        return -errno;
    }

    // Set name
    if (name != nullptr) {
        const char* nameStr = env->GetStringUTFChars(name, nullptr);
        if (nameStr != nullptr) {
            char buf[ASHMEM_NAME_LEN];
            strncpy(buf, nameStr, ASHMEM_NAME_LEN - 1);
            buf[ASHMEM_NAME_LEN - 1] = '\0';

            if (ioctl(fd, ASHMEM_SET_NAME, buf) < 0) {
                LOGE("Failed to set ashmem name: %s", strerror(errno));
            }
            env->ReleaseStringUTFChars(name, nameStr);
        }
    }

    // Set size
    if (ioctl(fd, ASHMEM_SET_SIZE, (size_t)size) < 0) {
        LOGE("Failed to set ashmem size: %s", strerror(errno));
        int err = errno;
        close(fd);
        return -err;
    }

    LOGD("Created ashmem fd=%d, size=%d", fd, size);
    return fd;
}

// Map ashmem to memory
JNIEXPORT jobject JNICALL Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeMap(JNIEnv* env, jclass clazz,jint fd, jint size, jboolean readOnly) {
    int prot = readOnly ? PROT_READ : (PROT_READ | PROT_WRITE);
    void* addr = mmap(nullptr, size, prot, MAP_SHARED, fd, 0);


    if (addr == MAP_FAILED) {
        LOGE("mmap failed: %s", strerror(errno));
        return nullptr;
    }

    LOGD("Mapped ashmem fd=%d at %p, size=%d, readOnly=%d", fd, addr, size, readOnly);
    return env->NewDirectByteBuffer(addr, size);
}

// Cancel mapping
JNIEXPORT void JNICALL Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeUnmap(JNIEnv* env, jclass clazz, jobject buffer, jint size) {
    if (buffer == nullptr)
        return;

    void* addr = env->GetDirectBufferAddress(buffer);

    if (addr != nullptr && size > 0) {
        munmap(addr, size);
        LOGD("Unmapped buffer at %p, size=%d", addr, size);
    }
}

// Close fd
JNIEXPORT void JNICALL Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeClose(JNIEnv* env, jclass clazz, jint fd) {
    if (fd >= 0) {
        close(fd);
        LOGD("Closed fd=%d", fd);
    }
}

// Set protection mode
JNIEXPORT jint JNICALL Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeSetProt(JNIEnv* env, jclass clazz, jint fd, jint prot) {
    if (ioctl(fd, ASHMEM_SET_PROT_MASK, (unsigned long)prot) < 0) {
        LOGE("Failed to set ashmem prot: %s", strerror(errno));
        return -errno;
    }
    return 0;
}

}  // extern "C"
