cmake_minimum_required(VERSION 3.22.1)

add_library(calculator SHARED
    src/calculator.cpp
    src/calculator_jni.cpp
)

target_include_directories(calculator PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

find_library(log-lib log)
target_link_libraries(calculator ${log-lib})
