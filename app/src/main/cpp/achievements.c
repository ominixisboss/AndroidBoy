/*
 * JNI bindings for RetroAchievements (rcheevos rc_client). Web requests are made by Java
 * (Achievements.serverCall) and handed back through nativeHttpResponse; events such as an
 * achievement unlocking are reported to Achievements.onEvent. rc_client locks internally, so
 * these functions may be called from the UI, network and emulation threads, with one rule:
 * game memory is only read on the emulation thread, in achievements_do_frame().
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "rc_client.h"
#include "rc_consoles.h"
#include "achievements.h"
#include "emulator.h"
#include "gba_core.h"

#define TAG "AndroidBoy"
#define JNI_FN(name) Java_com_ominixisboss_androidboy_Achievements_##name

static JavaVM *vm;
static jclass achievements_class;
static jmethodID server_call_method;
static jmethodID on_login_method;
static jmethodID on_game_loaded_method;
static jmethodID on_event_method;
static jmethodID on_progress_method;
static jmethodID on_titles_method;
static jmethodID on_leaderboard_method;
static rc_client_t *client;
/* Set while a game is loaded, so frames don't take rc_client's lock when there's nothing to do. */
static volatile int game_active;
/* rcheevos' hash iterator keeps a pointer to the ROM; keep a copy alive while a game is loaded. */
static uint8_t *rom_copy;

static void unload_game(void);

/* The JNIEnv for this thread, attaching it to the VM if it's a native thread. */
static JNIEnv *get_env(void)
{
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        /* void *: the NDK and JDK headers declare this parameter differently. */
        (*vm)->AttachCurrentThread(vm, (void *)&env, NULL);
    }
    return env;
}

static jstring new_string(JNIEnv *env, const char *text)
{
    return text ? (*env)->NewStringUTF(env, text) : NULL;
}

static uint32_t RC_CCONV read_memory(uint32_t address, uint8_t *buffer, uint32_t num_bytes, rc_client_t *c)
{
    (void)c;
    return emu_read_achievement_memory(address, buffer, num_bytes);
}

static void RC_CCONV server_call(const rc_api_request_t *request, rc_client_server_callback_t callback,
                                 void *callback_data, rc_client_t *c)
{
    (void)c;
    JNIEnv *env = get_env();
    jstring url = new_string(env, request->url);
    jstring post = new_string(env, request->post_data);
    jstring type = new_string(env, request->content_type);
    (*env)->CallStaticVoidMethod(env, achievements_class, server_call_method, url, post, type,
                                 (jlong)(intptr_t)callback, (jlong)(intptr_t)callback_data);
    (*env)->DeleteLocalRef(env, url);
    if (post) (*env)->DeleteLocalRef(env, post);
    if (type) (*env)->DeleteLocalRef(env, type);
}

static void RC_CCONV log_message(const char *message, const rc_client_t *c)
{
    (void)c;
    __android_log_print(ANDROID_LOG_INFO, TAG, "rcheevos: %s", message);
}

static void report_event(int type, const char *title, const char *description, const char *image, int points)
{
    JNIEnv *env = get_env();
    jstring jtitle = new_string(env, title);
    jstring jdescription = new_string(env, description);
    jstring jimage = new_string(env, image);
    (*env)->CallStaticVoidMethod(env, achievements_class, on_event_method, type, jtitle, jdescription, jimage, points);
    if (jtitle) (*env)->DeleteLocalRef(env, jtitle);
    if (jdescription) (*env)->DeleteLocalRef(env, jdescription);
    if (jimage) (*env)->DeleteLocalRef(env, jimage);
}

static void RC_CCONV handle_event(const rc_client_event_t *event, rc_client_t *c)
{
    switch (event->type) {
        case RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED:
            report_event(event->type, event->achievement->title, event->achievement->description,
                         event->achievement->badge_url, (int)event->achievement->points);
            break;
        case RC_CLIENT_EVENT_LEADERBOARD_STARTED:
        case RC_CLIENT_EVENT_LEADERBOARD_FAILED:
        case RC_CLIENT_EVENT_LEADERBOARD_SUBMITTED:
            report_event(event->type, event->leaderboard->title, event->leaderboard->tracker_value, NULL, 0);
            break;
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_SHOW:
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_UPDATE:
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_HIDE:
            /* The live score or time of a leaderboard attempt; "points" carries the tracker's id. */
            report_event(event->type, NULL, event->leaderboard_tracker->display, NULL,
                         (int)event->leaderboard_tracker->id);
            break;
        case RC_CLIENT_EVENT_LEADERBOARD_SCOREBOARD: {
            const rc_client_leaderboard_scoreboard_t *board = event->leaderboard_scoreboard;
            char text[128];
            snprintf(text, sizeof(text), "Rank %u of %u · best %s", board->new_rank, board->num_entries,
                     board->best_score);
            report_event(event->type, event->leaderboard ? event->leaderboard->title : NULL, text, NULL,
                         (int)board->new_rank);
            break;
        }
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_SHOW:
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_UPDATE:
            report_event(event->type, event->achievement->title, event->achievement->measured_progress,
                         event->achievement->badge_url, 0);
            break;
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_HIDE:
            report_event(event->type, NULL, NULL, NULL, 0);
            break;
        case RC_CLIENT_EVENT_GAME_COMPLETED: {
            const rc_client_game_t *game = rc_client_get_game_info(c);
            report_event(event->type, game ? game->title : NULL, NULL, game ? game->badge_url : NULL, 0);
            break;
        }
        case RC_CLIENT_EVENT_SERVER_ERROR:
            report_event(event->type, event->server_error->api, event->server_error->error_message, NULL, 0);
            break;
        case RC_CLIENT_EVENT_RESET:
        case RC_CLIENT_EVENT_DISCONNECTED:
        case RC_CLIENT_EVENT_RECONNECTED:
            report_event(event->type, NULL, NULL, NULL, 0);
            break;
        default:
            break;
    }
}

void achievements_do_frame(void)
{
    if (client && game_active) rc_client_do_frame(client);
}

JNIEXPORT void JNICALL JNI_FN(nativeInit)(JNIEnv *env, jclass clazz)
{
    if (client) return;
    (*env)->GetJavaVM(env, &vm);
    achievements_class = (*env)->NewGlobalRef(env, clazz);
    server_call_method = (*env)->GetStaticMethodID(env, clazz, "serverCall",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJ)V");
    on_login_method = (*env)->GetStaticMethodID(env, clazz, "onLogin", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    on_game_loaded_method = (*env)->GetStaticMethodID(env, clazz, "onGameLoaded", "(ILjava/lang/String;)V");
    on_event_method = (*env)->GetStaticMethodID(env, clazz, "onEvent",
            "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");
    on_progress_method = (*env)->GetStaticMethodID(env, clazz, "onProgress", "(IILjava/lang/String;[I)V");
    on_titles_method = (*env)->GetStaticMethodID(env, clazz, "onTitles",
            "(ILjava/lang/String;[I[Ljava/lang/String;[Ljava/lang/String;)V");
    on_leaderboard_method = (*env)->GetStaticMethodID(env, clazz, "onLeaderboardEntries",
            "(IILjava/lang/String;[Ljava/lang/String;II)V");

    client = rc_client_create(read_memory, server_call);
    rc_client_enable_logging(client, RC_CLIENT_LOG_LEVEL_WARN, log_message);
    rc_client_set_event_handler(client, handle_event);
    /* Game memory is only safe to read between frames on the emulation thread. */
    rc_client_set_allow_background_memory_reads(client, 0);
    rc_client_set_hardcore_enabled(client, 0);
}

JNIEXPORT jstring JNICALL JNI_FN(nativeUserAgentClause)(JNIEnv *env, jclass clazz)
{
    char buffer[64];
    rc_client_get_user_agent_clause(client, buffer, sizeof(buffer));
    return (*env)->NewStringUTF(env, buffer);
}

JNIEXPORT void JNICALL JNI_FN(nativeHttpResponse)(JNIEnv *env, jclass clazz, jlong callback, jlong callback_data,
                                                  jbyteArray body, jint status)
{
    rc_api_server_response_t response;
    memset(&response, 0, sizeof(response));
    char *text = NULL;
    if (body) {
        jsize length = (*env)->GetArrayLength(env, body);
        text = malloc((size_t)length + 1);
        if (!text) return;
        (*env)->GetByteArrayRegion(env, body, 0, length, (jbyte *)text);
        text[length] = '\0';
        response.body = text;
        response.body_length = (size_t)length;
    }
    response.http_status_code = status;
    ((rc_client_server_callback_t)(intptr_t)callback)(&response, (void *)(intptr_t)callback_data);
    free(text);
}

static void RC_CCONV login_done(int result, const char *error, rc_client_t *c, void *userdata)
{
    (void)userdata;
    JNIEnv *env = get_env();
    const rc_client_user_t *user = result == RC_OK ? rc_client_get_user_info(c) : NULL;
    jstring jerror = new_string(env, error);
    jstring jname = new_string(env, user ? user->username : NULL);
    jstring jtoken = new_string(env, user ? user->token : NULL);
    (*env)->CallStaticVoidMethod(env, achievements_class, on_login_method, result, jerror, jname, jtoken);
    if (jerror) (*env)->DeleteLocalRef(env, jerror);
    if (jname) (*env)->DeleteLocalRef(env, jname);
    if (jtoken) (*env)->DeleteLocalRef(env, jtoken);
}

JNIEXPORT void JNICALL JNI_FN(nativeLoginWithPassword)(JNIEnv *env, jclass clazz, jstring username, jstring password)
{
    const char *user = (*env)->GetStringUTFChars(env, username, NULL);
    const char *pass = (*env)->GetStringUTFChars(env, password, NULL);
    rc_client_begin_login_with_password(client, user, pass, login_done, NULL);
    (*env)->ReleaseStringUTFChars(env, username, user);
    (*env)->ReleaseStringUTFChars(env, password, pass);
}

JNIEXPORT void JNICALL JNI_FN(nativeLoginWithToken)(JNIEnv *env, jclass clazz, jstring username, jstring token)
{
    const char *user = (*env)->GetStringUTFChars(env, username, NULL);
    const char *tok = (*env)->GetStringUTFChars(env, token, NULL);
    rc_client_begin_login_with_token(client, user, tok, login_done, NULL);
    (*env)->ReleaseStringUTFChars(env, username, user);
    (*env)->ReleaseStringUTFChars(env, token, tok);
}

JNIEXPORT void JNICALL JNI_FN(nativeLogout)(JNIEnv *env, jclass clazz)
{
    unload_game();
    rc_client_logout(client);
}

/* Fields separated by \x1f: display name, hardcore points, softcore points, avatar URL. Null when logged out. */
JNIEXPORT jstring JNICALL JNI_FN(nativeUserInfo)(JNIEnv *env, jclass clazz)
{
    const rc_client_user_t *user = rc_client_get_user_info(client);
    if (!user) return NULL;
    char buffer[256];
    snprintf(buffer, sizeof(buffer), "%s\x1f%u\x1f%u\x1f%s", user->display_name, user->score, user->score_softcore,
             user->avatar_url ? user->avatar_url : "");
    return (*env)->NewStringUTF(env, buffer);
}

static void RC_CCONV game_loaded(int result, const char *error, rc_client_t *c, void *userdata)
{
    (void)userdata;
    game_active = result == RC_OK;
    JNIEnv *env = get_env();
    jstring jerror = new_string(env, error);
    (*env)->CallStaticVoidMethod(env, achievements_class, on_game_loaded_method, result, jerror);
    if (jerror) (*env)->DeleteLocalRef(env, jerror);
}

static void unload_game(void)
{
    game_active = 0;
    rc_client_unload_game(client);
    free(rom_copy);
    rom_copy = NULL;
}

JNIEXPORT void JNICALL JNI_FN(nativeLoadGame)(JNIEnv *env, jclass clazz, jbyteArray rom)
{
    unload_game();
    jsize size = (*env)->GetArrayLength(env, rom);
    rom_copy = malloc(size > 0 ? (size_t)size : 1);
    if (!rom_copy) return;
    (*env)->GetByteArrayRegion(env, rom, 0, size, (jbyte *)rom_copy);
    /* Games with the Color flag are listed under Game Boy Color; the hash is the same either way.
       GBA games hash the same way too (the whole ROM), but are listed under their own console. */
    uint32_t console = gba_is_rom(rom_copy, (size_t)size) ? RC_CONSOLE_GAMEBOY_ADVANCE
        : size > 0x143 && (rom_copy[0x143] & 0x80) ? RC_CONSOLE_GAMEBOY_COLOR : RC_CONSOLE_GAMEBOY;
    rc_client_begin_identify_and_load_game(client, console, NULL, rom_copy, (size_t)size, game_loaded, NULL);
}

JNIEXPORT void JNICALL JNI_FN(nativeUnloadGame)(JNIEnv *env, jclass clazz)
{
    unload_game();
}

/* Fields separated by \x1f: title, unlocked, total, points unlocked, points total. Null with no game. */
JNIEXPORT jstring JNICALL JNI_FN(nativeGameSummary)(JNIEnv *env, jclass clazz)
{
    const rc_client_game_t *game = rc_client_get_game_info(client);
    if (!game || !rc_client_is_game_loaded(client)) return NULL;
    rc_client_user_game_summary_t summary;
    rc_client_get_user_game_summary(client, &summary);
    char buffer[512];
    snprintf(buffer, sizeof(buffer), "%s\x1f%u\x1f%u\x1f%u\x1f%u", game->title ? game->title : "",
             summary.num_unlocked_achievements, summary.num_core_achievements,
             summary.points_unlocked, summary.points_core);
    return (*env)->NewStringUTF(env, buffer);
}

/*
 * The current game's achievements, one string each, fields separated by \x1f:
 * unlocked (0 or 1), points, title, description, badge URL, progress text.
 */
JNIEXPORT jobjectArray JNICALL JNI_FN(nativeAchievementList)(JNIEnv *env, jclass clazz)
{
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    rc_client_achievement_list_t *list = rc_client_is_game_loaded(client)
            ? rc_client_create_achievement_list(client, RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE,
                                                RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_LOCK_STATE)
            : NULL;
    uint32_t count = 0;
    if (list) {
        for (uint32_t b = 0; b < list->num_buckets; b++) count += list->buckets[b].num_achievements;
    }
    jobjectArray result = (*env)->NewObjectArray(env, (jsize)count, string_class, NULL);
    jsize index = 0;
    char buffer[1024];
    for (uint32_t b = 0; list && b < list->num_buckets; b++) {
        for (uint32_t i = 0; i < list->buckets[b].num_achievements; i++) {
            const rc_client_achievement_t *a = list->buckets[b].achievements[i];
            int unlocked = (a->unlocked & (rc_client_get_hardcore_enabled(client)
                    ? RC_CLIENT_ACHIEVEMENT_UNLOCKED_HARDCORE : RC_CLIENT_ACHIEVEMENT_UNLOCKED_BOTH)) != 0;
            snprintf(buffer, sizeof(buffer), "%d\x1f%u\x1f%s\x1f%s\x1f%s\x1f%s", unlocked, a->points,
                     a->title ? a->title : "", a->description ? a->description : "",
                     unlocked ? (a->badge_url ? a->badge_url : "") : (a->badge_locked_url ? a->badge_locked_url : ""),
                     a->measured_progress);
            jstring row = (*env)->NewStringUTF(env, buffer);
            (*env)->SetObjectArrayElement(env, result, index++, row);
            (*env)->DeleteLocalRef(env, row);
        }
    }
    if (list) rc_client_destroy_achievement_list(list);
    return result;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetHardcore)(JNIEnv *env, jclass clazz, jboolean enabled)
{
    rc_client_set_hardcore_enabled(client, enabled ? 1 : 0);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeIsHardcore)(JNIEnv *env, jclass clazz)
{
    return rc_client_get_hardcore_enabled(client) ? JNI_TRUE : JNI_FALSE;
}

/* Call after the game is reset (or rewound): achievements start over from the current state. */
JNIEXPORT void JNICALL JNI_FN(nativeReset)(JNIEnv *env, jclass clazz)
{
    if (game_active) rc_client_reset(client);
}

/* Keeps the session alive while emulation is paused. */
JNIEXPORT void JNICALL JNI_FN(nativeIdle)(JNIEnv *env, jclass clazz)
{
    if (client) rc_client_idle(client);
}

/* Achievement progress to save with a save state, or null. Emulation thread. */
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeSaveProgress)(JNIEnv *env, jclass clazz)
{
    if (!game_active) return NULL;
    size_t size = rc_client_progress_size(client);
    if (size == 0) return NULL;
    uint8_t *buffer = malloc(size);
    if (!buffer) return NULL;
    jbyteArray result = NULL;
    if (rc_client_serialize_progress_sized(client, buffer, size) == RC_OK) {
        result = (*env)->NewByteArray(env, (jsize)size);
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)size, (const jbyte *)buffer);
    }
    free(buffer);
    return result;
}

/* Restores progress saved with a state; null (an older state) starts achievements over. */
JNIEXPORT void JNICALL JNI_FN(nativeLoadProgress)(JNIEnv *env, jclass clazz, jbyteArray data)
{
    if (!game_active) return;
    if (!data) {
        rc_client_deserialize_progress_sized(client, NULL, 0);
        return;
    }
    jsize size = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    rc_client_deserialize_progress_sized(client, (const uint8_t *)bytes, (size_t)size);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
}

/* ---- The player's progress across games, for the achievements page ---- */

static void RC_CCONV progress_fetched(int result, const char *error, rc_client_all_user_progress_t *list,
                                      rc_client_t *c, void *userdata)
{
    (void)c;
    JNIEnv *env = get_env();
    jint console = (jint)(intptr_t)userdata;
    /* Four ints per game: id, achievements, unlocked (softcore or hardcore), unlocked in hardcore. */
    jsize count = list ? (jsize)list->num_entries : 0;
    jintArray values = (*env)->NewIntArray(env, count * 4);
    for (jsize i = 0; i < count; i++) {
        const rc_client_all_user_progress_entry_t *entry = &list->entries[i];
        jint row[4] = {(jint)entry->game_id, (jint)entry->num_achievements,
                       (jint)entry->num_unlocked_achievements, (jint)entry->num_unlocked_achievements_hardcore};
        (*env)->SetIntArrayRegion(env, values, i * 4, 4, row);
    }
    jstring jerror = new_string(env, error);
    (*env)->CallStaticVoidMethod(env, achievements_class, on_progress_method, console, result, jerror, values);
    if (jerror) (*env)->DeleteLocalRef(env, jerror);
    (*env)->DeleteLocalRef(env, values);
    if (list) rc_client_destroy_all_user_progress(list);
}

/* Asks for the logged-in player's progress in every game for a console; answered through onProgress. */
JNIEXPORT void JNICALL JNI_FN(nativeFetchProgress)(JNIEnv *env, jclass clazz, jint console)
{
    rc_client_begin_fetch_all_user_progress(client, (uint32_t)console, progress_fetched, (void *)(intptr_t)console);
}

static void RC_CCONV titles_fetched(int result, const char *error, rc_client_game_title_list_t *list,
                                    rc_client_t *c, void *userdata)
{
    (void)c;
    (void)userdata;
    JNIEnv *env = get_env();
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jsize count = list ? (jsize)list->num_entries : 0;
    jintArray ids = (*env)->NewIntArray(env, count);
    jobjectArray titles = (*env)->NewObjectArray(env, count, string_class, NULL);
    jobjectArray badges = (*env)->NewObjectArray(env, count, string_class, NULL);
    for (jsize i = 0; i < count; i++) {
        const rc_client_game_title_entry_t *entry = &list->entries[i];
        jint id = (jint)entry->game_id;
        (*env)->SetIntArrayRegion(env, ids, i, 1, &id);
        jstring title = new_string(env, entry->title ? entry->title : "");
        jstring badge = new_string(env, entry->badge_url ? entry->badge_url : "");
        (*env)->SetObjectArrayElement(env, titles, i, title);
        (*env)->SetObjectArrayElement(env, badges, i, badge);
        (*env)->DeleteLocalRef(env, title);
        (*env)->DeleteLocalRef(env, badge);
    }
    jstring jerror = new_string(env, error);
    (*env)->CallStaticVoidMethod(env, achievements_class, on_titles_method, result, jerror, ids, titles, badges);
    if (jerror) (*env)->DeleteLocalRef(env, jerror);
    if (list) rc_client_destroy_game_title_list(list);
}

/* Asks for games' titles and badge images; answered through onTitles. */
JNIEXPORT void JNICALL JNI_FN(nativeFetchTitles)(JNIEnv *env, jclass clazz, jintArray game_ids)
{
    jsize count = (*env)->GetArrayLength(env, game_ids);
    if (count == 0) {
        titles_fetched(RC_OK, NULL, NULL, client, NULL);
        return;
    }
    uint32_t *ids = malloc(sizeof(uint32_t) * (size_t)count);
    if (!ids) return;
    (*env)->GetIntArrayRegion(env, game_ids, 0, count, (jint *)ids);
    rc_client_begin_fetch_game_titles(client, ids, (uint32_t)count, titles_fetched, NULL);
    free(ids);
}

/* ---- Rich presence and leaderboards, for the in-game achievements screen ---- */

/* What the game says the player is doing ("World 1-2, 3 lives"), or null if it doesn't say. */
JNIEXPORT jstring JNICALL JNI_FN(nativeRichPresence)(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    if (!game_active || !rc_client_has_rich_presence(client)) return NULL;
    char buffer[256];
    if (rc_client_get_rich_presence_message(client, buffer, sizeof(buffer)) == 0) return NULL;
    return new_string(env, buffer);
}

/* The current game's leaderboards, one string each: id, title, description (separated by \x1f). */
JNIEXPORT jobjectArray JNICALL JNI_FN(nativeLeaderboardList)(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    rc_client_leaderboard_list_t *list = game_active && rc_client_has_leaderboards(client)
            ? rc_client_create_leaderboard_list(client, RC_CLIENT_LEADERBOARD_LIST_GROUPING_NONE)
            : NULL;
    uint32_t count = 0;
    if (list) {
        for (uint32_t b = 0; b < list->num_buckets; b++) count += list->buckets[b].num_leaderboards;
    }
    jobjectArray result = (*env)->NewObjectArray(env, (jsize)count, string_class, NULL);
    jsize index = 0;
    char buffer[1024];
    for (uint32_t b = 0; list && b < list->num_buckets; b++) {
        for (uint32_t i = 0; i < list->buckets[b].num_leaderboards; i++) {
            const rc_client_leaderboard_t *board = list->buckets[b].leaderboards[i];
            snprintf(buffer, sizeof(buffer), "%u\x1f%s\x1f%s", board->id, board->title ? board->title : "",
                     board->description ? board->description : "");
            jstring row = new_string(env, buffer);
            (*env)->SetObjectArrayElement(env, result, index++, row);
            (*env)->DeleteLocalRef(env, row);
        }
    }
    if (list) rc_client_destroy_leaderboard_list(list);
    return result;
}

static void RC_CCONV leaderboard_fetched(int result, const char *error, rc_client_leaderboard_entry_list_t *list,
                                         rc_client_t *c, void *userdata)
{
    (void)c;
    JNIEnv *env = get_env();
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jsize count = list ? (jsize)list->num_entries : 0;
    jobjectArray rows = (*env)->NewObjectArray(env, count, string_class, NULL);
    char buffer[256];
    for (jsize i = 0; i < count; i++) {
        const rc_client_leaderboard_entry_t *entry = &list->entries[i];
        /* rank, user, score */
        snprintf(buffer, sizeof(buffer), "%u\x1f%s\x1f%s", entry->rank, entry->user ? entry->user : "",
                 entry->display);
        jstring row = new_string(env, buffer);
        (*env)->SetObjectArrayElement(env, rows, i, row);
        (*env)->DeleteLocalRef(env, row);
    }
    jstring jerror = new_string(env, error);
    (*env)->CallStaticVoidMethod(env, achievements_class, on_leaderboard_method, (jint)(intptr_t)userdata, result,
                                 jerror, rows, list ? (jint)list->total_entries : 0,
                                 list ? (jint)list->user_index : -1);
    if (jerror) (*env)->DeleteLocalRef(env, jerror);
    (*env)->DeleteLocalRef(env, rows);
    if (list) rc_client_destroy_leaderboard_entry_list(list);
}

/* The top of a leaderboard, or the entries around the player; answered through onLeaderboardEntries. */
JNIEXPORT void JNICALL JNI_FN(nativeFetchLeaderboard)(JNIEnv *env, jclass clazz, jint id, jboolean around_user,
                                                      jint count)
{
    (void)env;
    (void)clazz;
    if (around_user) {
        rc_client_begin_fetch_leaderboard_entries_around_user(client, (uint32_t)id, (uint32_t)count,
                                                              leaderboard_fetched, (void *)(intptr_t)id);
    } else {
        rc_client_begin_fetch_leaderboard_entries(client, (uint32_t)id, 1, (uint32_t)count, leaderboard_fetched,
                                                  (void *)(intptr_t)id);
    }
}
