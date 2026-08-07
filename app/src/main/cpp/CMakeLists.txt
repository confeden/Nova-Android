cmake_minimum_required(VERSION 3.18.1)

project(tun2proxy_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(tun2proxy SHARED IMPORTED)
set_target_properties(
    tun2proxy
    PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libtun2proxy.so"
)

add_library(tun2proxy_jni SHARED native-lib.cpp)

target_include_directories(
    tun2proxy_jni
    PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)

find_library(log-lib log)

target_link_libraries(
    tun2proxy_jni
    tun2proxy
    ${log-lib}
)
