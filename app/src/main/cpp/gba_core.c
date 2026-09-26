#include "gba_core.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <mgba/core/blip_buf.h>
#include <mgba/core/cheats.h>
#include <mgba/core/config.h>
#include <mgba/core/core.h>
#include <mgba/core/log.h>
#include <mgba/core/rewind.h>
#include <mgba/core/serialize.h>
#include <mgba/gba/core.h>
#include <mgba/internal/gba/cheats.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/savedata.h>
#include <mgba-util/vfs.h>

/* The largest cartridge save (1 Mbit flash); smaller saves use the start of the buffer. */
#define SAVE_CAPACITY 0x20000
/* Interleaved stereo samples waiting for emu_take_audio, as in emulator.c. */
#define AUDIO_CAPACITY 16384
/* Rewind history is recorded every other frame (each entry is a delta, but GBA memory is big). */
#define REWIND_INTERVAL 2

static struct mCore *core;
static uint8_t *rom_copy;
static uint8_t *save_data;
static uint8_t *save_snapshot;
static color_t video_buffer[GBA_WIDTH * GBA_HEIGHT];
static uint32_t frame[GBA_WIDTH * GBA_HEIGHT];
static int16_t audio[AUDIO_CAPACITY];
static size_t audio_count;

static struct mCoreRewindContext rewind_history;
static bool rewind_ready;
static unsigned rewind_seconds = 30;
static unsigned frames_since_rewind_entry;

static void (*log_sink)(const char *message);
static struct mLogger logger;

static void log_message(struct mLogger *unused, int category, enum mLogLevel level, const char *format, va_list args)
{
    (void)unused;
    if (!log_sink || !(level & (mLOG_FATAL | mLOG_ERROR))) return;
    char message[512];
    int prefix = snprintf(message, sizeof(message), "mGBA %s: ", mLogCategoryName(category));
    if (prefix < 0 || (size_t)prefix >= sizeof(message)) prefix = 0;
    vsnprintf(message + prefix, sizeof(message) - (size_t)prefix, format, args);
    log_sink(message);
}

bool gba_is_rom(const uint8_t *rom, size_t size)
{
    /* The start of the Nintendo logo every cartridge carries at 0x04, and the fixed 0x96 at 0xB2. */
    static const uint8_t logo[] = {0x24, 0xFF, 0xAE, 0x51};
    return size >= 0xC0 && memcmp(rom + 4, logo, sizeof(logo)) == 0 && rom[0xB2] == 0x96;
}

static void free_rewind(void)
{
    if (rewind_ready) mCoreRewindContextDeinit(&rewind_history);
    rewind_ready = false;
}

static void start_rewind(void)
{
    free_rewind();
    frames_since_rewind_entry = 0;
    if (!core || !rewind_seconds) return;
    size_t entries = (size_t)rewind_seconds * 60 / REWIND_INTERVAL;
    mCoreRewindContextInit(&rewind_history, entries, false);
    rewind_ready = true;
}

void gba_unload(void)
{
    free_rewind();
    if (core) {
        mCoreConfigDeinit(&core->config);
        core->deinit(core); /* Frees it too. */
        core = NULL;
    }
    free(rom_copy);
    rom_copy = NULL;
    free(save_data);
    save_data = NULL;
    free(save_snapshot);
    save_snapshot = NULL;
    audio_count = 0;
}

bool gba_load(const uint8_t *rom, size_t size, unsigned sample_rate, void (*log)(const char *message))
{
    gba_unload();
    log_sink = log;
    logger.log = log_message;
    logger.filter = NULL;
    mLogSetDefaultLogger(&logger);

    rom_copy = malloc(size);
    save_data = malloc(SAVE_CAPACITY);
    save_snapshot = malloc(SAVE_CAPACITY);
    core = GBACoreCreate();
    if (core) mCoreInitConfig(core, NULL);
    if (!rom_copy || !save_data || !save_snapshot || !core || !core->init(core)) {
        if (core) {
            mCoreConfigDeinit(&core->config);
            free(core);
            core = NULL;
        }
        gba_unload();
        return false;
    }
    memcpy(rom_copy, rom, size);
    /* Erased flash reads as 0xFF, like a new cartridge. */
    memset(save_data, 0xFF, SAVE_CAPACITY);
    memcpy(save_snapshot, save_data, SAVE_CAPACITY);

    struct mCoreOptions options = {
        .useBios = false, /* No BIOS files: mGBA's own replacement runs instead. */
        .skipBios = true,
        .volume = 0x100,
    };
    mCoreConfigLoadDefaults(&core->config, &options);
    mCoreLoadConfig(core);

    core->setVideoBuffer(core, video_buffer, GBA_WIDTH);
    core->setAudioBufferSize(core, 0x4000);
    unsigned rate = sample_rate ? sample_rate : 48000;
    blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), rate);
    blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), rate);

    struct VFile *rom_file = VFileFromMemory(rom_copy, size);
    if (!rom_file || !core->loadROM(core, rom_file)) {
        gba_unload();
        return false;
    }
    /* The game reads and writes its save straight in save_data (memory files are mapped, not copied). */
    struct VFile *save_file = VFileFromMemory(save_data, SAVE_CAPACITY);
    if (!save_file || !core->loadSave(core, save_file)) {
        gba_unload();
        return false;
    }
    core->reset(core);
    memset(frame, 0, sizeof(frame));
    start_rewind();
    return true;
}

void gba_reset(void)
{
    if (!core) return;
    core->reset(core);
    start_rewind();
}

void gba_set_keys(unsigned mask)
{
    if (!core) return;
    /* emulator.h's bits: right, left, up, down, A, B, select, start (SameBoy's order), then L, R. */
    static const uint8_t gba_bit[] = {4, 5, 6, 7, 0, 1, 2, 3, 9, 8};
    uint32_t keys = 0;
    for (unsigned i = 0; i < sizeof(gba_bit); i++) {
        if (mask & (1u << i)) keys |= 1u << gba_bit[i];
    }
    core->setKeys(core, keys);
}

/* Moves what the core has produced into the audio queue, dropping the oldest if it's full. */
static void collect_audio(void)
{
    blip_t *left = core->getAudioChannel(core, 0);
    blip_t *right = core->getAudioChannel(core, 1);
    int16_t chunk[1024];
    int available;
    while ((available = blip_samples_avail(left)) > 0) {
        int count = available < 512 ? available : 512;
        count = blip_read_samples(left, chunk, count, 1);
        blip_read_samples(right, chunk + 1, count, 1);
        size_t samples = (size_t)count * 2;
        if (samples > AUDIO_CAPACITY) samples = AUDIO_CAPACITY;
        if (audio_count + samples > AUDIO_CAPACITY) {
            size_t drop = audio_count + samples - AUDIO_CAPACITY;
            memmove(audio, audio + drop, (audio_count - drop) * sizeof(int16_t));
            audio_count -= drop;
        }
        memcpy(audio + audio_count, chunk, samples * sizeof(int16_t));
        audio_count += samples;
    }
}

/* Copies the finished frame out, opaque; mGBA leaves the alpha byte clear. */
static void publish_frame(void)
{
    for (size_t i = 0; i < GBA_WIDTH * GBA_HEIGHT; i++) {
        frame[i] = (uint32_t)video_buffer[i] | 0xFF000000u;
    }
}

static void run_one_frame(void)
{
    core->runFrame(core);
    collect_audio();
    publish_frame();
}

void gba_run_frame(void)
{
    if (!core) return;
    run_one_frame();
    if (rewind_ready && ++frames_since_rewind_entry >= REWIND_INTERVAL) {
        frames_since_rewind_entry = 0;
        mCoreRewindAppend(&rewind_history, core);
    }
}

void gba_set_rewind_length(unsigned seconds)
{
    if (seconds == rewind_seconds && (rewind_ready || !core || !seconds)) return;
    rewind_seconds = seconds;
    start_rewind();
}

bool gba_rewind_frame(void)
{
    if (!core || !rewind_ready) return false;
    if (!mCoreRewindRestore(&rewind_history, core)) return false;
    /* A state doesn't hold the picture, so run a frame from it to show it. */
    run_one_frame();
    frames_since_rewind_entry = 0;
    return true;
}

const uint32_t *gba_get_frame(void)
{
    return frame;
}

size_t gba_take_audio(int16_t *out, size_t max_samples)
{
    size_t count = audio_count < max_samples ? audio_count : max_samples;
    count &= ~(size_t)1;
    memcpy(out, audio, count * sizeof(int16_t));
    memmove(audio, audio + count, (audio_count - count) * sizeof(int16_t));
    audio_count -= count;
    return count;
}

size_t gba_battery_size(void)
{
    if (!core) return 0;
    struct GBA *gba = core->board;
    /* Until the game first uses its save, its type (and so size) isn't known. */
    if (gba->memory.savedata.type == SAVEDATA_AUTODETECT || gba->memory.savedata.type == SAVEDATA_FORCE_NONE) {
        return 0;
    }
    size_t size = GBASavedataSize(&gba->memory.savedata);
    return size <= SAVE_CAPACITY ? size : SAVE_CAPACITY;
}

size_t gba_save_battery(uint8_t *buffer, size_t size)
{
    size_t needed = gba_battery_size();
    if (!needed || size < needed) return 0;
    memcpy(buffer, save_data, needed);
    return needed;
}

void gba_load_battery(const uint8_t *buffer, size_t size)
{
    if (!core || !size) return;
    if (size > SAVE_CAPACITY) size = SAVE_CAPACITY;
    memcpy(save_data, buffer, size);
    memcpy(save_snapshot, save_data, SAVE_CAPACITY);
}

bool gba_take_battery_dirty(void)
{
    size_t size = gba_battery_size();
    if (!size || memcmp(save_data, save_snapshot, size) == 0) return false;
    memcpy(save_snapshot, save_data, size);
    return true;
}

/* mGBA's own save state format, with the cartridge save and clock so a state is complete. */
static struct VFile *serialize(void)
{
    struct VFile *file = VFileMemChunk(NULL, 0);
    if (file && !mCoreSaveStateNamed(core, file, SAVESTATE_SAVEDATA | SAVESTATE_RTC)) {
        file->close(file);
        return NULL;
    }
    return file;
}

size_t gba_state_size(void)
{
    if (!core) return 0;
    struct VFile *file = serialize();
    if (!file) return 0;
    size_t size = (size_t)file->size(file);
    file->close(file);
    return size;
}

void gba_save_state(uint8_t *buffer)
{
    if (!core) return;
    struct VFile *file = serialize();
    if (!file) return;
    file->seek(file, 0, SEEK_SET);
    file->read(file, buffer, (size_t)file->size(file));
    file->close(file);
}

bool gba_load_state(const uint8_t *buffer, size_t size)
{
    if (!core) return false;
    struct VFile *file = VFileFromConstMemory(buffer, size);
    if (!file) return false;
    /* The state's cartridge save comes back too, as with SameBoy's states. Without it mGBA would
       "mask" the save, and the game's later saves would go to a temporary copy, not save_data. */
    bool loaded = mCoreLoadStateNamed(core, file, SAVESTATE_SAVEDATA | SAVESTATE_RTC);
    file->close(file);
    if (loaded) {
        /* Show where the state left off; its picture isn't in it. */
        run_one_frame();
        audio_count = 0;
        start_rewind();
    }
    return loaded;
}

uint32_t gba_read_achievement_memory(uint32_t address, uint8_t *buffer, uint32_t num_bytes)
{
    if (!core) return 0;
    size_t save_size = gba_battery_size();
    for (uint32_t i = 0; i < num_bytes; i++) {
        uint32_t a = address + i;
        if (a < 0x8000) {
            buffer[i] = (uint8_t)core->rawRead8(core, 0x03000000u + a, -1);
        } else if (a < 0x48000) {
            buffer[i] = (uint8_t)core->rawRead8(core, 0x02000000u + (a - 0x8000), -1);
        } else if (a < 0x58000 && a - 0x48000 < save_size) {
            buffer[i] = save_data[a - 0x48000];
        } else {
            return i;
        }
    }
    return num_bytes;
}

static bool all_hex(const char *text, size_t length)
{
    for (size_t i = 0; i < length; i++) {
        char c = text[i];
        if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f'))) return false;
    }
    return length > 0;
}

/* Copies one line of `codes` into `line` (trimmed); returns where the next line starts. */
static const char *next_line(const char *codes, char *line, size_t capacity)
{
    const char *end = strchr(codes, '\n');
    size_t length = end ? (size_t)(end - codes) : strlen(codes);
    while (length > 0 && (codes[length - 1] == '\r' || codes[length - 1] == ' ')) length--;
    if (length >= capacity) length = 0;
    memcpy(line, codes, length);
    line[length] = 0;
    return end ? end + 1 : codes + strlen(codes);
}

unsigned gba_set_cheats(const char *codes)
{
    if (!core) return 0;
    struct mCheatDevice *device = core->cheatDevice(core);
    if (!device) return 0;
    /* Take the old codes out properly (undoing their hooks and ROM patches), then free them. */
    while (mCheatSetsSize(&device->cheats)) {
        struct mCheatSet *old = *mCheatSetsGetPointer(&device->cheats, 0);
        mCheatRemoveSet(device, old);
        mCheatSetDeinit(old);
    }
    struct mCheatSet *set = device->createSet(device, NULL);
    if (!set) return 0;
    /* Codes come one per line with spaces taken out, and cheat files split each GameShark or
       Action Replay code into two 8-digit halves (CodeBreaker: 8 and 4). mGBA wants each code on
       one line with a space in the middle, so put the halves back together. */
    unsigned count = 0;
    char line[64], next[64], code[80];
    const char *rest = codes ? codes : "";
    if (*rest) rest = next_line(rest, line, sizeof(line));
    else line[0] = 0;
    bool have_line = codes && *codes;
    while (have_line) {
        bool more = *rest != 0;
        if (more) rest = next_line(rest, next, sizeof(next)); else next[0] = 0;
        size_t length = strlen(line), next_length = strlen(next);
        bool consumed_next = false;
        if (length == 8 && all_hex(line, 8) && (next_length == 8 || next_length == 4) && all_hex(next, next_length)) {
            snprintf(code, sizeof(code), "%s %s", line, next);
            consumed_next = true;
        } else if ((length == 16 || length == 12) && all_hex(line, length)) {
            snprintf(code, sizeof(code), "%.8s %s", line, line + 8);
        } else {
            snprintf(code, sizeof(code), "%s", line);
        }
        if (code[0] && mCheatAddLine(set, code, GBA_CHEAT_AUTODETECT)) count++;
        if (consumed_next) {
            if (!*rest) break;
            rest = next_line(rest, line, sizeof(line));
        } else {
            if (!more) break;
            memcpy(line, next, sizeof(line));
        }
    }
    if (!count) {
        mCheatSetDeinit(set); /* Frees it too. */
        return 0;
    }
    mCheatAddSet(device, set);
    if (set->refresh) set->refresh(set, device);
    return count;
}

void gba_get_title(char title[17])
{
    title[0] = 0;
    if (!core) return;
    char name[17] = {0};
    core->getGameTitle(core, name);
    name[16] = 0;
    /* Titles are padded with spaces or zeros. */
    size_t length = strlen(name);
    while (length > 0 && name[length - 1] == ' ') name[--length] = 0;
    memcpy(title, name, length + 1);
}
