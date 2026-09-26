/*
 * Platform-independent wrapper around the emulator cores: SameBoy for Game Boy and Game Boy
 * Color games, and mGBA (gba_core.c) for Game Boy Advance games. Loading a ROM picks the core
 * from its header, and every call below goes to that core; calls that only make sense on a
 * Game Boy (models, palettes, borders, the link cable, printer and camera) do nothing while a
 * GBA game is loaded.
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

/* Largest possible frame (Super Game Boy border enabled; a GBA screen is 240x160). */
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

/*
 * (Re)initializes the emulator with a ROM: a Game Boy Advance ROM runs on mGBA (`model` is then
 * ignored), anything else on SameBoy. Returns false on failure.
 */
bool emu_load_rom(const uint8_t *rom, size_t size, GB_model_t model, unsigned sample_rate);

#define EMU_SYSTEM_GB  0
#define EMU_SYSTEM_GBA 1

/* Which system the loaded game is for. */
int emu_get_system(void);
bool emu_is_loaded(void);
void emu_unload(void);

void emu_reset(void);
void emu_switch_model(GB_model_t model);
GB_model_t emu_get_model(void);

/* The Game Boy Advance's shoulder buttons, after the Game Boy's eight keys. */
#define EMU_KEY_L (1 << 8)
#define EMU_KEY_R (1 << 9)

/* Bitmask of (1 << GB_KEY_*), plus EMU_KEY_L and EMU_KEY_R for GBA games. */
void emu_set_keys(unsigned mask);

/* Runs the emulator until the next frame is ready. */
void emu_run_frame(void);

/* How many seconds of gameplay to record for rewinding; 0 turns rewinding off. */
void emu_set_rewind_length(unsigned seconds);

/*
 * Steps one frame back in the rewind history and renders it. Returns false, leaving the
 * emulator where it is, once the oldest recorded frame is reached (or rewinding is off).
 * Audio produced while rewinding is left in the buffer for the caller to discard.
 */
bool emu_rewind_frame(void);

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

/* Save states (BESS-compatible SameBoy format, or mGBA's own for GBA games). */
size_t emu_state_size(void);
void emu_save_state(uint8_t *buffer);
bool emu_load_state(const uint8_t *buffer, size_t size);

/* Settings. Values are the corresponding SameBoy enums. */
void emu_set_color_correction(GB_color_correction_mode_t mode);
void emu_set_dmg_palette(unsigned index); /* 0 grey, 1 DMG green, 2 MGB, 3 GB Light */
void emu_set_border_mode(GB_border_mode_t mode);
void emu_set_highpass(GB_highpass_mode_t mode);
void emu_set_rumble_mode(GB_rumble_mode_t mode);

/*
 * Reads memory for RetroAchievements, in its address map for the system. GBA: see gba_core.h.
 * Game Boy (Color): $0000-$FFFF is the
 * CPU's view (read without side effects), $10000-$15FFF the Color's work RAM banks 2-7, and
 * $16000-$33FFF cartridge RAM banks 1-15. Returns how many bytes were read, stopping at the
 * first address that doesn't exist on this game (e.g. more cartridge RAM than it has).
 */
uint32_t emu_read_achievement_memory(uint32_t address, uint8_t *buffer, uint32_t num_bytes);

/*
 * Replaces the game's cheats with `codes`: GameShark (01VVAAAA) and Game Genie (VVA-AAA or
 * VVA-AAA-OOO) codes for Game Boy games, GameShark, Action Replay and CodeBreaker codes for GBA
 * games, one per line. Returns how many codes were accepted; lines that aren't a
 * valid code are skipped. Loading a ROM clears the cheats; an empty string turns them off.
 */
unsigned emu_set_cheats(const char *codes);

/*
 * Link cable: a second Game Boy (the partner) running another cartridge, connected to the loaded
 * one and run in lockstep with it, cycle by cycle.
 *
 * Both Game Boys are powered on from scratch with RAM randomised from `seed`, in a fixed order,
 * and each emulated frame is one frame of the leading game (`partner_leads` picks which); so
 * two devices that link the same two games with the same seed and feed them the same keys stay
 * exactly in step. That's how two phones play together: each runs both Game Boys and only keys
 * are exchanged.
 *
 * Loading a ROM or emu_unlink() ends the link. Rewinding and cheats should stay off while linked.
 */
bool emu_link(const uint8_t *rom, size_t size, GB_model_t model, bool partner_leads, uint64_t seed);
void emu_unlink(void);
bool emu_is_linked(void);
void emu_set_partner_keys(unsigned mask);
/* Shows (and plays the sound of) the partner instead of the loaded Game Boy. */
void emu_show_partner(bool show);
size_t emu_partner_battery_size(void);
size_t emu_partner_save_battery(uint8_t *buffer, size_t size);
void emu_partner_load_battery(const uint8_t *buffer, size_t size);
bool emu_partner_take_battery_dirty(void);
/* The partner's save state, so a second device can start from exactly this moment. */
size_t emu_partner_state_size(void);
void emu_partner_save_state(uint8_t *buffer);
bool emu_partner_load_state(const uint8_t *buffer, size_t size);

/* What's plugged into the link port. */
#define EMU_LINK_NOTHING 0
#define EMU_LINK_PRINTER 1

/* Plugs something into the link port; kept across loading ROMs. */
void emu_set_link_accessory(int accessory);

/* Game Boy Printer paper is 160 pixels wide. */
#define EMU_PRINTOUT_WIDTH 160

/*
 * A finished printout, as 0xAABBGGRR pixels EMU_PRINTOUT_WIDTH wide: once the printer feeds the
 * paper out, or a couple of seconds after the last strip printed. Returns the number of rows and
 * sets *pixels to a buffer the caller frees, or returns 0 if nothing's ready.
 */
unsigned emu_take_printout(uint32_t **pixels);

/* The Game Boy Camera's sensor size. */
#define EMU_CAMERA_WIDTH 128
#define EMU_CAMERA_HEIGHT 112

/* Whether the loaded cartridge is a Game Boy Camera. */
bool emu_has_camera(void);

/*
 * What the Game Boy Camera sees: EMU_CAMERA_WIDTH x EMU_CAMERA_HEIGHT brightness values (0 dark,
 * 255 bright), copied. NULL goes back to SameBoy's static noise, as with no camera.
 */
void emu_set_camera_image(const uint8_t *pixels);

double emu_get_rumble(void);
void emu_get_title(char title[17]);

#endif
