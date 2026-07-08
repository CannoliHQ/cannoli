#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

void cheat_engine_set_table(const int64_t *table, size_t cheat_count);
void cheat_engine_clear(void);
void cheat_engine_invalidate_memory(void);
void cheat_engine_apply(void);
uint64_t cheat_engine_total_memory(void);

#ifdef __cplusplus
}
#endif
