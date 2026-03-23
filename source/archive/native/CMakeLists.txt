cmake_minimum_required(VERSION 3.22.1)

add_library(archive_module SHARED
    src/archive_module.cpp
    src/archive_jni.cpp
    src/miniz.cpp
)

target_include_directories(archive_module PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

find_library(log-lib log)
find_library(z-lib z)
target_link_libraries(archive_module ${log-lib} ${z-lib})
