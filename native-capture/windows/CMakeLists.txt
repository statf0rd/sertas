cmake_minimum_required(VERSION 3.15)
cmake_policy(SET CMP0091 NEW) # MSVC_RUNTIME_LIBRARY
project(sertas_capture CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# Статический MSVC-рантайм (/MT) — DLL самодостаточна, без VC++ Redist у пользователя.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

add_library(sertas_capture SHARED sertas_capture.cpp)
set_target_properties(sertas_capture PROPERTIES PREFIX "") # sertas_capture.dll

target_include_directories(sertas_capture PRIVATE
    "$ENV{JAVA_HOME}/include"
    "$ENV{JAVA_HOME}/include/win32")

target_link_libraries(sertas_capture PRIVATE d3d11 dxgi)
