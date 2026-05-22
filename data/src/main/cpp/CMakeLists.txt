cmake_minimum_required(VERSION 3.10.2)
project("sailens_ml")

# Set C++ standard for NDK r28 compatibility
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(
        sailens_ml
        SHARED
        sailens_ml_jni.cpp
)

# NDK r28 automatically handles 16KB page size for arm64-v8a and x86_64
# The following configuration is kept for explicit documentation
# but is no longer strictly necessary with NDK r28
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    message(STATUS "NDK r28: 16KB page size support enabled by default for ${ANDROID_ABI}")
endif()

# Ensure proper C++ standard library linkage for NDK r28
# This is critical to resolve undefined symbol errors like std::__throw_length_error
set_target_properties(sailens_ml PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON
)

# Add compile definitions and flags for NDK r28 compatibility
target_compile_options(sailens_ml PRIVATE
        -fexceptions
        -frtti
)

find_library(
        log-lib
        log
)

target_link_libraries(
        sailens_ml
        c++_shared
        ${log-lib}
)
