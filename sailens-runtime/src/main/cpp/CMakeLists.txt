cmake_minimum_required(VERSION 3.10.2)
project("sailens_runtime")

# Runtime-side native code: YUV -> tensor preprocessing and float -> int8
# quantization. Generic image work only; nothing here knows about navigation.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(
        sailens_runtime
        SHARED
        sailens_runtime_jni.cpp
)

set_target_properties(sailens_runtime PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON
)

target_compile_options(sailens_runtime PRIVATE
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
        sailens_runtime
        c++_shared
        ${log-lib}
)
