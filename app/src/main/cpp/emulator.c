#include "emulator.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static GB_gameboy_t *gb;
static bool loaded;

static uint32_t render_buffer[EMU_MAX_WIDTH * EMU_MAX_HEIGHT];
static uint32_t frame_buffer[EMU_MAX_WIDTH * EMU_MAX_HEIGHT];
static unsigned frame_width = 160, frame_height = 144;
static bool vblank_occurred;
static bool frame_odd;

static int16_t audio_buffer[EMU_AUDIO_CAPACITY];
static size_t audio_count;

static double rumble_amplitude;

static emu_boot_rom_provider_t boot_rom_provider;
static emu_log_sink_t log_sink;

/* Settings survive reloading a ROM. */
static GB_color_correction_mode_t color_correction = GB_COLOR_CORRECTION_MODERN_BALANCED;
static unsigned dmg_palette;
static GB_border_mode_t border_mode = GB_BORDER_NEVER;
static GB_highpass_mode_t highpass = GB_HIGHPASS_ACCURATE;
static GB_rumble_mode_t rumble_mode = GB_RUMBLE_CARTRIDGE_ONLY;
static unsigned sample_rate = 48000;
static unsigned rewind_seconds = 30;

void emu_set_boot_rom_provider(emu_boot_rom_provider_t provider)
{
    boot_rom_provider = provider;
}

void emu_set_log_sink(emu_log_sink_t sink)
{
    log_sink = sink;
}

static void emu_log(const char *fmt, ...)
{
    if (!log_sink) return;
    char message[256];
    va_list args;
    va_start(args, fmt);
    vsnprintf(message, sizeof(message), fmt, args);
    va_end(args);
    log_sink(message);
}

static void log_callback(GB_gameboy_t *unused, const char *string, GB_log_attributes_t attributes)
{
    (void)unused;
    (void)attributes;
    if (log_sink) log_sink(string);
}

static uint32_t rgb_encode(GB_gameboy_t *unused, uint8_t r, uint8_t g, uint8_t b)
{
    (void)unused;
    /* Little-endian RGBA byte order, as used by Android's ARGB_8888 bitmaps. */
    return 0xFF000000u | ((uint32_t)b << 16) | ((uint32_t)g << 8) | r;
}

static void vblank_callback(GB_gameboy_t *unused, GB_vblank_type_t type)
{
    (void)unused;
    /* A repeated frame means the screen keeps showing the previous one. */
    if (type != GB_VBLANK_TYPE_REPEAT) {
        memcpy(frame_buffer, render_buffer, sizeof(render_buffer[0]) * frame_width * frame_height);
        frame_odd = GB_is_odd_frame(gb);
    }
    vblank_occurred = true;
}

static void sample_callback(GB_gameboy_t *unused, GB_sample_t *sample)
{
    (void)unused;
    if (audio_count + 2 > EMU_AUDIO_CAPACITY) return; /* Frontend fell behind; drop. */
    audio_buffer[audio_count++] = sample->left;
    audio_buffer[audio_count++] = sample->right;
}

static void rumble_callback(GB_gameboy_t *unused, double amplitude)
{
    (void)unused;
    rumble_amplitude = amplitude;
}

static void boot_rom_load_callback(GB_gameboy_t *target, GB_boot_rom_t type)
{
    static const char *const names[] = {
        [GB_BOOT_ROM_DMG_0] = "dmg", /* DMG0 boot ROM is not part of SameBoy */
        [GB_BOOT_ROM_DMG] = "dmg",
        [GB_BOOT_ROM_MGB] = "mgb",
        [GB_BOOT_ROM_SGB] = "sgb",
        [GB_BOOT_ROM_SGB2] = "sgb2",
        [GB_BOOT_ROM_CGB_0] = "cgb0",
        [GB_BOOT_ROM_CGB] = "cgb",
        [GB_BOOT_ROM_CGB_E] = "cgb", /* SameBoy's CGB boot ROM covers CGB-E */
        [GB_BOOT_ROM_AGB_0] = "agb",
        [GB_BOOT_ROM_AGB] = "agb",
    };
    if ((unsigned)type >= sizeof(names) / sizeof(names[0]) || !names[type]) {
        emu_log("Unknown boot ROM type %d", type);
        return;
    }

    uint8_t *data = NULL;
    size_t size = 0;
    if (!boot_rom_provider || !boot_rom_provider(names[type], &data, &size)) {
        emu_log("Could not load the %s boot ROM", names[type]);
        return;
    }
    GB_load_boot_rom_from_buffer(target, data, size);
    free(data);
}

static void update_frame_size(void)
{
    frame_width = GB_get_screen_width(gb);
    frame_height = GB_get_screen_height(gb);
    memset(frame_buffer, 0, sizeof(frame_buffer));
}

static void apply_palette(void)
{
    static const GB_palette_t *const palettes[] = {
        &GB_PALETTE_GREY, &GB_PALETTE_DMG, &GB_PALETTE_MGB, &GB_PALETTE_GBL,
    };
    GB_set_palette(gb, palettes[dmg_palette < 4 ? dmg_palette : 0]);
}

static void apply_settings(void)
{
    GB_set_color_correction_mode(gb, color_correction);
    apply_palette();
    GB_set_border_mode(gb, border_mode);
    GB_set_highpass_filter_mode(gb, highpass);
    GB_set_rumble_mode(gb, rumble_mode);
    GB_set_rewind_length(gb, rewind_seconds);
    update_frame_size();
}

GB_model_t emu_pick_model(const uint8_t *rom, size_t size, GB_model_t dmg_model, GB_model_t cgb_model)
{
    if (size > 0x143 && (rom[0x143] & 0x80)) {
        /* Game Boy Color enhanced or exclusive. */
        return cgb_model;
    }
    return dmg_model;
}

bool emu_load_rom(const uint8_t *rom, size_t size, GB_model_t model, unsigned rate)
{
    if (size < 0x150) {
        emu_log("ROM is too small (%zu bytes)", size);
        return false;
    }
    if (!gb) {
        gb = GB_alloc();
        if (!gb) return false;
    }
    if (GB_is_inited(gb)) {
        GB_free(gb);
    }
    loaded = false;

    GB_init(gb, model);
    GB_set_log_callback(gb, log_callback);
    GB_set_boot_rom_load_callback(gb, boot_rom_load_callback);
    GB_set_pixels_output(gb, render_buffer);
    GB_set_rgb_encode_callback(gb, rgb_encode);
    GB_set_vblank_callback(gb, vblank_callback);
    GB_apu_set_sample_callback(gb, sample_callback);
    GB_set_rumble_callback(gb, rumble_callback);

    sample_rate = rate ? rate : 48000;
    GB_set_sample_rate(gb, sample_rate);

    GB_load_rom_from_buffer(gb, rom, size);
    apply_settings();
    /* Resetting after the ROM is in place requests the boot ROM and picks up the cartridge's SGB border. */
    GB_reset(gb);
    update_frame_size();

    audio_count = 0;
    rumble_amplitude = 0;
    loaded = true;
    return true;
}

bool emu_is_loaded(void)
{
    return loaded;
}

void emu_unload(void)
{
    if (gb && GB_is_inited(gb)) {
        GB_free(gb);
    }
    loaded = false;
    audio_count = 0;
    rumble_amplitude = 0;
}

void emu_reset(void)
{
    if (!loaded) return;
    GB_reset(gb);
    update_frame_size();
}

void emu_switch_model(GB_model_t model)
{
    if (!loaded) return;
    GB_switch_model_and_reset(gb, model);
    update_frame_size();
}

GB_model_t emu_get_model(void)
{
    return loaded ? GB_get_model(gb) : GB_MODEL_CGB_E;
}

void emu_set_keys(unsigned mask)
{
    if (!loaded) return;
    GB_set_key_mask(gb, (GB_key_mask_t)mask);
}

void emu_run_frame(void)
{
    if (!loaded) return;
    vblank_occurred = false;
    /* Bound the loop so a pathological ROM can never hang the emulation thread. */
    for (unsigned i = 0; !vblank_occurred && i < 1000000; i++) {
        GB_run(gb);
    }
}

void emu_set_rewind_length(unsigned seconds)
{
    rewind_seconds = seconds;
    if (loaded) GB_set_rewind_length(gb, seconds);
}

bool emu_rewind_frame(void)
{
    if (!loaded) return false;
    /* The core records a state at every frame, so the newest one is the frame on screen.
       Drop it, step back to the one before, then run a frame from there to show it (which
       records it again). This is what SameBoy's own frontend does. */
    GB_rewind_pop(gb);
    if (!GB_rewind_pop(gb)) return false;
    emu_run_frame();
    return true;
}

const uint32_t *emu_get_frame(unsigned *width, unsigned *height)
{
    *width = frame_width;
    *height = frame_height;
    return frame_buffer;
}

bool emu_is_odd_frame(void)
{
    return frame_odd;
}

size_t emu_take_audio(int16_t *out, size_t max_samples)
{
    size_t count = audio_count < max_samples ? audio_count : max_samples;
    count &= ~(size_t)1; /* Keep stereo pairs together. */
    memcpy(out, audio_buffer, count * sizeof(int16_t));
    memmove(audio_buffer, audio_buffer + count, (audio_count - count) * sizeof(int16_t));
    audio_count -= count;
    return count;
}

size_t emu_battery_size(void)
{
    if (!loaded) return 0;
    int size = GB_save_battery_size(gb);
    return size > 0 ? (size_t)size : 0;
}

size_t emu_save_battery(uint8_t *buffer, size_t size)
{
    size_t needed = emu_battery_size();
    if (!needed || size < needed) return 0;
    /* Returns 0 on success. */
    return GB_save_battery_to_buffer(gb, buffer, needed) == 0 ? needed : 0;
}

void emu_load_battery(const uint8_t *buffer, size_t size)
{
    if (!loaded) return;
    GB_load_battery_from_buffer(gb, buffer, size);
}

bool emu_take_battery_dirty(void)
{
    if (!loaded || !GB_get_battery_dirty(gb)) return false;
    GB_clear_battery_dirty(gb);
    return true;
}

size_t emu_state_size(void)
{
    return loaded ? GB_get_save_state_size(gb) : 0;
}

void emu_save_state(uint8_t *buffer)
{
    if (!loaded) return;
    GB_save_state_to_buffer(gb, buffer);
}

bool emu_load_state(const uint8_t *buffer, size_t size)
{
    if (!loaded) return false;
    GB_model_t state_model;
    if (GB_get_state_model_from_buffer(buffer, size, &state_model) == 0 && state_model != GB_get_model(gb)) {
        /* States carry their model; switch so e.g. a DMG state can be loaded while on CGB. */
        GB_switch_model_and_reset(gb, state_model);
    }
    bool success = GB_load_state_from_buffer(gb, buffer, size) == 0;
    update_frame_size();
    return success;
}

void emu_set_color_correction(GB_color_correction_mode_t mode)
{
    color_correction = mode;
    if (loaded) GB_set_color_correction_mode(gb, mode);
}

void emu_set_dmg_palette(unsigned index)
{
    dmg_palette = index;
    if (loaded) apply_palette();
}

void emu_set_border_mode(GB_border_mode_t mode)
{
    border_mode = mode;
    if (loaded) {
        GB_set_border_mode(gb, mode);
        update_frame_size();
    }
}

void emu_set_highpass(GB_highpass_mode_t mode)
{
    highpass = mode;
    if (loaded) GB_set_highpass_filter_mode(gb, mode);
}

void emu_set_rumble_mode(GB_rumble_mode_t mode)
{
    rumble_mode = mode;
    if (loaded) GB_set_rumble_mode(gb, mode);
}

/* One byte from a region of core memory, `offset` bytes in; false if it's past the end. */
static bool read_direct(GB_direct_access_t region, uint32_t offset, uint8_t *out)
{
    size_t size = 0;
    uint16_t bank;
    const uint8_t *data = GB_get_direct_access(gb, region, &size, &bank);
    if (!data || offset >= size) return false;
    *out = data[offset];
    return true;
}

uint32_t emu_read_achievement_memory(uint32_t address, uint8_t *buffer, uint32_t num_bytes)
{
    if (!gb || !loaded) return 0;
    for (uint32_t i = 0; i < num_bytes; i++) {
        uint32_t a = address + i;
        if (a < 0x10000) {
            buffer[i] = GB_safe_read_memory(gb, (uint16_t)a);
        }
        else if (a < 0x16000) {
            /* Banks 2-7 of the Color's work RAM; banks 0 and 1 are the first 0x2000 bytes. */
            if (!GB_is_cgb(gb) || !read_direct(GB_DIRECT_ACCESS_RAM, 0x2000 + (a - 0x10000), &buffer[i])) return i;
        }
        else if (a < 0x34000) {
            /* Cartridge RAM banks 1-15; bank 0 is the first 0x2000 bytes. */
            if (!read_direct(GB_DIRECT_ACCESS_CART_RAM, 0x2000 + (a - 0x16000), &buffer[i])) return i;
        }
        else {
            return i;
        }
    }
    return num_bytes;
}

double emu_get_rumble(void)
{
    return rumble_amplitude;
}

void emu_get_title(char title[17])
{
    if (!loaded) {
        title[0] = 0;
        return;
    }
    GB_get_rom_title(gb, title);
}
