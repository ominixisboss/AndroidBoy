#include "emulator.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static GB_gameboy_t *gb;
static bool loaded;

/* Link cable: the partner Game Boy and its own screen. */
static GB_gameboy_t *partner;
static bool linked;
static bool partner_leads;
static bool show_partner;
static int64_t link_offset;
static bool bit_from_gb, bit_from_partner;
static uint32_t partner_render_buffer[EMU_MAX_WIDTH * EMU_MAX_HEIGHT];
static uint32_t partner_frame_buffer[EMU_MAX_WIDTH * EMU_MAX_HEIGHT];
static unsigned partner_width = 160, partner_height = 144;
static bool partner_vblank_occurred;
static bool partner_frame_odd;

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

static void sample_callback(GB_gameboy_t *source, GB_sample_t *sample)
{
    /* While linked, only the Game Boy on screen is heard. */
    if (linked && (source == partner) != show_partner) return;
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

static void partner_vblank_callback(GB_gameboy_t *unused, GB_vblank_type_t type)
{
    (void)unused;
    if (type != GB_VBLANK_TYPE_REPEAT) {
        memcpy(partner_frame_buffer, partner_render_buffer, sizeof(partner_render_buffer[0]) * partner_width * partner_height);
        partner_frame_odd = GB_is_odd_frame(partner);
    }
    partner_vblank_occurred = true;
}

/* The cable: a bit shifted out of one Game Boy is shifted into the other (as in SameBoy's own frontends). */
static void gb_bit_start(GB_gameboy_t *unused, bool bit)
{
    (void)unused;
    bit_from_gb = bit;
}

static bool gb_bit_end(GB_gameboy_t *unused)
{
    (void)unused;
    bool received = GB_serial_get_data_bit(partner);
    GB_serial_set_data_bit(partner, bit_from_gb);
    return received;
}

static void partner_bit_start(GB_gameboy_t *unused, bool bit)
{
    (void)unused;
    bit_from_partner = bit;
}

static bool partner_bit_end(GB_gameboy_t *unused)
{
    (void)unused;
    bool received = GB_serial_get_data_bit(gb);
    GB_serial_set_data_bit(gb, bit_from_partner);
    return received;
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

/* The Game Boy Printer: strips printed so far, joined into one printout. */
#define PRINTOUT_MAX_ROWS 4096
#define PRINTOUT_IDLE_FRAMES 120
static int link_accessory;
static uint32_t *printout;
static unsigned printout_rows;
static bool printout_fed;
static unsigned frames_since_print;

static void printout_add_rows(const uint32_t *rows, unsigned count)
{
    if (printout_rows + count > PRINTOUT_MAX_ROWS) count = PRINTOUT_MAX_ROWS - printout_rows;
    if (count == 0) return;
    uint32_t *grown = realloc(printout, (size_t)(printout_rows + count) * EMU_PRINTOUT_WIDTH * sizeof(uint32_t));
    if (!grown) return;
    printout = grown;
    if (rows) {
        memcpy(printout + (size_t)printout_rows * EMU_PRINTOUT_WIDTH, rows, (size_t)count * EMU_PRINTOUT_WIDTH * sizeof(uint32_t));
    } else {
        /* Blank paper. */
        uint32_t white = rgb_encode(gb, 0xFF, 0xFF, 0xFF);
        for (size_t i = 0; i < (size_t)count * EMU_PRINTOUT_WIDTH; i++) printout[(size_t)printout_rows * EMU_PRINTOUT_WIDTH + i] = white;
    }
    printout_rows += count;
}

static void print_image(GB_gameboy_t *g, uint32_t *image, uint8_t height, uint8_t top_margin,
                        uint8_t bottom_margin, uint8_t exposure)
{
    (void)g;
    (void)exposure;
    /* Margins are paper feeds; show a little space for them between strips, not before the first. */
    if (printout_rows > 0) printout_add_rows(NULL, top_margin * 4u);
    printout_add_rows(image, height);
    if (bottom_margin > 0) printout_fed = true;
    frames_since_print = 0;
}

static void apply_link_accessory(void)
{
    if (!gb || !GB_is_inited(gb) || linked) return;
    if (link_accessory == EMU_LINK_PRINTER) {
        GB_connect_printer(gb, print_image, NULL);
    } else {
        GB_disconnect_serial(gb);
    }
}

void emu_set_link_accessory(int accessory)
{
    link_accessory = accessory;
    apply_link_accessory();
}

unsigned emu_take_printout(uint32_t **pixels)
{
    *pixels = NULL;
    if (printout_rows == 0 || (!printout_fed && frames_since_print < PRINTOUT_IDLE_FRAMES)) return 0;
    unsigned rows = printout_rows;
    *pixels = printout;
    printout = NULL;
    printout_rows = 0;
    printout_fed = false;
    return rows;
}

static uint8_t camera_image[EMU_CAMERA_WIDTH * EMU_CAMERA_HEIGHT];
static bool camera_has_image;
static bool cartridge_has_camera;

static uint8_t camera_get_pixel(GB_gameboy_t *g, uint8_t x, uint8_t y)
{
    (void)g;
    if (x >= EMU_CAMERA_WIDTH || y >= EMU_CAMERA_HEIGHT) return 0;
    return camera_image[y * EMU_CAMERA_WIDTH + x];
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
    emu_unlink();
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
    if (camera_has_image) GB_set_camera_get_pixel_callback(gb, camera_get_pixel);
    apply_link_accessory();
    cartridge_has_camera = size > 0x147 && rom[0x147] == 0xFC; /* Pocket Camera */

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
    emu_unlink();
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
    if (linked) {
        /* Run whichever Game Boy is behind, so neither gets more than a few cycles ahead; the
           order depends only on which game leads, never on which one is shown here. */
        GB_gameboy_t *first = partner_leads ? partner : gb;
        GB_gameboy_t *second = partner_leads ? gb : partner;
        bool *frame_done = partner_leads ? &partner_vblank_occurred : &vblank_occurred;
        partner_vblank_occurred = false;
        for (unsigned i = 0; !*frame_done && i < 4000000; i++) {
            if (link_offset <= 0) {
                link_offset += GB_run(first);
            } else {
                link_offset -= GB_run(second);
            }
        }
        if (frames_since_print < PRINTOUT_IDLE_FRAMES) frames_since_print++;
        return;
    }
    /* Bound the loop so a pathological ROM can never hang the emulation thread. */
    for (unsigned i = 0; !vblank_occurred && i < 1000000; i++) {
        GB_run(gb);
    }
    if (frames_since_print < PRINTOUT_IDLE_FRAMES) frames_since_print++;
}

void emu_set_rewind_length(unsigned seconds)
{
    rewind_seconds = seconds;
    if (loaded && !linked) GB_set_rewind_length(gb, seconds);
}

bool emu_rewind_frame(void)
{
    if (!loaded || linked) return false;
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
    if (linked && show_partner) {
        *width = partner_width;
        *height = partner_height;
        return partner_frame_buffer;
    }
    *width = frame_width;
    *height = frame_height;
    return frame_buffer;
}

bool emu_is_odd_frame(void)
{
    return linked && show_partner ? partner_frame_odd : frame_odd;
}

bool emu_link(const uint8_t *rom, size_t size, GB_model_t model, bool leads, uint64_t seed)
{
    if (!loaded || size < 0x150) return false;
    emu_unlink();
    if (!partner) {
        partner = GB_alloc();
        if (!partner) return false;
    }
    GB_init(partner, model);
    GB_set_log_callback(partner, log_callback);
    GB_set_boot_rom_load_callback(partner, boot_rom_load_callback);
    GB_set_pixels_output(partner, partner_render_buffer);
    GB_set_rgb_encode_callback(partner, rgb_encode);
    GB_set_vblank_callback(partner, partner_vblank_callback);
    GB_apu_set_sample_callback(partner, sample_callback);
    GB_set_sample_rate(partner, sample_rate);
    GB_load_rom_from_buffer(partner, rom, size);
    GB_set_color_correction_mode(partner, color_correction);
    static const GB_palette_t *const palettes[] = {&GB_PALETTE_GREY, &GB_PALETTE_DMG, &GB_PALETTE_MGB, &GB_PALETTE_GBL};
    GB_set_palette(partner, palettes[dmg_palette < 4 ? dmg_palette : 0]);
    GB_set_border_mode(partner, border_mode);
    GB_set_highpass_filter_mode(partner, highpass);

    /* Nothing else on the cable, and no rewinding: it would unlink the two in time. */
    GB_disconnect_serial(gb);
    GB_set_rewind_length(gb, 0);
    GB_set_serial_transfer_bit_start_callback(gb, gb_bit_start);
    GB_set_serial_transfer_bit_end_callback(gb, gb_bit_end);
    GB_set_serial_transfer_bit_start_callback(partner, partner_bit_start);
    GB_set_serial_transfer_bit_end_callback(partner, partner_bit_end);

    /* The clock runs on emulated time, not the phone's, so both devices agree on it. */
    GB_set_rtc_mode(gb, GB_RTC_MODE_ACCURATE);
    GB_set_rtc_mode(partner, GB_RTC_MODE_ACCURATE);

    /* Power both on from the same random state, leading game first. */
    partner_leads = leads;
    GB_random_seed(seed);
    GB_reset(leads ? partner : gb);
    GB_reset(leads ? gb : partner);
    partner_width = GB_get_screen_width(partner);
    partner_height = GB_get_screen_height(partner);
    memset(partner_frame_buffer, 0, sizeof(partner_frame_buffer));
    update_frame_size();
    link_offset = 0;
    bit_from_gb = bit_from_partner = false;
    show_partner = false;
    audio_count = 0;
    linked = true;
    return true;
}

void emu_unlink(void)
{
    if (!linked) return;
    linked = false;
    show_partner = false;
    GB_set_serial_transfer_bit_start_callback(gb, NULL);
    GB_set_serial_transfer_bit_end_callback(gb, NULL);
    if (partner && GB_is_inited(partner)) GB_free(partner);
    GB_set_rtc_mode(gb, GB_RTC_MODE_SYNC_TO_HOST);
    GB_set_rewind_length(gb, rewind_seconds);
    apply_link_accessory();
}

bool emu_is_linked(void)
{
    return linked;
}

void emu_set_partner_keys(unsigned mask)
{
    if (linked) GB_set_key_mask(partner, (GB_key_mask_t)mask);
}

void emu_show_partner(bool show)
{
    if (show_partner != show) audio_count = 0;
    show_partner = linked && show;
}

size_t emu_partner_battery_size(void)
{
    if (!linked) return 0;
    int size = GB_save_battery_size(partner);
    return size > 0 ? (size_t)size : 0;
}

size_t emu_partner_save_battery(uint8_t *buffer, size_t size)
{
    size_t needed = emu_partner_battery_size();
    if (!needed || size < needed) return 0;
    return GB_save_battery_to_buffer(partner, buffer, needed) == 0 ? needed : 0;
}

void emu_partner_load_battery(const uint8_t *buffer, size_t size)
{
    if (linked) GB_load_battery_from_buffer(partner, buffer, size);
}

size_t emu_partner_state_size(void)
{
    return linked ? GB_get_save_state_size(partner) : 0;
}

void emu_partner_save_state(uint8_t *buffer)
{
    if (linked) GB_save_state_to_buffer(partner, buffer);
}

bool emu_partner_load_state(const uint8_t *buffer, size_t size)
{
    if (!linked) return false;
    bool success = GB_load_state_from_buffer(partner, buffer, size) == 0;
    partner_width = GB_get_screen_width(partner);
    partner_height = GB_get_screen_height(partner);
    return success;
}

bool emu_partner_take_battery_dirty(void)
{
    if (!linked || !GB_get_battery_dirty(partner)) return false;
    GB_clear_battery_dirty(partner);
    return true;
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

/* A 6-digit Game Genie code (VVA-AAA), which replaces a value whatever it was. */
static bool is_short_game_genie(const char *code)
{
    if (strlen(code) == 8) return false; /* GameShark */
    unsigned digits = 0;
    for (; *code; code++) {
        if (*code != '-') digits++;
    }
    return digits == 6;
}

bool emu_has_camera(void)
{
    return loaded && cartridge_has_camera;
}

void emu_set_camera_image(const uint8_t *pixels)
{
    if (pixels) {
        memcpy(camera_image, pixels, sizeof(camera_image));
        camera_has_image = true;
    } else {
        camera_has_image = false;
    }
    if (gb && GB_is_inited(gb)) GB_set_camera_get_pixel_callback(gb, camera_has_image ? camera_get_pixel : NULL);
}

unsigned emu_set_cheats(const char *codes)
{
    if (!loaded) return 0;
    GB_remove_all_cheats(gb);
    unsigned count = 0;
    while (codes && *codes) {
        const char *end = strchr(codes, '\n');
        size_t length = end ? (size_t)(end - codes) : strlen(codes);
        char code[32];
        if (length > 0 && length < sizeof(code)) {
            memcpy(code, codes, length);
            code[length] = 0;
            const GB_cheat_t *cheat = GB_import_cheat(gb, code, "", true);
            if (cheat && is_short_game_genie(code)) {
                /* SameBoy 1.0.3 imports these as "only when the original value is 0"; they have no
                 * original value, so they should always apply. */
                GB_update_cheat(gb, cheat, cheat->description, cheat->address, cheat->bank, cheat->value, 0, false,
                                true);
            }
            if (cheat) count++;
        }
        codes += length + (end ? 1 : 0);
    }
    GB_set_cheats_enabled(gb, count > 0);
    return count;
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
