/*
 * Platform-independent wrapper around the SameBoy core.
 *
 * The JNI bridge (jni_bridge.c) is a thin layer over this API, which keeps
 * everything in here testable on a desktop host (see tests/host_test.c).
 * All functions must be called from a single thread.
 */
#ifndef ANDROIDBOY_EMULATOR_H
#define ANDROIDBOY_EMULATOR_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "Core/gb.h"

/* Largest possible frame (Super Game Boy border enabled). */
#define EMU_MAX_WIDTH  256
#define EMU_MAX_HEIGHT 224

/* Enough room for several frames of stereo audio at 48 kHz. */
#define EMU_AUDIO_CAPACITY 16384

/*
 * Provides boot ROM images. `name` is one of "dmg", "mgb", "sgb", "sgb2",
 * "cgb0", "cgb", "agb". On success, fills `data`/`size` with a buffer the
 * caller of the provider will free() and returns true.
 */
typedef bool (*emu_boot_rom_provider_t)(const char *name, uint8_t **data, size_t *size);
typedef void (*emu_log_sink_t)(const char *message);

void emu_set_boot_rom_provider(emu_boot_rom_provider_t provider);
void emu_set_log_sink(emu_log_sink_t sink);

/* Returns the model a ROM should run on, given the user's model preferences. */
GB_model_t emu_pick_model(const uint8_t *rom, size_t size, GB_model_t dmg_model, GB_model_t cgb_model);

/* (Re)initializes the emulator with a ROM. Returns false on failure. */
bool emu_load_rom(const uint8_t *rom, size_t size, GB_model_t model, unsigned sample_rate);
bool emu_is_loaded(void);
void emu_unload(void);

void emu_reset(void);
void emu_switch_model(GB_model_t model);
GB_model_t emu_get_model(void);

/* Bitmask of (1 << GB_KEY_*). */
void emu_set_keys(unsigned mask);

/* Runs the emulator until the next frame is ready. */
void emu_run_frame(void);

/* The most recently completed frame, as 0xAABBGGRR pixels (Android ARGB_8888 memory order). */
const uint32_t *emu_get_frame(unsigned *width, unsigned *height);

/* Whether the most recent frame is an odd one, for SameBoy's accurate frame blending. */
bool emu_is_odd_frame(void);

/* Moves up to `max_samples` interleaved stereo int16 samples into `out`. Returns the count moved. */
size_t emu_take_audio(int16_t *out, size_t max_samples);

/* Battery-backed cartridge RAM (and RTC). */
size_t emu_battery_size(void);
size_t emu_save_battery(uint8_t *buffer, size_t size);
void emu_load_battery(const uint8_t *buffer, size_t size);
bool emu_take_battery_dirty(void);

/* Save states (BESS-compatible SameBoy format). */
size_t emu_state_size(void);
void emu_save_state(uint8_t *buffer);
bool emu_load_state(const uint8_t *buffer, size_t size);

/* Settings. Values are the corresponding SameBoy enums. */
void emu_set_color_correction(GB_color_correction_mode_t mode);
void emu_set_dmg_palette(unsigned index); /* 0 grey, 1 DMG green, 2 MGB, 3 GB Light */
void emu_set_border_mode(GB_border_mode_t mode);
void emu_set_highpass(GB_highpass_mode_t mode);
void emu_set_rumble_mode(GB_rumble_mode_t mode);

double emu_get_rumble(void);
void emu_get_title(char title[17]);

#endif
