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
 * Copyright (C) 2021 - 2022 LSPosed Contributors
 */

//
// Created by loves on 2/7/2021.
//

#include "service.h"
#include "config_bridge.h"
#include "context.h"
#include "elf_util.h"
#include "loader.h"
#include "native_util.h"
#include "symbol_cache.h"
#include "utils/jni_helper.hpp"
#include <atomic>
#include <dobby.h>
#if __has_include(<linux/ashmem.h>)
#include <linux/ashmem.h>
#endif
#include <pthread.h>

#ifndef ASHMEM_NAME_LEN
#define ASHMEM_NAME_LEN 256
#endif

#ifndef ASHMEM_SET_NAME
#define ASHMEM_SET_NAME _IOW(0x77, 1, char[ASHMEM_NAME_LEN])
#endif

#ifndef ASHMEM_SET_SIZE
#define ASHMEM_SET_SIZE _IOW(0x77, 3, size_t)
#endif

#ifndef ASHMEM_SET_PROT_MASK
#define ASHMEM_SET_PROT_MASK _IOW(0x77, 5, unsigned long)
#endif
#include <sys/mman.h>
#include <sys/ioctl.h>
#include <thread>

// API 27+ SharedMemory JNI API
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/stat.h>

// API 27+ SharedMemory function pointers
typedef int (*ASharedMemory_create_func)(const char *name, size_t size);
typedef int (*ASharedMemory_dupFromJava_func)(JNIEnv *env, jobject sharedMemory);
typedef int (*ASharedMemory_setProt_func)(int fd, int prot);

static ASharedMemory_create_func g_ASharedMemory_create = nullptr;
static ASharedMemory_dupFromJava_func g_ASharedMemory_dupFromJava = nullptr;
static ASharedMemory_setProt_func g_ASharedMemory_setProt = nullptr;

static void LoadSharedMemorySymbols() {
    static pthread_once_t once_control = PTHREAD_ONCE_INIT;
    pthread_once(&once_control, []() {
        void *handle = dlopen("libandroid.so", RTLD_NOW);
        if (handle) {
            g_ASharedMemory_create = (ASharedMemory_create_func) dlsym(handle, "ASharedMemory_create");
            g_ASharedMemory_dupFromJava = (ASharedMemory_dupFromJava_func) dlsym(handle, "ASharedMemory_dupFromJava");
            g_ASharedMemory_setProt = (ASharedMemory_setProt_func) dlsym(handle, "ASharedMemory_setProt");
        }
    });
}


using namespace lsplant;

namespace lspd {
    std::unique_ptr<Service> Service::instance_ = std::make_unique<Service>();

    uint8_t* access_matrix = nullptr;

    class IPCThreadState {
        static IPCThreadState* (*selfOrNullFn)();
        static uid_t (*getCallingUidFn)(IPCThreadState*);

    public:

        uid_t getCallingUid() {
            if (getCallingUidFn != nullptr) [[likely]] {
                return getCallingUidFn(this);
            }
            return 0;
        }

        static IPCThreadState* selfOrNull() {
            if (selfOrNullFn != nullptr) [[likely]] {
                return selfOrNullFn();
            }
            return nullptr;
        }

        static void Init(const SandHook::ElfImg *binder) {
            if (binder == nullptr) {
                LOGE("libbinder not found");
                return;
            }
            selfOrNullFn = reinterpret_cast<decltype(selfOrNullFn)>(
                    binder->getSymbAddress("_ZN7android14IPCThreadState10selfOrNullEv"));
            getCallingUidFn = reinterpret_cast<decltype(getCallingUidFn)>(
                    binder->getSymbAddress("_ZNK7android14IPCThreadState13getCallingUidEv"));
            LOGD("libbinder selfOrNull {} getCallingUid {}", (void*) selfOrNullFn, (void*) getCallingUidFn);
        }
    };

    IPCThreadState* (*IPCThreadState::selfOrNullFn)() = nullptr;
    uid_t (*IPCThreadState::getCallingUidFn)(IPCThreadState*) = nullptr;

    jboolean
    Service::exec_transact_replace(jboolean *res, JNIEnv *env, [[maybe_unused]] jobject obj,
                                   va_list args) {
        va_list copy;
        va_copy(copy, args);
        auto code = va_arg(copy, jint);
        auto data_obj = va_arg(copy, jlong);
        auto reply_obj = va_arg(copy, jlong);
        auto flags = va_arg(copy, jint);
        va_end(copy);

        if (code == BRIDGE_TRANSACTION_CODE) [[unlikely]] {
            *res = JNI_CallStaticBooleanMethod(env, instance()->bridge_service_class_,
                                               instance()->exec_transact_replace_methodID_,
                                               obj, code, data_obj, reply_obj, flags);
            return true;
        }
        return false;
    }

    jboolean
    Service::call_boolean_method_va_replace(JNIEnv *env, jobject obj, jmethodID methodId,
                                            va_list args) {
        bool need_skip = false;
        if (auto self = IPCThreadState::selfOrNull(); self != nullptr) {
            auto uid = self->getCallingUid();
            auto appId = uid % 100000;
            if (appId >= 10000 && appId <= 19999 && access_matrix != nullptr) {
                need_skip = (access_matrix[(appId - 10000) >> 3] & (1 << ((appId - 10000) & 7))) == 0;
            } else {
                // isolated
                need_skip = appId >= 90000 && appId <= 99999;
            }
        }
        if (!need_skip && methodId == instance()->exec_transact_backup_methodID_) [[unlikely]] {
            jboolean res = false;
            if (exec_transact_replace(&res, env, obj, args)) [[unlikely]] return res;
            // else fallback to backup
        }
        return instance()->call_boolean_method_va_backup_(env, obj, methodId, args);
    }

    // API 27+: Using SharedMemory Java objects via Dynamic Loading or Reflection Fallback
    static void BridgeService_initializeAccessMatrix_SM(JNIEnv *env, jclass, jobject shared_memory) {
        if (access_matrix != nullptr) return;
        
        int fd = -1;
        LoadSharedMemorySymbols();

        // Try ASharedMemory_dupFromJava first (API 27+)
        if (g_ASharedMemory_dupFromJava) {
            fd = g_ASharedMemory_dupFromJava(env, shared_memory);
            if (fd >= 0) LOGD("Using ASharedMemory_dupFromJava, fd=%d", fd);
        }

        // Fallback to Reflection if dynamic loading failed or returned invalid fd
        if (fd < 0) {
            LOGD("Falling back to Reflection for SharedMemory FD");
            jclass sm_class = env->GetObjectClass(shared_memory);
            jmethodID get_fd_method = env->GetMethodID(sm_class, "getFileDescriptor", "()Ljava/io/FileDescriptor;");
            if (!get_fd_method) {
                 LOGE("SharedMemory.getFileDescriptor not found");
                 return;
            }
            jobject fd_obj = env->CallObjectMethod(shared_memory, get_fd_method);
            if (env->ExceptionCheck()) {
                 env->ExceptionClear();
                 LOGE("exception calling getFileDescriptor");
                 return;
            }
            if (fd_obj) {
                jclass fd_class = env->GetObjectClass(fd_obj);
                jfieldID fd_field = env->GetFieldID(fd_class, "descriptor", "I");
                if (fd_field) {
                    fd = env->GetIntField(fd_obj, fd_field);
                }
            }
        }

        if (fd < 0) {
            LOGE("Failed to get fd from SharedMemory");
            return;
        }

        auto addr = mmap(nullptr, 1250, PROT_READ, MAP_SHARED, fd, 0);
        if (addr == MAP_FAILED) {
            PLOGE("map access matrix");
        } else {
            LOGD("access matrix from SharedMemory at {}", addr);
            access_matrix = reinterpret_cast<uint8_t*>(addr);
        }

        if (g_ASharedMemory_dupFromJava && fd >= 0) {
             // Check if we actually used dupFromJava path. The logic above sets fd.
             // If g_ASharedMemory_dupFromJava is not null, we tried it.
             // If it succeeded, fd >= 0.
             close(fd);
        }
    }

    // API 26: Using FileDescriptor 
    static void BridgeService_initializeAccessMatrix_FD(JNIEnv *env, jclass, jobject file_descriptor) {
        if (access_matrix != nullptr) return;

        jclass fd_class = env->GetObjectClass(file_descriptor);
        jfieldID fd_field = env->GetFieldID(fd_class, "descriptor", "I");
        int fd = env->GetIntField(file_descriptor, fd_field);
        
        if (ioctl(fd, ASHMEM_SET_PROT_MASK, PROT_READ) < 0) {
            PLOGE("set protection access matrix");
        }
        auto addr = mmap(nullptr, 1250, PROT_READ, MAP_SHARED, fd, 0);
        if (addr == MAP_FAILED) {
            PLOGE("map access matrix");
        } else {
            LOGD("access matrix from FileDescriptor at {}", addr);
            access_matrix = reinterpret_cast<uint8_t*>(addr);
        }
    }

    // SharedMemoryCompat native methods for API 26 runtime support
    // These methods are always compiled but only called on API 26 devices at runtime

    extern "C" JNIEXPORT jobject JNICALL Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeMap(
        JNIEnv* env, jclass, jint fd, jint size, jboolean readOnly) {
        int prot = readOnly ? PROT_READ : (PROT_READ | PROT_WRITE);
        void* addr = mmap(nullptr, size, prot, MAP_SHARED, fd, 0);
        if (addr == MAP_FAILED) {
            PLOGE("SharedMemoryCompat nativeMap failed");
            return nullptr;
        }
        LOGD("SharedMemoryCompat: mapped fd={} at {}, size={}", fd, addr, size);
        return env->NewDirectByteBuffer(addr, size);
    }

    extern "C" JNIEXPORT void JNICALL Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeUnmap(
        JNIEnv* env, jclass, jobject buffer, jint size) {
        if (buffer == nullptr)
            return;
        void* addr = env->GetDirectBufferAddress(buffer);
        if (addr != nullptr && size > 0) {
            munmap(addr, size);
            LOGD("SharedMemoryCompat: unmapped buffer at {}, size={}", addr, size);
        }
    }

    extern "C" JNIEXPORT void JNICALL Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeClose(JNIEnv*,
                                                                                              jclass,
                                                                                              jint fd) {
        if (fd >= 0) {
            close(fd);
            LOGD("SharedMemoryCompat: closed fd={}", fd);
        }
    }

    extern "C" JNIEXPORT jint JNICALL Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeCreate(
        JNIEnv* env, jclass, jstring name, jint size) {
        LoadSharedMemorySymbols();
        
        // Try ASharedMemory_create first
        if (g_ASharedMemory_create) {
             const char* nameStr = name ? env->GetStringUTFChars(name, nullptr) : nullptr;
             int fd = g_ASharedMemory_create(nameStr, size);
             if (nameStr) env->ReleaseStringUTFChars(name, nameStr);
             if (fd >= 0) {
                 LOGD("Using ASharedMemory_create, fd=%d", fd);
                 return fd;
             }
             // If failed, fallthrough to ashmem? Usually ASharedMemory_create fail means real fail.
             // But we can fallback if symbol is missing.
        }

        // Fallback to ashmem
        LOGD("Falling back to ashmem for creation");
        int fd = open("/dev/ashmem", O_RDWR);
        if (fd < 0) {
            PLOGE("Failed to open ashmem device");
            return -errno;
        }

        if (name != nullptr) {
            const char* nameStr = env->GetStringUTFChars(name, nullptr);
            if (nameStr != nullptr) {
                char buf[ASHMEM_NAME_LEN];
                strncpy(buf, nameStr, ASHMEM_NAME_LEN - 1);
                buf[ASHMEM_NAME_LEN - 1] = '\0';
                if (ioctl(fd, ASHMEM_SET_NAME, buf) < 0) {
                    PLOGE("Failed to set ashmem name");
                }
                env->ReleaseStringUTFChars(name, nameStr);
            }
        }

        if (ioctl(fd, ASHMEM_SET_SIZE, (size_t)size) < 0) {
            PLOGE("Failed to set ashmem size");
            close(fd);
            return -errno;
        }

        return fd;
    }

    extern "C" JNIEXPORT jint JNICALL
    Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeSetProt(JNIEnv*, jclass, jint fd, jint prot) {
        LoadSharedMemorySymbols();
        
        if (g_ASharedMemory_setProt) {
             return g_ASharedMemory_setProt(fd, prot);
        }

        if (ioctl(fd, ASHMEM_SET_PROT_MASK, (unsigned long)prot) < 0) {
            PLOGE("Failed to set ashmem prot");
            return -errno;
        }
        return 0;
    }

    void Service::InitService(JNIEnv *env) {
        if (initialized_) [[unlikely]] return;

        // ServiceManager
        if (auto service_manager_class = JNI_FindClass(env, "android/os/ServiceManager")) {
            service_manager_class_ = JNI_NewGlobalRef(env, service_manager_class);
        } else return;
        get_service_method_ = JNI_GetStaticMethodID(env, service_manager_class_, "getService",
                                                    "(Ljava/lang/String;)Landroid/os/IBinder;");
        if (!get_service_method_) return;

        // IBinder
        if (auto ibinder_class = JNI_FindClass(env, "android/os/IBinder")) {
            transact_method_ = JNI_GetMethodID(env, ibinder_class, "transact",
                                               "(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z");
        } else return;

        if (auto binder_class = JNI_FindClass(env, "android/os/Binder")) {
            binder_class_ = JNI_NewGlobalRef(env, binder_class);
        } else return;
        binder_ctor_ = JNI_GetMethodID(env, binder_class_, "<init>", "()V");

        // Parcel
        if (auto parcel_class = JNI_FindClass(env, "android/os/Parcel")) {
            parcel_class_ = JNI_NewGlobalRef(env, parcel_class);
        } else return;
        data_size_method_ = JNI_GetMethodID(env, parcel_class_, "dataSize","()I");
        obtain_method_ = JNI_GetStaticMethodID(env, parcel_class_, "obtain",
                                               "()Landroid/os/Parcel;");
        recycleMethod_ = JNI_GetMethodID(env, parcel_class_, "recycle", "()V");
        write_interface_token_method_ = JNI_GetMethodID(env, parcel_class_, "writeInterfaceToken",
                                                        "(Ljava/lang/String;)V");
        write_int_method_ = JNI_GetMethodID(env, parcel_class_, "writeInt", "(I)V");
        write_string_method_ = JNI_GetMethodID(env, parcel_class_, "writeString",
                                               "(Ljava/lang/String;)V");
        write_strong_binder_method_ = JNI_GetMethodID(env, parcel_class_, "writeStrongBinder",
                                                      "(Landroid/os/IBinder;)V");
        read_exception_method_ = JNI_GetMethodID(env, parcel_class_, "readException", "()V");
        read_int_method_ = JNI_GetMethodID(env, parcel_class_, "readInt", "()I");
        read_long_method_ = JNI_GetMethodID(env, parcel_class_, "readLong", "()J");
        read_strong_binder_method_ = JNI_GetMethodID(env, parcel_class_, "readStrongBinder",
                                                     "()Landroid/os/IBinder;");
        read_string_method_ = JNI_GetMethodID(env, parcel_class_, "readString",
                                                     "()Ljava/lang/String;");
        read_file_descriptor_method_ = JNI_GetMethodID(env, parcel_class_, "readFileDescriptor",
                                                       "()Landroid/os/ParcelFileDescriptor;");
//        createStringArray_ = env->GetMethodID(parcel_class_, "createStringArray",
//                                              "()[Ljava/lang/String;");

        if (auto parcel_file_descriptor_class = JNI_FindClass(env, "android/os/ParcelFileDescriptor")) {
            parcel_file_descriptor_class_ = JNI_NewGlobalRef(env, parcel_file_descriptor_class);
        } else {
            LOGE("ParcelFileDescriptor not found");
            return;
        }
        detach_fd_method_ = JNI_GetMethodID(env, parcel_file_descriptor_class_, "detachFd", "()I");

        if (auto dead_object_exception_class = JNI_FindClass(env,
                                                             "android/os/DeadObjectException")) {
            deadObjectExceptionClass_ = JNI_NewGlobalRef(env, dead_object_exception_class);
        }
        initialized_ = true;
    }

    std::string GetBridgeServiceName() {
        const auto &obfs_map = ConfigBridge::GetInstance()->obfuscation_map();
        static auto signature = obfs_map.at("org.lsposed.lspd.service.") + "BridgeService";
        return signature;
    }

    // SharedMemoryCompat registration helper
    LSP_DEF_NATIVE_METHOD(void, BridgeService, registerSharedMemoryCompat) {
        auto sm_clazz = env->FindClass("org/lsposed/lspd/os/SharedMemoryCompat");
        if (!sm_clazz) {
            if (env->ExceptionCheck())
                env->ExceptionClear();
            LOGE("SharedMemoryCompat not found in registerSharedMemoryCompat");
            return;
        }

        JNINativeMethod sm_methods[] = {
            {"nativeMap", "(IIZ)Ljava/nio/ByteBuffer;",
             (void*)Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeMap},
            {"nativeUnmap", "(Ljava/nio/ByteBuffer;I)V",
             (void*)Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeUnmap},
            {"nativeClose", "(I)V", (void*)Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeClose},
            {"nativeCreate", "(Ljava/lang/String;I)I",
             (void*)Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeCreate},
            {"nativeSetProt", "(II)I",
             (void*)Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeSetProt}};
        JNI_RegisterNatives(env, sm_clazz, sm_methods, 5);
        LOGD("Registered SharedMemoryCompat natives via helper");
    }

    void Service::HookBridge(const Context &context, JNIEnv *env) {
        static bool kHooked = false;
        // This should only be ran once, so unlikely
        if (kHooked) [[unlikely]] return;
        if (!initialized_) [[unlikely]] return;
        kHooked = true;
        if (auto bridge_service_class = context.FindClassFromCurrentLoader(env,
                                                                           GetBridgeServiceName()))
            bridge_service_class_ = JNI_NewGlobalRef(env, bridge_service_class);
        else {
            LOGE("server class not found");
            return;
        }

        constexpr const auto *hooker_sig = "(Landroid/os/IBinder;IJJI)Z";

        exec_transact_replace_methodID_ = JNI_GetStaticMethodID(env, bridge_service_class_,
                                                                "execTransact",
                                                                hooker_sig);
        if (!exec_transact_replace_methodID_) {
            LOGE("execTransact class not found");
            return;
        }

        auto binder_class = JNI_FindClass(env, "android/os/Binder");
        exec_transact_backup_methodID_ = JNI_GetMethodID(env, binder_class, "execTransact",
                                                         "(IJJI)Z");
        auto *setTableOverride = SandHook::ElfImg("/libart.so").getSymbAddress<void (*)(JNINativeInterface *)>(
                "_ZN3art9JNIEnvExt16SetTableOverrideEPK18JNINativeInterface");
        if (!setTableOverride) {
            LOGE("set table override not found");
        }
        memcpy(&native_interface_replace_, env->functions, sizeof(JNINativeInterface));

        call_boolean_method_va_backup_ = env->functions->CallBooleanMethodV;
        native_interface_replace_.CallBooleanMethodV = &call_boolean_method_va_replace;

        if (setTableOverride != nullptr) {
            setTableOverride(&native_interface_replace_);
        }
        if (auto activity_thread_class = JNI_FindClass(env, "android/app/IActivityManager$Stub")) {
            if (auto *set_activity_controller_field = JNI_GetStaticFieldID(env,
                                                                           activity_thread_class,
                                                                           "TRANSACTION_setActivityController",
                                                                           "I")) {
                SET_ACTIVITY_CONTROLLER_CODE = JNI_GetStaticIntField(env, activity_thread_class,
                                                                     set_activity_controller_field);
            }
        }

        auto &binder = lspd::GetLibBinder(false);
        IPCThreadState::Init(binder.get());
        lspd::GetLibBinder(true);

        JNINativeMethod m[] = {
            {"initializeAccessMatrix", "(Landroid/os/SharedMemory;)V", (void*)BridgeService_initializeAccessMatrix_SM},
            {"initializeAccessMatrix", "(Ljava/io/FileDescriptor;)V", (void*)BridgeService_initializeAccessMatrix_FD},
            LSP_NATIVE_METHOD(BridgeService, registerSharedMemoryCompat, "()V")};

        JNI_RegisterNatives(env, bridge_service_class_, m, 3);

        // Register SharedMemoryCompat natives for API 26 (runtime check done by
        // caller, but we register always to be safe)
        if (auto shared_mem_class =
                context.FindClassFromCurrentLoader(env, "org/lsposed/lspd/os/SharedMemoryCompat")) {
            JNINativeMethod sm_methods[] = {
                {"nativeMap", "(IIZ)Ljava/nio/ByteBuffer;",
                 (void*)Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeMap},
                {"nativeUnmap", "(Ljava/nio/ByteBuffer;I)V",
                 (void*)Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeUnmap},
                {"nativeClose", "(I)V", (void*)Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeClose},
                {"nativeCreate", "(Ljava/lang/String;I)I",
                 (void*)Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeCreate},
                {"nativeSetProt", "(II)I",
                 (void*)Java_org_lsposed_lspd_os_SharedMemoryCompat_nativeSetProt}};
            JNI_RegisterNatives(env, shared_mem_class, sm_methods, 5);
        } else {
            if (env->ExceptionCheck())
                env->ExceptionClear();
            LOGW("SharedMemoryCompat class not found");
        }

        LOGD("Done InitService");
    }

    ScopedLocalRef<jobject> Service::RequestBinder(JNIEnv *env, jstring nice_name) {
        if (!initialized_) [[unlikely]] {
            LOGE("Service not initialized");
            return {env, nullptr};
        }

        auto bridge_service_name = JNI_NewStringUTF(env, BRIDGE_SERVICE_NAME.data());
        auto bridge_service = JNI_CallStaticObjectMethod(env, service_manager_class_,
                                                         get_service_method_, bridge_service_name);
        if (!bridge_service) {
            LOGD("can't get {}", BRIDGE_SERVICE_NAME);
            return {env, nullptr};
        }

        auto heart_beat_binder = JNI_NewObject(env, binder_class_, binder_ctor_);

        auto data = JNI_CallStaticObjectMethod(env, parcel_class_, obtain_method_);
        auto reply = JNI_CallStaticObjectMethod(env, parcel_class_, obtain_method_);

        auto descriptor = JNI_NewStringUTF(env, BRIDGE_SERVICE_DESCRIPTOR.data());
        JNI_CallVoidMethod(env, data, write_interface_token_method_, descriptor);
        JNI_CallVoidMethod(env, data, write_int_method_, BRIDGE_ACTION_GET_BINDER);
        JNI_CallVoidMethod(env, data, write_string_method_, nice_name);
        JNI_CallVoidMethod(env, data, write_strong_binder_method_, heart_beat_binder);

        auto res = JNI_CallBooleanMethod(env, bridge_service, transact_method_,
                                         BRIDGE_TRANSACTION_CODE,
                                         data,
                                         reply, 0);

        ScopedLocalRef<jobject> service = {env, nullptr};
        if (res) {
            JNI_CallVoidMethod(env, reply, read_exception_method_);
            service = JNI_CallObjectMethod(env, reply, read_strong_binder_method_);
        }
        JNI_CallVoidMethod(env, data, recycleMethod_);
        JNI_CallVoidMethod(env, reply, recycleMethod_);
        if (service) {
            JNI_NewGlobalRef(env, heart_beat_binder);
        }

        return service;
    }

    ScopedLocalRef<jobject> Service::RequestSystemServerBinder(JNIEnv *env) {
        if (!initialized_) [[unlikely]] {
            LOGE("Service not initialized");
            return {env, nullptr};
        }
        // Get Binder for LSPSystemServerService.
        // The binder itself was inject into system service "serial"
        auto bridge_service_name = JNI_NewStringUTF(env, SYSTEM_SERVER_BRIDGE_SERVICE_NAME);
        ScopedLocalRef<jobject> binder{env, nullptr};
        for (int i = 0; i < 3; ++i) {
            binder = JNI_CallStaticObjectMethod(env, service_manager_class_,
                                                get_service_method_, bridge_service_name);
            if (binder) {
                LOGD("Got binder for system server");
                break;
            }
            LOGI("Fail to get binder for system server, try again in 1s");
            using namespace std::chrono_literals;
            std::this_thread::sleep_for(1s);
        }
        if (!binder) {
            LOGW("Fail to get binder for system server");
            return {env, nullptr};
        }
        return binder;
    }

    ScopedLocalRef<jobject> Service::RequestApplicationBinderFromSystemServer(JNIEnv *env, const ScopedLocalRef<jobject> &system_server_binder) {
        auto heart_beat_binder = JNI_NewObject(env, binder_class_, binder_ctor_);
        Wrapper wrapper{env, this};

        JNI_CallVoidMethod(env, wrapper.data, write_int_method_, getuid());
        JNI_CallVoidMethod(env, wrapper.data, write_int_method_, getpid());
        JNI_CallVoidMethod(env, wrapper.data, write_string_method_, JNI_NewStringUTF(env, "system"));
        JNI_CallVoidMethod(env, wrapper.data, write_strong_binder_method_, heart_beat_binder);

        auto res = wrapper.transact(system_server_binder, BRIDGE_TRANSACTION_CODE);

        ScopedLocalRef<jobject> app_binder = {env, nullptr};
        if (res) {
            JNI_CallVoidMethod(env, wrapper.reply, read_exception_method_);
            app_binder = JNI_CallObjectMethod(env, wrapper.reply, read_strong_binder_method_);
        }
        if (app_binder) {
            JNI_NewGlobalRef(env, heart_beat_binder);
        }
        LOGD("app_binder: {}", static_cast<void*>(app_binder.get()));
        return app_binder;
    }

    std::tuple<int, size_t> Service::RequestLSPDex(JNIEnv *env, const ScopedLocalRef<jobject> &binder) {
        Wrapper wrapper{env, this};
        bool res = wrapper.transact(binder, DEX_TRANSACTION_CODE);
        if (!res) {
            LOGE("Service::RequestLSPDex: transaction failed?");
            return {-1, 0};
        }
        auto parcel_fd = JNI_CallObjectMethod(env, wrapper.reply, read_file_descriptor_method_);
        if (!parcel_fd) {
            LOGE("Service::RequestLSPDex: failed to read file descriptor");
            return {-1, 0};
        }
        int fd = JNI_CallIntMethod(env, parcel_fd, detach_fd_method_);
        auto size = static_cast<size_t>(JNI_CallLongMethod(env, wrapper.reply, read_long_method_));
        LOGD("fd={}, size={}", fd, size);
        return {fd, size};
    }

    std::map<std::string, std::string>
    Service::RequestObfuscationMap(JNIEnv *env, const ScopedLocalRef<jobject> &binder) {
        std::map<std::string, std::string> ret;
        Wrapper wrapper{env, this};
        bool res = wrapper.transact(binder, OBFUSCATION_MAP_TRANSACTION_CODE);

        if (!res) {
            LOGE("Service::RequestObfuscationMap: transaction failed?");
            return ret;
        }
        auto size = JNI_CallIntMethod(env, wrapper.reply, read_int_method_);
        LOGI("RequestObfuscationMap: parcel size={}", size);
        if (!size || (size & 1) == 1) {
            LOGW("Service::RequestObfuscationMap: invalid parcel size");
        }

        auto get_string = [this, &wrapper, &env]() -> std::string {
            auto s = JNI_Cast<jstring>(JNI_CallObjectMethod(env, wrapper.reply, read_string_method_));
            return JUTFString(s);
        };
        for (auto i = 0; i < size / 2; i++) {
            // DO NOT TOUCH, or value evaluates before key.
            auto &&key = get_string();
            ret[key] = get_string();
        }
#ifndef NDEBUG
        for (const auto &i: ret) {
            LOGD("{} => {}", i.first, i.second);
        }
#endif

        return ret;
    }
}  // namespace lspd
