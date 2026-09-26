/*
 * The Game Boy Advance backend, on mGBA (third_party/mgba). emulator.c hands every call to it
 * while a GBA game is loaded, so the rest of the app drives both systems through emulator.h.
 * All functions must be called from a single thread.
 */
#ifndef ANDROIDBOY_GBA_CORE_H
#define ANDROIDBOY_GBA_CORE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define GBA_WIDTH 240
#define GBA_HEIGHT 160

/* Whether `rom` is a Game Boy Advance cartridge (by its header). */
bool gba_is_rom(const uint8_t *rom, size_t size);

/* Loads a GBA game, copying the ROM. `log` gets mGBA's warnings and errors. */
bool gba_load(const uint8_t *rom, size_t size, unsigned sample_rate, void (*log)(const char *message));
void gba_unload(void);
void gba_reset(void);

/* Keys in emulator.h's layout: the Game Boy's eight (1 << GB_KEY_*) plus EMU_KEY_L and EMU_KEY_R. */
void gba_set_keys(unsigned mask);
void gba_run_frame(void);

void gba_set_rewind_length(unsigned seconds);
bool gba_rewind_frame(void);

/* The last frame, GBA_WIDTH x GBA_HEIGHT, as 0xAABBGGRR pixels. */
const uint32_t *gba_get_frame(void);

size_t gba_take_audio(int16_t *out, size_t max_samples);

size_t gba_battery_size(void);
size_t gba_save_battery(uint8_t *buffer, size_t size);
void gba_load_battery(const uint8_t *buffer, size_t size);
bool gba_take_battery_dirty(void);

size_t gba_state_size(void);
void gba_save_state(uint8_t *buffer);
bool gba_load_state(const uint8_t *buffer, size_t size);

/* RetroAchievements' GBA map: $00000 IWRAM, $08000 EWRAM, $48000 cartridge save RAM. */
uint32_t gba_read_achievement_memory(uint32_t address, uint8_t *buffer, uint32_t num_bytes);

/* GameShark, Action Replay and CodeBreaker codes, one per line; returns how many were accepted. */
unsigned gba_set_cheats(const char *codes);

void gba_get_title(char title[17]);

#endif
