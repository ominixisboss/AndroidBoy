/*
 * Host test for the RetroAchievements integration: rcheevos' client runs against the emulator
 * wrapper's memory reader, with a stand-in server answering login, game data and unlocks.
 * Checks that the test cartridge is identified by its MD5, and that achievements unlock from
 * what the game writes to memory, at the right moment.
 *
 * Usage: achievements_test <testrom.gb> <boot ROM dir> <MD5 of testrom.gb>
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "emulator.h"
#include "rc_client.h"
#include "rc_consoles.h"

static int failures;
#define CHECK(cond, ...) do { \
    if (cond) { printf("ok:   "); } else { printf("FAIL: "); failures++; } \
    printf(__VA_ARGS__); printf("\n"); } while (0)

static const char *boot_rom_dir;
static const char *expected_md5;
static char last_request[1024];
static int saw_hash_request;
static unsigned triggered[8];
static unsigned triggered_count;
static int login_result = -1000;
static int load_result = -1000;

static uint8_t *read_file(const char *path, size_t *size)
{
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long length = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t *data = malloc(length);
    *size = fread(data, 1, length, f);
    fclose(f);
    return data;
}

static bool boot_roms(const char *name, uint8_t **data, size_t *size)
{
    char path[512];
    snprintf(path, sizeof(path), "%s/%s_boot.bin", boot_rom_dir, name);
    *data = read_file(path, size);
    return *data != NULL;
}

static uint32_t RC_CCONV read_memory(uint32_t address, uint8_t *buffer, uint32_t num_bytes, rc_client_t *client)
{
    (void)client;
    return emu_read_achievement_memory(address, buffer, num_bytes);
}

/* Two achievements: the game's battery marker ($A000 = $42), and A+Start held ($A001 = $F6). */
static const char *game_data = "{\"Success\":true,"
    "\"GameId\":1234,\"Title\":\"Test Cartridge\",\"ConsoleId\":4,"
    "\"ImageIconUrl\":\"http://server/Images/000001.png\","
    "\"RichPresenceGameId\":1234,\"RichPresencePatch\":\"\",\"Sets\":[{"
      "\"AchievementSetId\":1111,\"GameId\":1234,\"Title\":null,\"Type\":\"core\","
      "\"ImageIconUrl\":\"http://server/Images/000001.png\","
      "\"Achievements\":["
       "{\"ID\":1,\"Title\":\"Marker\",\"Description\":\"Write the marker\",\"Flags\":3,\"Points\":5,"
        "\"MemAddr\":\"0xHa000=66\",\"Author\":\"Test\",\"BadgeName\":\"00001\","
        "\"Created\":1367266583,\"Modified\":1376929305},"
       "{\"ID\":2,\"Title\":\"Start\",\"Description\":\"Press A and Start\",\"Flags\":3,\"Points\":10,"
        "\"MemAddr\":\"0xHa001=246\",\"Author\":\"Test\",\"BadgeName\":\"00002\","
        "\"Created\":1367266583,\"Modified\":1376929305}"
      "],"
      "\"Leaderboards\":[]"
    "}]}";

static void RC_CCONV server_call(const rc_api_request_t *request, rc_client_server_callback_t callback,
                                 void *callback_data, rc_client_t *client)
{
    (void)client;
    const char *post = request->post_data ? request->post_data : "";
    snprintf(last_request, sizeof(last_request), "%s", post);
    const char *body = "{\"Success\":false,\"Error\":\"Unexpected request\"}";
    if (strstr(post, "r=login2")) {
        body = "{\"Success\":true,\"User\":\"Tester\",\"Token\":\"ApiToken\",\"Score\":100,"
               "\"SoftcoreScore\":20,\"Messages\":0,\"Permissions\":1,\"AccountType\":\"Registered\"}";
    }
    else if (strstr(post, "r=achievementsets")) {
        char key[64];
        snprintf(key, sizeof(key), "m=%s", expected_md5);
        saw_hash_request = strstr(post, key) != NULL;
        body = game_data;
    }
    else if (strstr(post, "r=startsession")) {
        body = "{\"Success\":true,\"Unlocks\":[],\"HardcoreUnlocks\":[]}";
    }
    else if (strstr(post, "r=awardachievement")) {
        body = "{\"Success\":true,\"Score\":105,\"SoftcoreScore\":20,\"AchievementID\":1,\"AchievementsRemaining\":1}";
    }
    else if (strstr(post, "r=ping")) {
        body = "{\"Success\":true}";
    }
    rc_api_server_response_t response;
    memset(&response, 0, sizeof(response));
    response.body = body;
    response.body_length = strlen(body);
    response.http_status_code = 200;
    callback(&response, callback_data);
}

static void RC_CCONV handle_event(const rc_client_event_t *event, rc_client_t *client)
{
    (void)client;
    if (event->type == RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED && triggered_count < 8) {
        triggered[triggered_count++] = event->achievement->id;
    }
}

static void RC_CCONV on_login(int result, const char *error, rc_client_t *client, void *userdata)
{
    (void)error; (void)client; (void)userdata;
    login_result = result;
}

static void RC_CCONV on_load(int result, const char *error, rc_client_t *client, void *userdata)
{
    (void)client; (void)userdata;
    load_result = result;
    if (error) printf("load error: %s\n", error);
}

static void run(rc_client_t *client, unsigned frames)
{
    int16_t audio[EMU_AUDIO_CAPACITY];
    for (unsigned i = 0; i < frames; i++) {
        emu_run_frame();
        emu_take_audio(audio, EMU_AUDIO_CAPACITY);
        rc_client_do_frame(client);
    }
}

int main(int argc, char **argv)
{
    if (argc < 4) {
        fprintf(stderr, "usage: %s testrom.gb bootrom-dir md5\n", argv[0]);
        return 2;
    }
    boot_rom_dir = argv[2];
    expected_md5 = argv[3];
    size_t rom_size;
    uint8_t *rom = read_file(argv[1], &rom_size);
    if (!rom) return 2;
    emu_set_boot_rom_provider(boot_roms);

    rc_client_t *client = rc_client_create(read_memory, server_call);
    rc_client_set_event_handler(client, handle_event);
    rc_client_set_allow_background_memory_reads(client, 0);
    rc_client_set_hardcore_enabled(client, 0);

    rc_client_begin_login_with_password(client, "Tester", "secret", on_login, NULL);
    const rc_client_user_t *user = rc_client_get_user_info(client);
    CHECK(login_result == RC_OK && user && strcmp(user->token, "ApiToken") == 0, "logs in and gets a token");

    CHECK(emu_load_rom(rom, rom_size, GB_MODEL_DMG_B, 48000), "ROM loads");
    rc_client_begin_identify_and_load_game(client, RC_CONSOLE_GAMEBOY, NULL, rom, rom_size, on_load, NULL);
    CHECK(saw_hash_request, "the game is looked up by the ROM's MD5 (%s)", expected_md5);
    run(client, 1); /* memory is only read between frames, so loading finishes here */
    const rc_client_game_t *game = rc_client_get_game_info(client);
    CHECK(load_result == RC_OK && game && strcmp(game->title, "Test Cartridge") == 0, "game loads (%d)", load_result);

    /* The boot animation runs first; the marker is written once the game itself starts. */
    run(client, 60);
    CHECK(triggered_count == 0, "nothing unlocks during the boot animation");
    run(client, 400);
    CHECK(triggered_count == 1 && triggered[0] == 1, "the marker achievement unlocks when the game writes it (%u)",
          triggered_count);
    CHECK(strstr(last_request, "r=awardachievement") && strstr(last_request, "a=1"), "the unlock is sent to the server");

    /* Achievement progress survives a save state round trip. */
    size_t progress_size = rc_client_progress_size(client);
    uint8_t *progress = malloc(progress_size);
    CHECK(rc_client_serialize_progress_sized(client, progress, progress_size) == RC_OK, "progress serializes (%zu bytes)",
          progress_size);
    CHECK(rc_client_deserialize_progress_sized(client, progress, progress_size) == RC_OK, "progress restores");
    free(progress);

    emu_set_keys((1 << GB_KEY_A) | (1 << GB_KEY_START));
    run(client, 10);
    CHECK(triggered_count == 2 && triggered[1] == 2, "pressing A+Start unlocks the second one (%u)", triggered_count);
    emu_set_keys(0);

    rc_client_user_game_summary_t summary;
    rc_client_get_user_game_summary(client, &summary);
    CHECK(summary.num_unlocked_achievements == 2 && summary.points_unlocked == 15, "summary: 2 of %u, %u points",
          summary.num_core_achievements, summary.points_unlocked);

    rc_client_unload_game(client);
    rc_client_destroy(client);
    emu_unload();
    free(rom);
    printf(failures ? "\n%d check(s) failed\n" : "\nAll achievement checks passed\n", failures);
    return failures != 0;
}
