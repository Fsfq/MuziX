cmake_minimum_required(VERSION 3.22.1)

project(track_matcher VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(SOURCES
    track_matcher_core.cpp
)

# Если собираем под Android (NDK)
if (ANDROID)
    find_library(log-lib log)
    list(APPEND SOURCES track_matcher_jni.cpp)

    add_library(track_matcher SHARED ${SOURCES})
    target_link_libraries(track_matcher ${log-lib})

    target_compile_options(track_matcher PRIVATE
        -O3
        -fvisibility=hidden
        -Wall
        -Wextra
    )
else()
    # Сборка под ПК (Windows .dll / Linux .so / macOS .dylib)
    find_package(JNI QUIET)
    if (JNI_FOUND)
        list(APPEND SOURCES track_matcher_jni.cpp)
        include_directories(${JNI_INCLUDE_DIRS})
    endif()

    add_library(track_matcher SHARED ${SOURCES})

    if (MSVC)
        target_compile_options(track_matcher PRIVATE /O2 /W4 /utf-8)
    else()
        target_compile_options(track_matcher PRIVATE -O3 -fvisibility=hidden -Wall)
    endif()
endif()
