cmake_minimum_required(VERSION 3.10.2)
project(needle2jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Needle 2 static library
add_library(needle_static STATIC IMPORTED)
set_target_properties(needle_static PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libneedle.a
)

# JNI wrapper library
add_library(needle2jni SHARED needle_jni.cpp)

# Link against Needle static library and required system libraries
target_link_libraries(needle2jni
    needle_static
    log
    dl
    m
    c
    android
    c++_shared
)

# Include directories
target_include_directories(needle2jni PRIVATE ${CMAKE_SOURCE_DIR})

# C++ flags
target_compile_options(needle2jni PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)

# Linker flags for static library
target_link_options(needle2jni PRIVATE
    -Wl,--no-undefined
    -Wl,--as-needed
)