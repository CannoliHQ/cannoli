cmake_minimum_required(VERSION 3.22.1)
project(cannoli_hash C)

# RetroAchievements identifies a game by hashing the ROM, and the launcher needs that before a game
# is ever run: it is how a preload knows which set to fetch. Only rc_hash is built here. The rest of
# rcheevos lives inside RetroArch, in the other process, and duplicating it would mean two clients
# with two opinions about the same account.
file(GLOB RHASH_SRC third_party/rcheevos/src/rhash/*.c)

add_library(cannoli_hash SHARED
    ra_hash_jni.c
    ${RHASH_SRC}
    third_party/rcheevos/src/rc_compat.c
    third_party/rcheevos/src/rc_util.c)

target_include_directories(cannoli_hash PRIVATE
    third_party/rcheevos/include
    third_party/rcheevos/src)

# No Lua rules and no disc readers pulled in by the client: a ROM hash needs neither, and both drag
# in the parts of rcheevos this deliberately does not build.
target_compile_definitions(cannoli_hash PRIVATE RC_DISABLE_LUA)

find_library(log-lib log)
target_link_libraries(cannoli_hash ${log-lib})
