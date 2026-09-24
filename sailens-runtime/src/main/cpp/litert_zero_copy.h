#ifndef SAILENS_LITERT_ZERO_COPY_H
#define SAILENS_LITERT_ZERO_COPY_H

#include <dlfcn.h>

#include <atomic>
#include <cstddef>
#include <cstdint>

// Zero-copy access to a LiteRT output tensor.
//
// Reading a model output through TensorBuffer.readFloat() copies the whole tensor into a new JVM
// array; for a 640x640x19 semantic output that is a 31 MB allocation per frame. Instead the
// kernels lock the buffer and read it in place, which is what keeps outputReadTimeMs at
// approximately 0 (architecture.md §6.5, §12.3).
//
// Three things have to be right for that, and until 2026-09 none of them was:
//
// 1. Finding the symbols. libLiteRt.so is loaded by the Kotlin layer through System.loadLibrary,
//    i.e. RTLD_LOCAL, and this library deliberately does not link against it. For apps targeting
//    API 23+ bionic's dlsym(RTLD_DEFAULT, ...) skips RTLD_LOCAL libraries, so that lookup always
//    returned null and every kernel silently fell back to the copying path. The library is looked
//    up by name instead (dlopen with RTLD_NOLOAD: it is already loaded; this only gets a handle).
//
// 2. The signature. LiteRT 2.1.x declares
//      LiteRtStatus LiteRtLockTensorBuffer(LiteRtTensorBuffer, void** host_mem_addr,
//                                          LiteRtTensorBufferLockMode lock_mode);
//    The previous declaration here had the last two parameters swapped.
//
// 3. The handle. The Kotlin TensorBuffer's JNI handle is a pointer to the C++ litert::TensorBuffer
//    wrapper, not to the C LiteRtTensorBuffer. The wrapper is a BaseHandle<LiteRtTensorBuffer>
//    (litert/cc/internal/litert_handle.h, v2.1.5): no virtual functions, and its first member is
//    a std::unique_ptr<LiteRtTensorBufferT, std::function<...>>. The C handle is read from the
//    first word of that object.
//
//    **This is a known ABI dependency, not a contract.** It holds because libLiteRt.so is built
//    with the NDK's libc++ (its RTTI names are std::__ndk1::...), whose unique_ptr stores the
//    pointer first (_LIBCPP_COMPRESSED_PAIR(pointer, __ptr_, deleter_type, __deleter_)). The
//    C++ standard does not promise that layout, and nothing here can detect a wrong one safely:
//    the packed-size check below dereferences the unwrapped pointer inside LiteRT, so a layout
//    change would crash rather than fall back. What the check does catch is a *different*
//    buffer (wrong tensor, wrong element type): its byte count will not match.
//
// Pinned to LiteRT 2.1.5 (gradle/libs.versions.toml); LiteRtVersionPinTest fails when that
// changes. Upgrading LiteRT means re-checking 2 and 3 on a device (architecture.md §12.3).
//
// This header is owned by sailens-runtime and included by the vision and Guidance CMake builds.
// It is a file-level include on purpose: it does not create a Gradle or Kotlin dependency in the
// wrong direction (architecture.md §6.10).

namespace sailens {

using LiteRtTensorBufferHandle = void*;
using FnLiteRtLockTensorBuffer = int (*)(LiteRtTensorBufferHandle buffer, void** host_mem_addr, int lock_mode);
using FnLiteRtUnlockTensorBuffer = int (*)(LiteRtTensorBufferHandle buffer);
using FnLiteRtGetTensorBufferPackedSize = int (*)(LiteRtTensorBufferHandle buffer, size_t* size);

constexpr int kLiteRtTensorBufferLockModeRead = 0;
constexpr int kLiteRtStatusOk = 0;
constexpr const char* kLiteRtLibraryName = "libLiteRt.so";

struct LiteRtZeroCopyApi {
    FnLiteRtLockTensorBuffer lock = nullptr;
    FnLiteRtUnlockTensorBuffer unlock = nullptr;
    FnLiteRtGetTensorBufferPackedSize packedSize = nullptr;

    bool complete() const { return lock != nullptr && unlock != nullptr && packedSize != nullptr; }
};

inline std::atomic<const LiteRtZeroCopyApi*> g_liteRtApi{nullptr};
inline std::atomic<int> g_liteRtResolveAttempts{0};
// Resolution is retried a few times rather than cached as a failure forever: a kernel called
// before the Kotlin layer loaded LiteRT would otherwise disable zero-copy for the whole process.
constexpr int kMaxLiteRtResolveAttempts = 16;

inline void* resolveLiteRtSymbol(void* library, const char* name) {
    if (library != nullptr) {
        if (void* symbol = dlsym(library, name)) return symbol;
    }
    return dlsym(RTLD_DEFAULT, name);
}

/** The resolved API, or nullptr when LiteRT is not (yet) loaded in this process. */
inline const LiteRtZeroCopyApi* liteRtZeroCopyApi() {
    if (const LiteRtZeroCopyApi* api = g_liteRtApi.load(std::memory_order_acquire)) return api;
    if (g_liteRtResolveAttempts.fetch_add(1, std::memory_order_relaxed) >= kMaxLiteRtResolveAttempts) {
        return nullptr;
    }
    // Not closed: RTLD_NOLOAD adds a reference to a library the Kotlin layer keeps loaded for the
    // life of the process, and the resolved function pointers must stay valid as long as ours.
    void* library = dlopen(kLiteRtLibraryName, RTLD_NOW | RTLD_NOLOAD);
    auto* api = new LiteRtZeroCopyApi{
            reinterpret_cast<FnLiteRtLockTensorBuffer>(resolveLiteRtSymbol(library, "LiteRtLockTensorBuffer")),
            reinterpret_cast<FnLiteRtUnlockTensorBuffer>(resolveLiteRtSymbol(library, "LiteRtUnlockTensorBuffer")),
            reinterpret_cast<FnLiteRtGetTensorBufferPackedSize>(
                    resolveLiteRtSymbol(library, "LiteRtGetTensorBufferPackedSize")),
    };
    if (!api->complete()) {
        delete api;
        return nullptr;
    }
    const LiteRtZeroCopyApi* expected = nullptr;
    if (!g_liteRtApi.compare_exchange_strong(expected, api, std::memory_order_acq_rel)) {
        delete api;  // another thread won; its copy is identical
        return expected;
    }
    return api;
}

/** True when the symbols resolved, i.e. the zero-copy path is usable in this process. */
inline bool liteRtZeroCopyAvailable() {
    return liteRtZeroCopyApi() != nullptr;
}

/** The C LiteRtTensorBuffer inside the Kotlin handle's C++ wrapper (see note 3 above). */
inline LiteRtTensorBufferHandle unwrapKotlinTensorBuffer(long long kotlinHandle) {
    if (kotlinHandle == 0) return nullptr;
    return *reinterpret_cast<LiteRtTensorBufferHandle const*>(static_cast<uintptr_t>(kotlinHandle));
}

/**
 * Locks the buffer behind a Kotlin TensorBuffer handle for reading, after checking that it holds
 * exactly [expectedBytes]. Returns the host address, or nullptr when the API is unavailable, the
 * buffer is not the expected size, or the lock fails; on success the caller must call
 * [unlockTensorBuffer] with the same handle. See note 3: a changed wrapper layout is not among the
 * cases this can report.
 */
inline void* lockTensorBufferForRead(long long kotlinHandle, size_t expectedBytes) {
    const LiteRtZeroCopyApi* api = liteRtZeroCopyApi();
    if (api == nullptr || kotlinHandle == 0 || expectedBytes == 0) return nullptr;
    LiteRtTensorBufferHandle buffer = unwrapKotlinTensorBuffer(kotlinHandle);
    if (buffer == nullptr) return nullptr;

    size_t packedBytes = 0;
    if (api->packedSize(buffer, &packedBytes) != kLiteRtStatusOk || packedBytes != expectedBytes) {
        return nullptr;
    }
    void* hostPtr = nullptr;
    if (api->lock(buffer, &hostPtr, kLiteRtTensorBufferLockModeRead) != kLiteRtStatusOk) {
        return nullptr;
    }
    return hostPtr;
}

inline void unlockTensorBuffer(long long kotlinHandle) {
    const LiteRtZeroCopyApi* api = g_liteRtApi.load(std::memory_order_acquire);
    LiteRtTensorBufferHandle buffer = unwrapKotlinTensorBuffer(kotlinHandle);
    if (api != nullptr && buffer != nullptr) api->unlock(buffer);
}

}  // namespace sailens

#endif  // SAILENS_LITERT_ZERO_COPY_H
