cmake_minimum_required(VERSION 3.22.1)
project(cannoli_hash C)

# Built from RetroArch's rcheevos, not a copy: the launcher resolves a game id from a rom hash that
# the in-game rcheevos has to agree with. A second copy drifts and the two disagree about which game
# a rom is.
set(RCHEEVOS ${CMAKE_CURRENT_SOURCE_DIR}/../../../../retroarch/deps/rcheevos)
set(LIBRETRO_COMMON ${CMAKE_CURRENT_SOURCE_DIR}/../../../../retroarch/libretro-common)

file(GLOB RHASH_SRC ${RCHEEVOS}/src/rhash/*.c)

# RetroArch's md5.h maps rcheevos' md5_init/append/finish onto libretro-common's MD5 instead of
# shipping an implementation, so that md5.c is not optional.
add_library(cannoli_hash SHARED
    ra_hash_jni.c
    ${RHASH_SRC}
    ${RCHEEVOS}/src/rc_compat.c
    ${RCHEEVOS}/src/rc_util.c
    ${LIBRETRO_COMMON}/utils/md5.c)

target_include_directories(cannoli_hash PRIVATE
    ${RCHEEVOS}/include
    ${RCHEEVOS}/src
    ${LIBRETRO_COMMON}/include)

# No Lua rules: a ROM hash needs none, and it drags in parts of rcheevos this does not build.
target_compile_definitions(cannoli_hash PRIVATE RC_DISABLE_LUA)

find_library(log-lib log)
target_link_libraries(cannoli_hash ${log-lib})
