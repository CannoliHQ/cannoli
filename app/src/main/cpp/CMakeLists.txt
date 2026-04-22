cmake_minimum_required(VERSION 3.22.1)
project(cannoli_native)

find_library(log-lib log)
find_package(oboe REQUIRED CONFIG)

# --- speex_resampler (libspeexdsp resampler, compiled standalone) ---
add_library(speex_resampler STATIC
    third_party/speexdsp/libspeexdsp/resample.c)
target_compile_definitions(speex_resampler PUBLIC
    OUTSIDE_SPEEX
    RANDOM_PREFIX=cannoli)
target_compile_definitions(speex_resampler PRIVATE
    FLOATING_POINT
    EXPORT=)
target_include_directories(speex_resampler PUBLIC
    third_party/speexdsp/include/speex
    third_party/speexdsp/libspeexdsp)
target_compile_options(speex_resampler PRIVATE -Wno-unused-function -Wno-unused-variable)

# Copy libretro.h into rcheevos include so rc_libretro.c can find it via <libretro.h>
configure_file(${CMAKE_CURRENT_SOURCE_DIR}/libretro.h
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/rcheevos/include/libretro.h COPYONLY)

# --- rcheevos (RetroAchievements client library) ---
file(GLOB RCHEEVOS_SRC
    third_party/rcheevos/src/rcheevos/*.c
    third_party/rcheevos/src/rapi/*.c
    third_party/rcheevos/src/rhash/*.c
    third_party/rcheevos/src/rc_client.c
    third_party/rcheevos/src/rc_compat.c
    third_party/rcheevos/src/rc_libretro.c
    third_party/rcheevos/src/rc_util.c
    third_party/rcheevos/src/rc_version.c)
add_library(rcheevos STATIC ${RCHEEVOS_SRC})
target_include_directories(rcheevos PUBLIC third_party/rcheevos/include)
target_include_directories(rcheevos PRIVATE third_party/rcheevos/src ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(rcheevos PRIVATE RC_DISABLE_LUA)
target_compile_definitions(rcheevos PUBLIC RC_CLIENT_SUPPORTS_HASH)

# --- retro_bridge (libretro core runner) ---
add_library(retro_bridge SHARED libretro_bridge.cpp ra_integration.c native_audio.cpp)
target_link_libraries(retro_bridge rcheevos speex_resampler oboe::oboe z ${log-lib})
target_include_directories(retro_bridge PRIVATE third_party/rcheevos/include third_party/rcheevos/src)

