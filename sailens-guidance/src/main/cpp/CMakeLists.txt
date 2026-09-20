cmake_minimum_required(VERSION 3.10.2)
project("sailens_guidance")

# Set C++ standard for NDK r28 compatibility
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(
        sailens_guidance
        SHARED
        sailens_guidance_jni.cpp
)

# NDK r28 automatically handles 16KB page size for arm64-v8a and x86_64
# The following configuration is kept for explicit documentation
# but is no longer strictly necessary with NDK r28
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    message(STATUS "NDK r28: 16KB page size support enabled by default for ${ANDROID_ABI}")
endif()

# Ensure proper C++ standard library linkage for NDK r28
# This is critical to resolve undefined symbol errors like std::__throw_length_error
set_target_properties(sailens_guidance PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON
)

# Add compile definitions and flags for NDK r28 compatibility
target_compile_options(sailens_guidance PRIVATE
        -O3
        -ffast-math
        -fexceptions
        -frtti
)

find_library(
        log-lib
        log
)

# Runtime-owned headers, included by file path. Deliberately not a Gradle dependency:
# sailens-runtime owns the LiteRT zero-copy helper and the tensor-layout constants, and sharing
# them as headers avoids a second copy drifting (architecture.md §6.10).
target_include_directories(sailens_guidance PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../sailens-runtime/src/main/cpp
)

target_link_libraries(
        sailens_guidance
        c++_shared
        ${log-lib}
)
