cmake_minimum_required(VERSION 3.22.1)

project(maaframework-rootlauncher C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(rootlauncher launcher.c)
set_target_properties(rootlauncher PROPERTIES
    OUTPUT_NAME "librootlauncher"
    SUFFIX ".so"
    LINKER_LANGUAGE C)

add_library(bridge SHARED
    bridge.cpp
    bridge_capture.cpp
    bridge_frame_buffer.cpp
    bridge_preview.cpp
)
set_target_properties(bridge PROPERTIES
    OUTPUT_NAME "bridge")

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(android-lib android)
find_library(mediandk-lib mediandk)
find_library(egl-lib EGL)
find_library(glesv2-lib GLESv2)

target_link_libraries(rootlauncher ${log-lib})
target_link_libraries(bridge ${log-lib} ${jnigraphics-lib} ${android-lib} ${mediandk-lib} ${egl-lib} ${glesv2-lib})
