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

/* The pixel at (x, y) of the last frame, as red, green and blue. */
static void pixel_at(unsigned x, unsigned y, unsigned *r, unsigned *g, unsigned *b)
{
    unsigned width, height;
    const uint32_t *frame = emu_get_frame(&width, &height);
    uint32_t p = frame[y * width + x];
    *r = p & 0xFF;
    *g = (p >> 8) & 0xFF;
    *b = (p >> 16) & 0xFF;
}

/* Frames counted by tests/gbarom.gba, from cartridge SRAM bytes 2-3. */
static unsigned gba_frame_count(void)
{
    uint8_t save[0x8000];
    if (emu_save_battery(save, sizeof(save)) < 4) return 0;
    return save[2] | (save[3] << 8);
}

static void test_gba(const char *path, const uint8_t *gb_rom, size_t gb_rom_size)
{
    size_t size;
    uint8_t *rom = read_file(path, &size);
    CHECK(rom != NULL, "GBA: test ROM readable");
    if (!rom) return;

    CHECK(emu_load_rom(rom, size, GB_MODEL_CGB_E, 48000), "GBA: ROM loads");
    CHECK(emu_get_system() == EMU_SYSTEM_GBA && emu_get_model() == GB_MODEL_AGB_A, "GBA: runs as a Game Boy Advance");
    char title[17];
    emu_get_title(title);
    CHECK(strcmp(title, "ANDROIDBOY") == 0, "GBA: title read from the header (\"%s\")", title);

    size_t samples = run_frames(120);
    double per_frame = samples / 2.0 / 120;
    CHECK(per_frame > 780 && per_frame < 830, "GBA: ~804 stereo samples per frame at 48 kHz (got %.1f)", per_frame);
    unsigned width, height;
    emu_get_frame(&width, &height);
    CHECK(width == 240 && height == 160, "GBA: frame is 240x160 (got %ux%u)", width, height);
    CHECK(frame_is_opaque(), "GBA: frame pixels are opaque");
    unsigned r, g, b;
    pixel_at(120, 80, &r, &g, &b);
    CHECK(r == 0 && g == 0 && b == 0, "GBA: no keys, black screen (%u,%u,%u)", r, g, b);

    emu_set_keys(1 << GB_KEY_A);
    run_frames(3);
    pixel_at(0, 0, &r, &g, &b);
    CHECK(r > 0 && g == 0 && b == 0, "GBA: A reaches the game as A (%u,%u,%u)", r, g, b);
    emu_set_keys(1 << GB_KEY_START);
    run_frames(3);
    unsigned start_red;
    pixel_at(0, 0, &start_red, &g, &b);
    CHECK(start_red > r && g == 0, "GBA: Start reaches the game as Start (red %u)", start_red);
    emu_set_keys(EMU_KEY_L);
    run_frames(3);
    unsigned l_green;
    pixel_at(239, 159, &r, &l_green, &b);
    CHECK(r == 0 && l_green > 100, "GBA: L reaches the game as L (green %u)", l_green);
    emu_set_keys(EMU_KEY_R);
    run_frames(3);
    unsigned r_green;
    pixel_at(239, 159, &r, &r_green, &b);
    CHECK(r == 0 && r_green > 30 && r_green < l_green, "GBA: R reaches the game as R (green %u)", r_green);
    emu_set_keys((1 << GB_KEY_LEFT) | (1 << GB_KEY_DOWN));
    run_frames(3);
    uint8_t save[0x8000];
    CHECK(emu_battery_size() == 0x8000, "GBA: SRAM save detected (%zu bytes)", emu_battery_size());
    CHECK(emu_take_battery_dirty() && !emu_take_battery_dirty(), "GBA: save marked dirty once");
    CHECK(emu_save_battery(save, sizeof(save)) == 0x8000 && save[0] == 0xA0 && save[1] == 0x00,
          "GBA: Left+Down seen by the game (0x%02X%02X)", save[1], save[0]);
    emu_set_keys(0);
    run_frames(2);
    CHECK(emu_take_battery_dirty(), "GBA: a changed save is dirty again");

    /* RetroAchievements: EWRAM at $08000 holds the frame counter the ROM also writes to SRAM. */
    uint8_t bytes[4];
    unsigned count = gba_frame_count();
    CHECK(emu_read_achievement_memory(0x8000, bytes, 4) == 4 &&
          (bytes[0] | (bytes[1] << 8)) == count, "GBA: achievements read EWRAM (%u)", count);
    CHECK(emu_read_achievement_memory(0x48002, bytes, 2) == 2 && (bytes[0] | (bytes[1] << 8)) == count,
          "GBA: achievements read cartridge SRAM");
    CHECK(emu_read_achievement_memory(0x7FFE, bytes, 4) == 4, "GBA: reads run from IWRAM on into EWRAM");
    CHECK(emu_read_achievement_memory(0x50000, bytes, 1) == 0 && emu_read_achievement_memory(0x58000, bytes, 1) == 0,
          "GBA: nothing past the save or the map");

    /* Save states. */
    size_t state_size = emu_state_size();
    CHECK(state_size > 0x40000, "GBA: save state has a size (%zu)", state_size);
    uint8_t *state = malloc(state_size);
    emu_save_state(state);
    unsigned saved = gba_frame_count();
    run_frames(90);
    CHECK(emu_load_state(state, state_size), "GBA: save state loads");
    CHECK(gba_frame_count() == saved + 1, "GBA: state restored the game (%u, saved at %u)", gba_frame_count(), saved);
    CHECK(!emu_load_state(state, 100), "GBA: a truncated state is refused");
    free(state);

    /* Rewind. */
    emu_set_rewind_length(10);
    run_frames(400);
    unsigned now = gba_frame_count();
    bool moved = true;
    for (int i = 0; i < 60 && moved; i++) moved = emu_rewind_frame();
    unsigned back = gba_frame_count();
    CHECK(moved && back < now && now - back >= 50 && now - back <= 130, "GBA: 60 rewind steps go back (%u -> %u)", now, back);
    unsigned steps = 0;
    while (emu_rewind_frame() && steps < 5000) steps++;
    unsigned oldest = gba_frame_count();
    CHECK(steps > 100 && now - oldest <= 620, "GBA: rewinding stops at the recorded history (%u steps, back to %u)", steps,
          oldest);
    run_frames(10);
    CHECK(gba_frame_count() == oldest + 10, "GBA: playing resumes from there (%u)", gba_frame_count());
    emu_set_rewind_length(0);
    run_frames(10);
    CHECK(!emu_rewind_frame(), "GBA: rewind off records nothing");
    emu_set_rewind_length(30);

    /* Cheats: a CodeBreaker 8-bit write to EWRAM. */
    CHECK(emu_set_cheats("32000010 0055\n") == 1, "GBA: a CodeBreaker code is accepted");
    run_frames(2);
    CHECK(emu_read_achievement_memory(0x8010, bytes, 1) == 1 && bytes[0] == 0x55, "GBA: the cheat applies (0x%02X)", bytes[0]);
    CHECK(emu_set_cheats("") == 0, "GBA: cheats can be cleared");
    CHECK(emu_set_cheats("not a code\n") == 0, "GBA: junk is refused");

    emu_reset();
    run_frames(5);
    CHECK(gba_frame_count() < 10, "GBA: reset starts the game over (%u)", gba_frame_count());
    CHECK(!emu_link(gb_rom, gb_rom_size, GB_MODEL_CGB_E, false, 1), "GBA: no link cable");

    /* And back to a Game Boy game. */
    CHECK(emu_load_rom(gb_rom, gb_rom_size, GB_MODEL_CGB_E, 48000) && emu_get_system() == EMU_SYSTEM_GB,
          "GBA: a Game Boy game loads after it");
    run_frames(10);
    emu_get_frame(&width, &height);
    CHECK(width == 160 && height == 144, "GBA: back to a 160x144 screen (%ux%u)", width, height);
    CHECK(emu_load_rom(rom, size, GB_MODEL_CGB_E, 48000) && emu_get_system() == EMU_SYSTEM_GBA,
          "GBA: and a GBA game again");
    run_frames(10);
    free(rom);
}

int main(int argc, char **argv)
{
    if (argc < 3) {
        fprintf(stderr, "Usage: %s <rom> <boot rom dir> [printer test rom] [link test rom] [GBA test rom]\n", argv[0]);
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

    /* RetroAchievements' view of memory. */
    for (int color = 0; color < 2; color++) {
        const char *name = color ? "achievements (CGB)" : "achievements (DMG)";
        emu_load_rom(rom, rom_size, color ? GB_MODEL_CGB_E : GB_MODEL_DMG_B, 48000);
        run_frames(400); /* past the boot animation, into the game */
        uint8_t bytes[0x50];
        CHECK(emu_read_achievement_memory(0x100, bytes, 0x50) == 0x50 && memcmp(bytes, rom + 0x100, 0x50) == 0,
              "%s: $0100-$014F reads the cartridge header", name);
        CHECK(emu_read_achievement_memory(0xA000, bytes, 4) == 4 && bytes[0] == 0x42,
              "%s: $A000 reads cartridge RAM (0x%02X)", name, bytes[0]);
        uint8_t sram[0x2000];
        emu_save_battery(sram, sizeof(sram));
        CHECK(bytes[2] == sram[2] && bytes[3] == sram[3] && (bytes[2] | bytes[3]) != 0 && bytes[3] != 0xFF,
              "%s: the frame counter matches the save RAM", name);
        CHECK(emu_read_achievement_memory(0xFFFF, bytes, 1) == 1 && bytes[0] == 0x01,
              "%s: $FFFF reads the interrupt enable register (0x%02X)", name, bytes[0]);
        uint32_t banks = emu_read_achievement_memory(0x10000, bytes, 16);
        CHECK(color ? banks == 16 : banks == 0, "%s: Color work RAM banks 2-7 %s (read %u)", name,
              color ? "readable" : "absent", banks);
        CHECK(emu_read_achievement_memory(0xFFFE, bytes, 4) == (color ? 4u : 2u),
              "%s: reads stop where memory ends", name);
        CHECK(emu_read_achievement_memory(0x16000, bytes, 1) == 0,
              "%s: no cartridge RAM bank 1 on an 8 KB cartridge", name);
        CHECK(emu_read_achievement_memory(0x40000, bytes, 1) == 0, "%s: nothing past the map", name);
        /* Cheat search reads the work RAM bank register. The test cartridge is a Game Boy game, so on a
         * Color it runs in compatibility mode, where the register reads $FF as on a Game Boy (bank 1). */
        CHECK(emu_read_achievement_memory(0xFF70, bytes, 1) == 1 && bytes[0] == 0xFF,
              "%s: $FF70 reads the work RAM bank (0x%02X)", name, bytes[0]);
    }

    /* Cheats: they change what reads see, for the game and anyone else reading memory. */
    {
        uint8_t byte;
        CHECK(emu_set_cheats("019900A0") == 1, "cheats: a GameShark code is accepted");
        CHECK(emu_read_achievement_memory(0xA000, &byte, 1) == 1 && byte == 0x99,
              "cheats: GameShark code replaces cartridge RAM reads (0x%02X)", byte);
        CHECK(emu_set_cheats("99F-F08\n") == 1 && emu_read_achievement_memory(0x7FF0, &byte, 1) == 1 && byte == 0x99,
              "cheats: Game Genie code replaces ROM reads (0x%02X)", byte);
        emu_read_achievement_memory(0xA000, &byte, 1);
        CHECK(byte == 0x42, "cheats: replacing the list removes the old code (0x%02X)", byte);
        emu_set_cheats("99F-F08-E0A");
        emu_read_achievement_memory(0x7FF0, &byte, 1);
        CHECK(byte == 0xFF, "cheats: a Game Genie code with the wrong original value does nothing (0x%02X)", byte);
        emu_set_cheats("99F-F08-105");
        emu_read_achievement_memory(0x7FF0, &byte, 1);
        CHECK(byte == 0x99, "cheats: ...and applies with the right one (0x%02X)", byte);
        CHECK(emu_set_cheats("01??E4CB\n$8C243F2A\nnonsense\n\n019900A0") == 1, "cheats: invalid lines are skipped");
        CHECK(emu_set_cheats("") == 0, "cheats: an empty list turns them off");
        emu_read_achievement_memory(0xA000, &byte, 1);
        CHECK(byte == 0x42, "cheats: off again (0x%02X)", byte);
        emu_set_cheats("019900A0");
        emu_load_rom(rom, rom_size, GB_MODEL_DMG_B, 48000);
        run_frames(400);
        emu_read_achievement_memory(0xA000, &byte, 1);
        CHECK(byte == 0x42, "cheats: loading a ROM clears them (0x%02X)", byte);
    }

    /* Game Boy Camera: recognised by its cartridge type, and fed pictures without trouble. */
    CHECK(!emu_has_camera(), "camera: an ordinary cartridge has none");
    {
        uint8_t *camera_rom = malloc(rom_size);
        memcpy(camera_rom, rom, rom_size);
        camera_rom[0x147] = 0xFC;
        uint8_t picture[EMU_CAMERA_WIDTH * EMU_CAMERA_HEIGHT];
        for (size_t i = 0; i < sizeof(picture); i++) picture[i] = (uint8_t)i;
        emu_set_camera_image(picture);
        CHECK(emu_load_rom(camera_rom, rom_size, GB_MODEL_DMG_B, 48000) && emu_has_camera(),
              "camera: a Pocket Camera cartridge (type $FC) has one");
        run_frames(30);
        emu_set_camera_image(NULL);
        run_frames(30);
        emu_set_camera_image(picture);
        run_frames(30);
        CHECK(emu_is_loaded(), "camera: pictures can be set and cleared while running");
        emu_set_camera_image(NULL);
        free(camera_rom);
    }

    /* Game Boy Printer: a cartridge prints a strip, black on top and white below. */
    if (argc > 3) {
        size_t print_rom_size;
        uint8_t *print_rom = read_file(argv[3], &print_rom_size);
        CHECK(print_rom != NULL, "printer: test cartridge read");
        uint32_t *pixels = NULL;
        emu_set_link_accessory(EMU_LINK_NOTHING);
        emu_load_rom(print_rom, print_rom_size, GB_MODEL_DMG_B, 48000);
        run_frames(400);
        CHECK(emu_take_printout(&pixels) == 0, "printer: nothing prints with nothing plugged in");
        emu_set_link_accessory(EMU_LINK_PRINTER);
        emu_load_rom(print_rom, print_rom_size, GB_MODEL_DMG_B, 48000);
        unsigned rows = 0;
        for (int second = 0; second < 20 && rows == 0; second++) {
            run_frames(60);
            rows = emu_take_printout(&pixels);
        }
        CHECK(rows == 16, "printer: a 16-row printout comes out (%u)", rows);
        if (rows == 16) {
            uint32_t black = pixels[0], white = pixels[15 * EMU_PRINTOUT_WIDTH + 159];
            CHECK((black & 0xFFFFFF) == 0 && (white & 0xFFFFFF) == 0xFFFFFF && pixels[7 * EMU_PRINTOUT_WIDTH] == black
                  && pixels[8 * EMU_PRINTOUT_WIDTH] == white,
                  "printer: black on top, white below (0x%08X, 0x%08X)", black, white);
        }
        free(pixels);
        CHECK(emu_take_printout(&pixels) == 0, "printer: a printout is only handed over once");
        emu_set_link_accessory(EMU_LINK_NOTHING);
        free(print_rom);
    }

    /* Link cable: two Game Boys pass bytes both ways, and stay in step through save states. */
    if (argc > 4) {
        size_t link_rom_size;
        uint8_t *link_rom = read_file(argv[4], &link_rom_size);
        CHECK(link_rom != NULL, "link: test cartridge read");
        for (int leads = 0; leads < 2; leads++) {
            const char *name = leads ? "link (partner leads)" : "link";
            emu_load_rom(link_rom, link_rom_size, GB_MODEL_DMG_B, 48000);
            CHECK(emu_link(link_rom, link_rom_size, leads ? GB_MODEL_CGB_E : GB_MODEL_DMG_B, leads, 1234) && emu_is_linked(),
                  "%s: a second Game Boy is linked", name);
            emu_set_keys(1 << GB_KEY_A); /* This one clocks the transfers. */
            emu_set_partner_keys(0);
            run_frames(600);
            uint8_t mine[2];
            emu_read_achievement_memory(0xA000, mine, 2);
            uint8_t theirs[0x2000];
            size_t partner_battery = emu_partner_save_battery(theirs, sizeof(theirs));
            CHECK(mine[0] == 0x22 && mine[1] > 10, "%s: received the partner's byte (0x%02X, %u times)", name, mine[0], mine[1]);
            CHECK(partner_battery > 0 && theirs[0] == 0x11 && theirs[1] > 10,
                  "%s: the partner received this one's byte (0x%02X, %u times)", name, theirs[0], theirs[1]);
            CHECK(emu_partner_take_battery_dirty(), "%s: the partner's save changed", name);

            /* What a second device does: start from both states, feed the same keys, get the same frames. */
            size_t size_a = emu_state_size(), size_b = emu_partner_state_size();
            uint8_t *state_a = malloc(size_a), *state_b = malloc(size_b);
            emu_save_state(state_a);
            emu_partner_save_state(state_b);
            uint32_t hashes[2] = {0, 0};
            for (int pass = 0; pass < 2; pass++) {
                CHECK(emu_load_state(state_a, size_a) && emu_partner_load_state(state_b, size_b),
                      "%s: both states load (pass %d)", name, pass + 1);
                emu_show_partner(pass == 1);
                uint32_t hash = 2166136261u;
                for (int frame = 0; frame < 120; frame++) {
                    emu_set_partner_keys(frame % 30 < 5 ? (1 << GB_KEY_START) : 0);
                    run_frames(1);
                    uint8_t counter;
                    emu_read_achievement_memory(0xA001, &counter, 1);
                    hash = (hash ^ counter) * 16777619u;
                }
                emu_partner_save_battery(theirs, sizeof(theirs));
                hash = (hash ^ theirs[1]) * 16777619u;
                hashes[pass] = hash;
            }
            CHECK(hashes[0] == hashes[1], "%s: the same start and keys give the same run (%08X, %08X)", name,
                  hashes[0], hashes[1]);
            unsigned width, height;
            emu_get_frame(&width, &height);
            CHECK(width == 160 && height == 144, "%s: the partner's screen can be shown", name);
            free(state_a);
            free(state_b);
            emu_unlink();
            CHECK(!emu_is_linked() && emu_partner_battery_size() == 0, "%s: unlinked", name);
            run_frames(30);
            CHECK(emu_is_loaded(), "%s: carries on alone after unlinking", name);
        }
        free(link_rom);
    }

    if (argc > 5) test_gba(argv[5], rom, rom_size);

    emu_unload();
    CHECK(emu_get_system() == EMU_SYSTEM_GB, "no game: back to the Game Boy");
    CHECK(emu_set_cheats("019900A0") == 0, "cheats: nothing to cheat with no game");
    uint8_t unused;
    CHECK(emu_read_achievement_memory(0xC000, &unused, 1) == 0, "achievements: nothing to read with no game");
    free(rom);
    printf(failures ? "\n%d check(s) failed\n" : "\nAll checks passed\n", failures);
    return failures != 0;
}
