/*
 * Desktop smoke test for the emulator wrapper used by the Android app.
 * Build and run with tests/run_host_test.sh.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "emulator.h"

static const char *boot_rom_dir;
static int failures;

#define CHECK(cond, ...) do { \
    if (!(cond)) { failures++; printf("FAIL: " __VA_ARGS__); printf("\n"); } \
    else { printf("ok:   " __VA_ARGS__); printf("\n"); } \
} while (0)

static uint8_t *read_file(const char *path, size_t *size)
{
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long length = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t *data = malloc(length);
    if (fread(data, 1, length, f) != (size_t)length) {
        free(data);
        data = NULL;
    }
    fclose(f);
    *size = (size_t)length;
    return data;
}

static bool file_boot_rom_provider(const char *name, uint8_t **data, size_t *size)
{
    char path[1024];
    snprintf(path, sizeof(path), "%s/%s_boot.bin", boot_rom_dir, name);
    *data = read_file(path, size);
    return *data != NULL;
}

static void log_sink(const char *message)
{
    printf("[core] %s", message);
    if (message[0] && message[strlen(message) - 1] != '\n') printf("\n");
}

static size_t run_frames(unsigned count)
{
    static int16_t audio[EMU_AUDIO_CAPACITY];
    size_t samples = 0;
    for (unsigned i = 0; i < count; i++) {
        emu_run_frame();
        samples += emu_take_audio(audio, EMU_AUDIO_CAPACITY);
    }
    return samples;
}

static bool frame_is_opaque(void)
{
    unsigned width, height;
    const uint32_t *frame = emu_get_frame(&width, &height);
    for (unsigned i = 0; i < width * height; i++) {
        if ((frame[i] >> 24) != 0xFF) return false;
    }
    return true;
}

static void test_model(const uint8_t *rom, size_t rom_size, GB_model_t model, const char *name)
{
    printf("--- %s ---\n", name);
    CHECK(emu_load_rom(rom, rom_size, model, 48000), "%s: ROM loads", name);
    CHECK(emu_get_model() == model, "%s: model is set", name);

    /* Let the boot ROM animation finish and the test program start. */
    size_t samples = run_frames(600);
    double per_frame = samples / 2.0 / 600;
    CHECK(per_frame > 780 && per_frame < 830, "%s: ~804 stereo samples per frame at 48 kHz (got %.1f)", name, per_frame);

    unsigned width, height;
    emu_get_frame(&width, &height);
    CHECK(width == 160 && height == 144, "%s: frame is 160x144 (got %ux%u)", name, width, height);
    CHECK(frame_is_opaque(), "%s: frame pixels are opaque", name);

    /* Frame blending relies on the odd/even flag alternating. SameBoy doesn't track it for the
       Super Game Boy, whose frames always blend as even ones. */
    if (model != GB_MODEL_SGB && model != GB_MODEL_SGB2) {
        unsigned odd = 0;
        for (unsigned i = 0; i < 10; i++) {
            emu_run_frame();
            odd += emu_is_odd_frame();
        }
        CHECK(odd > 0 && odd < 10, "%s: odd/even frame flag alternates (%u of 10 odd)", name, odd);
    }

    CHECK(emu_take_battery_dirty(), "%s: battery marked dirty", name);
    size_t battery_size = emu_battery_size();
    uint8_t *battery = malloc(battery_size);
    CHECK(emu_save_battery(battery, battery_size) == battery_size && battery[0] == 0x42,
          "%s: battery contains marker (size %zu)", name, battery_size);
    CHECK(battery[1] == 0xFF, "%s: no keys pressed (joypad byte 0x%02X)", name, battery[1]);

    emu_set_keys((1 << GB_KEY_A) | (1 << GB_KEY_START));
    run_frames(5);
    emu_save_battery(battery, battery_size);
    /* Active low: buttons nibble has A (bit 0) and Start (bit 3) cleared. */
    CHECK(battery[1] == 0xF6, "%s: A+Start seen by the game (joypad byte 0x%02X)", name, battery[1]);
    emu_set_keys(0);
    run_frames(5);

    size_t state_size = emu_state_size();
    uint8_t *state = malloc(state_size);
    emu_save_state(state);
    emu_set_keys(1 << GB_KEY_B);
    run_frames(5);
    CHECK(emu_load_state(state, state_size), "%s: save state loads", name);
    emu_set_keys(0);
    emu_save_battery(battery, battery_size);
    CHECK(battery[1] == 0xFF, "%s: state restored RAM (joypad byte 0x%02X)", name, battery[1]);

    free(state);
    free(battery);
}

int main(int argc, char **argv)
{
    if (argc < 3) {
        fprintf(stderr, "Usage: %s <rom> <boot rom dir>\n", argv[0]);
        return 2;
    }
    boot_rom_dir = argv[2];
    emu_set_boot_rom_provider(file_boot_rom_provider);
    emu_set_log_sink(log_sink);

    size_t rom_size;
    uint8_t *rom = read_file(argv[1], &rom_size);
    if (!rom) {
        fprintf(stderr, "Could not read %s\n", argv[1]);
        return 2;
    }

    CHECK(emu_pick_model(rom, rom_size, GB_MODEL_DMG_B, GB_MODEL_CGB_E) == GB_MODEL_DMG_B, "DMG-only ROM picks the DMG model");

    test_model(rom, rom_size, GB_MODEL_DMG_B, "DMG");
    test_model(rom, rom_size, GB_MODEL_MGB, "MGB");
    test_model(rom, rom_size, GB_MODEL_CGB_E, "CGB");
    test_model(rom, rom_size, GB_MODEL_AGB_A, "AGB");
    test_model(rom, rom_size, GB_MODEL_SGB2, "SGB2");

    /* SGB border mode grows the frame. */
    emu_set_border_mode(GB_BORDER_ALWAYS);
    emu_load_rom(rom, rom_size, GB_MODEL_SGB, 48000);
    run_frames(300);
    unsigned width, height;
    emu_get_frame(&width, &height);
    CHECK(width == 256 && height == 224, "border mode grows frame to 256x224 (got %ux%u)", width, height);
    emu_set_border_mode(GB_BORDER_NEVER);

    /* Loading a DMG state while running as CGB switches the model. */
    emu_load_rom(rom, rom_size, GB_MODEL_DMG_B, 48000);
    run_frames(10);
    size_t state_size = emu_state_size();
    uint8_t *state = malloc(state_size);
    emu_save_state(state);
    emu_switch_model(GB_MODEL_CGB_E);
    CHECK(emu_load_state(state, state_size) && emu_get_model() == GB_MODEL_DMG_B, "loading a DMG state switches back to DMG");
    free(state);

    /* Rewind. The test ROM counts frames in cartridge RAM, so the RAM shows how far back in the
       recorded history the emulator is. */
    emu_set_rewind_length(10);
    emu_load_rom(rom, rom_size, GB_MODEL_CGB_E, 48000);
    run_frames(840);
    uint8_t ram[0x2000];
    #define FRAME_COUNT() (emu_save_battery(ram, sizeof(ram)), ram[2] | (ram[3] << 8))
    unsigned now = FRAME_COUNT();
    CHECK(now > 600, "rewind: the test ROM counts frames (%u)", now);
    bool moved = true;
    for (unsigned i = 0; i < 60 && moved; i++) moved = emu_rewind_frame();
    unsigned back = FRAME_COUNT();
    /* Each step goes back one frame, except that the first lands on the frame already shown,
       as in SameBoy's own frontend. */
    CHECK(moved && now - back == 59, "rewind: 60 steps go back 59 frames (%u -> %u)", now, back);
    for (unsigned i = 0; i < 120 && moved; i++) moved = emu_rewind_frame();
    CHECK(moved && now - FRAME_COUNT() == 179, "rewind: 180 steps go back 179 frames (%u -> %u)", now, FRAME_COUNT());
    unsigned steps = 0;
    while (emu_rewind_frame() && steps < 100000) steps++;
    /* 10 seconds were asked for; SameBoy keeps the history in blocks of frames, so a little of
       it may already have been dropped. */
    unsigned oldest = FRAME_COUNT();
    CHECK(steps > 300 && now - oldest >= 540 && now - oldest <= 620,
          "rewind: stops at the oldest recorded frame, about 10 seconds back (%u frames back)", now - oldest);
    CHECK(!emu_rewind_frame(), "rewind: nothing older after that");
    run_frames(3);
    CHECK(FRAME_COUNT() == oldest + 3, "rewind: playing resumes from there (%u)", FRAME_COUNT());
    moved = emu_rewind_frame() && emu_rewind_frame();
    CHECK(moved && FRAME_COUNT() == oldest + 2, "rewind: new play is recorded again (%u)", FRAME_COUNT());
    #undef FRAME_COUNT

    emu_set_rewind_length(0);
    run_frames(10);
    CHECK(!emu_rewind_frame(), "rewind: off records nothing");

    emu_unload();
    free(rom);
    printf(failures ? "\n%d check(s) failed\n" : "\nAll checks passed\n", failures);
    return failures != 0;
}
