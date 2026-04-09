cmake_minimum_required(VERSION 3.22.1)

set(SHARED_NATIVE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../shared_native)

add_library(filemanager SHARED
    src/filemanager.cpp
    src/filemanager_jni.cpp
    ${SHARED_NATIVE_DIR}/sandbox_guard.cpp
)

target_include_directories(filemanager PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${SHARED_NATIVE_DIR}
)

find_library(log-lib log)
target_link_libraries(filemanager ${log-lib})
