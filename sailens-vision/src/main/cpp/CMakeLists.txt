cmake_minimum_required(VERSION 3.10.2)
project("sailens_vision")

# Vision-side native code: generic semantic argmax and detection box decoding + NMS.
# Nothing here knows about navigation; the fused Guidance scoring kernel lives with Guidance.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(
        sailens_vision
        SHARED
        sailens_vision_jni.cpp
)

# Runtime-owned headers, included by file path. This is deliberately not a Gradle dependency:
# sailens-runtime owns the LiteRT zero-copy helper and the tensor-layout constants, and sharing
# them as headers avoids a second copy drifting (architecture.md §6.10).
target_include_directories(sailens_vision PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../sailens-runtime/src/main/cpp
)

set_target_properties(sailens_vision PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON
)

target_compile_options(sailens_vision PRIVATE
        -O3
        -ffast-math
        -fexceptions
        -frtti
)

find_library(
        log-lib
        log
)

target_link_libraries(
        sailens_vision
        c++_shared
        ${log-lib}
)
