cmake_minimum_required(VERSION 3.22.1)

add_library(filemanager SHARED
    src/filemanager.cpp
    src/filemanager_jni.cpp
)

target_include_directories(filemanager PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

find_library(log-lib log)
target_link_libraries(filemanager ${log-lib})
