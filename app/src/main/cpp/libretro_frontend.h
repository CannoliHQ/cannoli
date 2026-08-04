#pragma once

// Upstream libretro.h declares the core API as functions a core implements
// (RETRO_API void retro_init(void)). A frontend resolves the same API through
// dlsym instead, so it needs function-pointer types that upstream does not
// define. These signatures must track libretro.h.

#include "libretro.h"

typedef void (*retro_set_environment_t)(retro_environment_t);
typedef void (*retro_set_video_refresh_t)(retro_video_refresh_t);
typedef void (*retro_set_audio_sample_t)(retro_audio_sample_t);
typedef void (*retro_set_audio_sample_batch_t)(retro_audio_sample_batch_t);
typedef void (*retro_set_input_poll_t)(retro_input_poll_t);
typedef void (*retro_set_input_state_t)(retro_input_state_t);
typedef void (*retro_init_t)(void);
typedef void (*retro_deinit_t)(void);
typedef unsigned (*retro_api_version_t)(void);
typedef void (*retro_get_system_info_t)(struct retro_system_info *info);
typedef void (*retro_get_system_av_info_t)(struct retro_system_av_info *info);
typedef void (*retro_set_controller_port_device_t)(unsigned port, unsigned device);
typedef void (*retro_reset_t)(void);
typedef void (*retro_run_t)(void);
typedef size_t (*retro_serialize_size_t)(void);
typedef bool (*retro_serialize_t)(void *data, size_t size);
typedef bool (*retro_unserialize_t)(const void *data, size_t size);
typedef bool (*retro_load_game_t)(const struct retro_game_info *game);
typedef void (*retro_unload_game_t)(void);
typedef void *(*retro_get_memory_data_t)(unsigned id);
typedef size_t (*retro_get_memory_size_t)(unsigned id);
typedef void (*retro_cheat_reset_t)(void);
typedef void (*retro_cheat_set_t)(unsigned, bool, const char *);
