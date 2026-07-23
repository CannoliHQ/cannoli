#include "cheat_engine.h"
#include "libretro.h"

#include <vector>

// Not defined by this project's trimmed libretro.h; value matches the libretro spec.
#ifndef RETRO_MEMDESC_SYSTEM_RAM
#define RETRO_MEMDESC_SYSTEM_RAM (1 << 2)
#endif

extern "C" void *bridge_get_memory_data(unsigned id);
extern "C" size_t bridge_get_memory_size(unsigned id);
extern "C" const struct retro_memory_map *bridge_get_memory_map(void);

namespace {

constexpr int STRIDE = CHEAT_ENGINE_STRIDE;

enum {
    TYPE_DISABLED = 0,
    TYPE_SET_TO_VALUE = 1,
    TYPE_INCREASE_VALUE = 2,
    TYPE_DECREASE_VALUE = 3,
    TYPE_RUN_NEXT_IF_EQ = 4,
    TYPE_RUN_NEXT_IF_NEQ = 5,
    TYPE_RUN_NEXT_IF_LT = 6,
    TYPE_RUN_NEXT_IF_GT = 7,
};

struct Cheat {
    bool enabled;
    bool retro_handler;
    uint64_t address;
    uint32_t address_mask;
    uint32_t value;
    int type;
    int search_size;
    bool big_endian;
    uint32_t repeat_count;
    uint32_t repeat_add_to_value;
    uint32_t repeat_add_to_address;
};

struct Buffer {
    uint8_t *data;
    size_t len;
};

// All cheat_engine_* entry points run either on the emulation thread
// (cheat_engine_apply, from nativeRun) or while emulation is fully paused with the
// GL thread synced (set_table/clear/invalidate/total_memory, called from the IGM
// which pauses the renderer before touching cheats). There is thus no concurrent
// access to this state and no locking; that invariant must hold for new callers.
std::vector<Cheat> g_cheats;
std::vector<Buffer> g_buffers;
uint64_t g_total = 0;
bool g_memory_dirty = true;

void rebuild_buffers() {
    g_buffers.clear();
    g_total = 0;
    const retro_memory_map *map = bridge_get_memory_map();
    if (map) {
        for (unsigned i = 0; i < map->num_descriptors; i++) {
            const retro_memory_descriptor &d = map->descriptors[i];
            if (!(d.flags & RETRO_MEMDESC_SYSTEM_RAM) || !d.ptr || d.len == 0) continue;
            // RetroArch's cheat engine indexes system RAM from d.ptr directly and
            // ignores d.offset; the cheat database addresses are calibrated to that.
            g_buffers.push_back({(uint8_t *)d.ptr, d.len});
            g_total += d.len;
        }
    }
    if (g_buffers.empty()) {
        void *data = bridge_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
        size_t size = bridge_get_memory_size(RETRO_MEMORY_SYSTEM_RAM);
        if (data && size > 0) {
            g_buffers.push_back({(uint8_t *)data, size});
            g_total = size;
        }
    }
    g_memory_dirty = false;
}

uint8_t *translate(uint64_t address) {
    for (auto &b : g_buffers) {
        if (address < b.len) return b.data + address;
        address -= b.len;
    }
    return nullptr;
}

// Returns the value width in bits. RetroArch encodes the same thing as a
// (bytes_per_item, mask) pair; here the byte count is bits/8 and the value mask is
// value_mask(bits), which are equivalent for sizes 3-5 (8/16/32-bit).
unsigned bits_for_size(int search_size) {
    switch (search_size) {
        case 0: return 1;
        case 1: return 2;
        case 2: return 4;
        case 3: return 8;
        case 4: return 16;
        default: return 32;
    }
}

uint32_t value_mask(unsigned bits) {
    return bits >= 32 ? 0xFFFFFFFFu : ((1u << bits) - 1u);
}

unsigned mask_shift(uint32_t mask) {
    if (mask == 0) return 0;
    unsigned s = 0;
    while (!((mask >> s) & 1u)) s++;
    return s;
}

uint32_t read_val(uint64_t address, unsigned bits, bool big_endian, uint32_t addr_mask) {
    if (bits < 8) {
        uint8_t *p = translate(address);
        if (!p) return 0;
        uint32_t mask = addr_mask & 0xFFu;
        return (uint32_t)((*p & mask) >> mask_shift(mask));
    }
    unsigned bytes = bits / 8;
    uint32_t out = 0;
    for (unsigned i = 0; i < bytes; i++) {
        uint8_t *p = translate((address + i) % g_total);
        if (!p) return 0;
        unsigned shift = big_endian ? (bytes - 1 - i) * 8 : i * 8;
        out |= ((uint32_t)*p) << shift;
    }
    return out;
}

void write_val(uint64_t address, unsigned bits, bool big_endian, uint32_t addr_mask, uint32_t value) {
    if (bits < 8) {
        uint8_t *p = translate(address);
        if (!p) return;
        uint32_t mask = addr_mask & 0xFFu;
        *p = (uint8_t)((*p & ~mask) | ((value << mask_shift(mask)) & mask));
        return;
    }
    unsigned bytes = bits / 8;
    for (unsigned i = 0; i < bytes; i++) {
        uint8_t *p = translate((address + i) % g_total);
        if (!p) return;
        unsigned shift = big_endian ? (bytes - 1 - i) * 8 : i * 8;
        *p = (uint8_t)((value >> shift) & 0xFFu);
    }
}

}  // namespace

extern "C" {

void cheat_engine_set_table(const int64_t *table, size_t cheat_count) {
    g_cheats.clear();
    g_cheats.reserve(cheat_count);
    for (size_t i = 0; i < cheat_count; i++) {
        const int64_t *r = table + i * STRIDE;
        Cheat c;
        c.enabled = r[0] != 0;
        c.retro_handler = r[1] == 1;
        c.address = (uint64_t)r[2];
        c.address_mask = (uint32_t)r[3];
        c.value = (uint32_t)r[4];
        c.type = (int)r[5];
        c.search_size = (int)r[6];
        c.big_endian = r[7] != 0;
        c.repeat_count = r[8] > 0 ? (uint32_t)r[8] : 1u;
        c.repeat_add_to_value = (uint32_t)r[9];
        c.repeat_add_to_address = (uint32_t)r[10];
        g_cheats.push_back(c);
    }
}

void cheat_engine_clear(void) {
    g_cheats.clear();
}

void cheat_engine_invalidate_memory(void) {
    g_memory_dirty = true;
}

uint64_t cheat_engine_total_memory(void) {
    rebuild_buffers();
    return g_total;
}

void cheat_engine_apply(void) {
    if (g_cheats.empty()) return;
    if (g_memory_dirty) rebuild_buffers();
    if (g_total == 0) return;

    bool run_cheat = true;
    for (auto &c : g_cheats) {
        // RetroArch applies the handler/enabled filter BEFORE the run-next skip
        // guard, so a failed run-next condition skips the next enabled RETRO cheat,
        // stepping over disabled and emulator-handled entries without consuming the
        // skip. Preserve that order (cheat_manager_apply_retro_cheats).
        if (!c.enabled || !c.retro_handler) continue;

        if (!run_cheat) {
            run_cheat = true;
            continue;
        }

        unsigned bits = bits_for_size(c.search_size);
        uint32_t vmask = value_mask(bits);
        uint64_t address = c.address % g_total;
        uint32_t value = c.value;

        switch (c.type) {
            case TYPE_DISABLED:
                break;
            case TYPE_RUN_NEXT_IF_EQ:
                run_cheat = read_val(address, bits, c.big_endian, c.address_mask) == value;
                break;
            case TYPE_RUN_NEXT_IF_NEQ:
                run_cheat = read_val(address, bits, c.big_endian, c.address_mask) != value;
                break;
            case TYPE_RUN_NEXT_IF_LT:
                run_cheat = value < read_val(address, bits, c.big_endian, c.address_mask);
                break;
            case TYPE_RUN_NEXT_IF_GT:
                run_cheat = value > read_val(address, bits, c.big_endian, c.address_mask);
                break;
            default: {
                unsigned bytes_per_item = bits >= 8 ? bits / 8 : 1;
                uint32_t written;
                switch (c.type) {
                    case TYPE_INCREASE_VALUE:
                        written = read_val(address, bits, c.big_endian, c.address_mask) + value;
                        break;
                    case TYPE_DECREASE_VALUE:
                        written = read_val(address, bits, c.big_endian, c.address_mask) - value;
                        break;
                    default:  // TYPE_SET_TO_VALUE
                        written = value;
                        break;
                }
                // Addresses wrap modulo total memory, so iterating more than the
                // memory size can never poke anything new; clamp there so a malformed
                // .cht with a huge repeat_count cannot stall the frame.
                uint64_t reps = c.repeat_count;
                if (reps > g_total) reps = g_total;
                for (uint64_t r = 0; r < reps; r++) {
                    write_val(address, bits, c.big_endian, c.address_mask, written);
                    written += c.repeat_add_to_value;
                    if (vmask != 0) written %= vmask;
                    address = (address + (uint64_t)c.repeat_add_to_address * bytes_per_item) % g_total;
                }
                break;
            }
        }
    }
}

}  // extern "C"
