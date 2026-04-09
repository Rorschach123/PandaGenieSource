cmake_minimum_required(VERSION 3.22.1)

set(SHARED_NATIVE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../shared_native)

add_library(archive_module SHARED
    src/archive_module.cpp
    src/archive_jni.cpp
    src/miniz.cpp
    ${SHARED_NATIVE_DIR}/sandbox_guard.cpp
)

target_include_directories(archive_module PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${SHARED_NATIVE_DIR}
)

find_library(log-lib log)
find_library(z-lib z)
target_link_libraries(archive_module ${log-lib} ${z-lib})
