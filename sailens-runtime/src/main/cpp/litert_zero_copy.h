#ifndef SAILENS_LITERT_ZERO_COPY_H
#define SAILENS_LITERT_ZERO_COPY_H

#include <dlfcn.h>
#include <mutex>

// Zero-copy access to a LiteRT output tensor.
//
// Reading a model output through TensorBuffer.readFloat() copies the whole tensor into a JVM
// array; for a 640x640x19 semantic output that copy is the difference between the semantic
// postprocess showing up in the frame budget and not. Instead the kernels lock the buffer and
// read it in place, which is what keeps outputReadTimeMs at approximately 0 (architecture.md
// §6.5, §12.3).
//
// The symbols are resolved with dlsym rather than linked: libLiteRt.so is already loaded by the
// Kotlin layer (System.loadLibrary("LiteRt")), so RTLD_DEFAULT finds them without an explicit
// dlopen and without this build having to link against LiteRT at all.
//
// This header is owned by sailens-runtime and included by the vision and Guidance CMake builds.
// It is a file-level include on purpose: it does not create a Gradle or Kotlin dependency in the
// wrong direction (architecture.md §6.10).

namespace sailens {

using FnLiteRtLockTensorBuffer = int (*)(void* buffer, int lock_mode, void** host_mem_addr);
using FnLiteRtUnlockTensorBuffer = int (*)(void* buffer);

inline FnLiteRtLockTensorBuffer g_liteRtLock = nullptr;
inline FnLiteRtUnlockTensorBuffer g_liteRtUnlock = nullptr;

constexpr int kLiteRtTensorBufferLockModeRead = 0;
constexpr int kLiteRtStatusOk = 0;

inline void initLiteRtApi() {
    static std::once_flag flag;
    std::call_once(flag, []() {
        g_liteRtLock = reinterpret_cast<FnLiteRtLockTensorBuffer>(
                dlsym(RTLD_DEFAULT, "LiteRtLockTensorBuffer"));
        g_liteRtUnlock = reinterpret_cast<FnLiteRtUnlockTensorBuffer>(
                dlsym(RTLD_DEFAULT, "LiteRtUnlockTensorBuffer"));
    });
}

/** True when both symbols resolved, i.e. the zero-copy path is usable in this process. */
inline bool liteRtZeroCopyAvailable() {
    initLiteRtApi();
    return g_liteRtLock != nullptr && g_liteRtUnlock != nullptr;
}

/** Locks the buffer for reading. Returns nullptr when the handle is unusable. */
inline void* lockTensorBufferForRead(long long handle) {
    if (!liteRtZeroCopyAvailable() || handle == 0) {
        return nullptr;
    }
    void* hostPtr = nullptr;
    if (g_liteRtLock(reinterpret_cast<void*>(handle),
                     kLiteRtTensorBufferLockModeRead,
                     &hostPtr) != kLiteRtStatusOk) {
        return nullptr;
    }
    return hostPtr;
}

inline void unlockTensorBuffer(long long handle) {
    if (g_liteRtUnlock != nullptr && handle != 0) {
        g_liteRtUnlock(reinterpret_cast<void*>(handle));
    }
}

}  // namespace sailens

#endif  // SAILENS_LITERT_ZERO_COPY_H
