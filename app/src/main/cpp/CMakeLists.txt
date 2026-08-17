cmake_minimum_required(VERSION 3.18.1)

project(tun2proxy_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(tun2proxy_jni SHARED native-lib.cpp)

target_include_directories(
    tun2proxy_jni
    PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)

find_library(log-lib log)

target_link_libraries(
    tun2proxy_jni
    ${log-lib}
)

# libtun2proxy.so подключается через -L/-l, а НЕ как IMPORTED-цель с полным путём.
#
# С IMPORTED_LOCATION компоновщик записывает в DT_NEEDED тот путь, который ему
# дали, и в библиотеку попадает абсолютный путь сборочной машины:
# `D:/Documents/.../jniLibs/arm64-v8a/libtun2proxy.so`. На устройстве такого пути
# нет, и загрузка падает с `dlopen failed: library ... not found` — то есть режим
# Opera перестаёт работать у всех, кроме того, кто собирал. Через -l в DT_NEEDED
# уходит короткое имя, а сам libtun2proxy.so к этому моменту уже загружен
# из ProxyVpnService (`System.loadLibrary("tun2proxy")` идёт первым).
target_link_options(
    tun2proxy_jni
    PRIVATE
    "-L${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}"
    "-ltun2proxy"
)
