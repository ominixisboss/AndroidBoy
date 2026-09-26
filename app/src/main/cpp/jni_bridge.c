/* JNI bindings for com.ominixisboss.androidboy.Emulator. */
#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "achievements.h"
#include "emulator.h"

#define LOG_TAG "AndroidBoy"
#define JNI_FN(name) Java_com_ominixisboss_androidboy_Emulator_##name

static AAssetManager *asset_manager;
static jobject asset_manager_ref;

static void log_sink(const char *message)
{
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", message);
}

static bool asset_boot_rom_provider(const char *name, uint8_t **data, size_t *size)
{
    if (!asset_manager) return false;
    char path[64];
    snprintf(path, sizeof(path), "BootROMs/%s_boot.bin", name);
    AAsset *asset = AAssetManager_open(asset_manager, path, AASSET_MODE_BUFFER);
    if (!asset) return false;
    off_t length = AAsset_getLength(asset);
    uint8_t *buffer = malloc(length);
    bool success = buffer && AAsset_read(asset, buffer, length) == length;
    AAsset_close(asset);
    if (!success) {
        free(buffer);
        return false;
    }
    *data = buffer;
    *size = (size_t)length;
    return true;
}

static jbyteArray to_byte_array(JNIEnv *env, const uint8_t *data, size_t size)
{
    jbyteArray array = (*env)->NewByteArray(env, (jsize)size);
    if (array) {
        (*env)->SetByteArrayRegion(env, array, 0, (jsize)size, (const jbyte *)data);
    }
    return array;
}

JNIEXPORT void JNICALL JNI_FN(nativeInit)(JNIEnv *env, jclass clazz, jobject assets)
{
    (void)clazz;
    if (asset_manager_ref) {
        (*env)->DeleteGlobalRef(env, asset_manager_ref);
    }
    /* Hold a global reference so the native AAssetManager stays valid. */
    asset_manager_ref = (*env)->NewGlobalRef(env, assets);
    asset_manager = AAssetManager_fromJava(env, asset_manager_ref);
    emu_set_boot_rom_provider(asset_boot_rom_provider);
    emu_set_log_sink(log_sink);
}

JNIEXPORT jint JNICALL JNI_FN(nativePickModel)(JNIEnv *env, jclass clazz, jbyteArray rom, jint dmg_model, jint cgb_model)
{
    (void)clazz;
    jsize size = (*env)->GetArrayLength(env, rom);
    jbyte *bytes = (*env)->GetByteArrayElements(env, rom, NULL);
    GB_model_t model = emu_pick_model((const uint8_t *)bytes, (size_t)size, (GB_model_t)dmg_model, (GB_model_t)cgb_model);
    (*env)->ReleaseByteArrayElements(env, rom, bytes, JNI_ABORT);
    return (jint)model;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeLoadRom)(JNIEnv *env, jclass clazz, jbyteArray rom, jint model, jint sample_rate)
{
    (void)clazz;
    jsize size = (*env)->GetArrayLength(env, rom);
    jbyte *bytes = (*env)->GetByteArrayElements(env, rom, NULL);
    bool success = emu_load_rom((const uint8_t *)bytes, (size_t)size, (GB_model_t)model, (unsigned)sample_rate);
    (*env)->ReleaseByteArrayElements(env, rom, bytes, JNI_ABORT);
    return success;
}

JNIEXPORT void JNICALL JNI_FN(nativeUnload)(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    emu_unload();
}

JNIEXPORT void JNICALL JNI_FN(nativeReset)(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    emu_reset();
}

JNIEXPORT void JNICALL JNI_FN(nativeSwitchModel)(JNIEnv *env, jclass clazz, jint model)
{
    (void)env; (void)clazz;
    emu_switch_model((GB_model_t)model);
}

JNIEXPORT jint JNICALL JNI_FN(nativeGetSystem)(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jint)emu_get_system();
}

JNIEXPORT jint JNICALL JNI_FN(nativeGetModel)(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jint)emu_get_model();
}

JNIEXPORT void JNICALL JNI_FN(nativeSetKeys)(JNIEnv *env, jclass clazz, jint mask)
{
    (void)env; (void)clazz;
    emu_set_keys((unsigned)mask);
}

JNIEXPORT jint JNICALL JNI_FN(nativeRunFrame)(JNIEnv *env, jclass clazz, jshortArray audio)
{
    (void)clazz;
    emu_run_frame();
    achievements_do_frame();
    jsize capacity = (*env)->GetArrayLength(env, audio);
    jshort *samples = (*env)->GetPrimitiveArrayCritical(env, audio, NULL);
    if (!samples) return 0;
    size_t count = emu_take_audio((int16_t *)samples, (size_t)capacity);
    (*env)->ReleasePrimitiveArrayCritical(env, audio, samples, 0);
    return (jint)count;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeRewindFrame)(JNIEnv *env, jclass clazz, jshortArray audio)
{
    (void)clazz;
    bool moved = emu_rewind_frame();
    /* Rewound frames play silently; drop what the core produced. */
    jsize capacity = (*env)->GetArrayLength(env, audio);
    jshort *samples = (*env)->GetPrimitiveArrayCritical(env, audio, NULL);
    if (samples) {
        while (emu_take_audio((int16_t *)samples, (size_t)capacity) > 0) {}
        (*env)->ReleasePrimitiveArrayCritical(env, audio, samples, JNI_ABORT);
    }
    return moved;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetRewindLength)(JNIEnv *env, jclass clazz, jint seconds)
{
    (void)env; (void)clazz;
    emu_set_rewind_length(seconds > 0 ? (unsigned)seconds : 0);
}

JNIEXPORT jint JNICALL JNI_FN(nativeGetFrameWidth)(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    unsigned width, height;
    emu_get_frame(&width, &height);
    return (jint)width;
}

JNIEXPORT jint JNICALL JNI_FN(nativeGetFrameHeight)(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    unsigned width, height;
    emu_get_frame(&width, &height);
    return (jint)height;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeCopyFrame)(JNIEnv *env, jclass clazz, jobject buffer)
{
    (void)clazz;
    unsigned width, height;
    const uint32_t *frame = emu_get_frame(&width, &height);

    /* A direct ByteBuffer of at least EMU_MAX_WIDTH * EMU_MAX_HEIGHT pixels, filled tightly packed. */
    void *pixels = (*env)->GetDirectBufferAddress(env, buffer);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, buffer);
    if (!pixels || capacity < (jlong)(width * height * sizeof(uint32_t))) return false;
    memcpy(pixels, frame, width * height * sizeof(uint32_t));
    return true;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeIsOddFrame)(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return emu_is_odd_frame();
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeSaveBattery)(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    size_t size = emu_battery_size();
    if (!size) return NULL;
    uint8_t *buffer = malloc(size);
    if (!buffer) return NULL;
    size_t written = emu_save_battery(buffer, size);
    jbyteArray result = written ? to_byte_array(env, buffer, written) : NULL;
    free(buffer);
    return result;
}

JNIEXPORT void JNICALL JNI_FN(nativeLoadBattery)(JNIEnv *env, jclass clazz, jbyteArray data)
{
    (void)clazz;
    jsize size = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    emu_load_battery((const uint8_t *)bytes, (size_t)size);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeTakeBatteryDirty)(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return emu_take_battery_dirty();
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeSaveState)(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    size_t size = emu_state_size();
    if (!size) return NULL;
    uint8_t *buffer = malloc(size);
    if (!buffer) return NULL;
    emu_save_state(buffer);
    jbyteArray result = to_byte_array(env, buffer, size);
    free(buffer);
    return result;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeLoadState)(JNIEnv *env, jclass clazz, jbyteArray data)
{
    (void)clazz;
    jsize size = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    bool success = emu_load_state((const uint8_t *)bytes, (size_t)size);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return success;
}

/* ---- Link cable ---- */

JNIEXPORT jboolean JNICALL JNI_FN(nativeLink)(JNIEnv *env, jclass clazz, jbyteArray rom, jint model,
                                             jboolean partner_leads, jlong seed)
{
    (void)clazz;
    jsize size = (*env)->GetArrayLength(env, rom);
    jbyte *bytes = (*env)->GetByteArrayElements(env, rom, NULL);
    if (!bytes) return JNI_FALSE;
    bool linked = emu_link((const uint8_t *)bytes, (size_t)size, (GB_model_t)model, partner_leads, (uint64_t)seed);
    (*env)->ReleaseByteArrayElements(env, rom, bytes, JNI_ABORT);
    return linked;
}

JNIEXPORT void JNICALL JNI_FN(nativeUnlink)(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    emu_unlink();
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeIsLinked)(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return emu_is_linked();
}

JNIEXPORT void JNICALL JNI_FN(nativeSetPartnerKeys)(JNIEnv *env, jclass clazz, jint mask)
{
    (void)env; (void)clazz;
    emu_set_partner_keys((unsigned)mask);
}

JNIEXPORT void JNICALL JNI_FN(nativeShowPartner)(JNIEnv *env, jclass clazz, jboolean show)
{
    (void)env; (void)clazz;
    emu_show_partner(show);
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeSavePartnerBattery)(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    size_t size = emu_partner_battery_size();
    if (!size) return NULL;
    uint8_t *buffer = malloc(size);
    if (!buffer) return NULL;
    size_t written = emu_partner_save_battery(buffer, size);
    jbyteArray result = written ? to_byte_array(env, buffer, written) : NULL;
    free(buffer);
    return result;
}

JNIEXPORT void JNICALL JNI_FN(nativeLoadPartnerBattery)(JNIEnv *env, jclass clazz, jbyteArray data)
{
    (void)clazz;
    jsize size = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    emu_partner_load_battery((const uint8_t *)bytes, (size_t)size);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeTakePartnerBatteryDirty)(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return emu_partner_take_battery_dirty();
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeSavePartnerState)(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    size_t size = emu_partner_state_size();
    if (!size) return NULL;
    uint8_t *buffer = malloc(size);
    if (!buffer) return NULL;
    emu_partner_save_state(buffer);
    jbyteArray result = to_byte_array(env, buffer, size);
    free(buffer);
    return result;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeLoadPartnerState)(JNIEnv *env, jclass clazz, jbyteArray data)
{
    (void)clazz;
    jsize size = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    bool success = emu_partner_load_state((const uint8_t *)bytes, (size_t)size);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return success;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetColorCorrection)(JNIEnv *env, jclass clazz, jint mode)
{
    (void)env; (void)clazz;
    emu_set_color_correction((GB_color_correction_mode_t)mode);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetDmgPalette)(JNIEnv *env, jclass clazz, jint index)
{
    (void)env; (void)clazz;
    emu_set_dmg_palette((unsigned)index);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetBorderMode)(JNIEnv *env, jclass clazz, jint mode)
{
    (void)env; (void)clazz;
    emu_set_border_mode((GB_border_mode_t)mode);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetHighpass)(JNIEnv *env, jclass clazz, jint mode)
{
    (void)env; (void)clazz;
    emu_set_highpass((GB_highpass_mode_t)mode);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetRumbleMode)(JNIEnv *env, jclass clazz, jint mode)
{
    (void)env; (void)clazz;
    emu_set_rumble_mode((GB_rumble_mode_t)mode);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetLinkAccessory)(JNIEnv *env, jclass clazz, jint accessory)
{
    (void)env; (void)clazz;
    emu_set_link_accessory(accessory);
}

JNIEXPORT jintArray JNICALL JNI_FN(nativeTakePrintout)(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    uint32_t *pixels;
    unsigned rows = emu_take_printout(&pixels);
    if (rows == 0) return NULL;
    jsize count = (jsize)(rows * EMU_PRINTOUT_WIDTH);
    jintArray result = (*env)->NewIntArray(env, count);
    if (result) (*env)->SetIntArrayRegion(env, result, 0, count, (const jint *)pixels);
    free(pixels);
    return result;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeHasCamera)(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return emu_has_camera() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetCameraImage)(JNIEnv *env, jclass clazz, jbyteArray pixels)
{
    (void)clazz;
    if (!pixels || (*env)->GetArrayLength(env, pixels) < EMU_CAMERA_WIDTH * EMU_CAMERA_HEIGHT) {
        emu_set_camera_image(NULL);
        return;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, pixels, NULL);
    if (!bytes) return;
    emu_set_camera_image((const uint8_t *)bytes);
    (*env)->ReleaseByteArrayElements(env, pixels, bytes, JNI_ABORT);
}

JNIEXPORT jint JNICALL JNI_FN(nativeReadMemory)(JNIEnv *env, jclass clazz, jint address, jbyteArray buffer)
{
    (void)clazz;
    jsize length = (*env)->GetArrayLength(env, buffer);
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (!bytes) return 0;
    uint32_t read = emu_read_achievement_memory((uint32_t)address, (uint8_t *)bytes, (uint32_t)length);
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, 0);
    return (jint)read;
}

JNIEXPORT jint JNICALL JNI_FN(nativeSetCheats)(JNIEnv *env, jclass clazz, jstring codes)
{
    (void)clazz;
    const char *text = codes ? (*env)->GetStringUTFChars(env, codes, NULL) : NULL;
    unsigned count = emu_set_cheats(text ? text : "");
    if (text) (*env)->ReleaseStringUTFChars(env, codes, text);
    return (jint)count;
}

JNIEXPORT jdouble JNICALL JNI_FN(nativeGetRumble)(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return emu_get_rumble();
}

JNIEXPORT jstring JNICALL JNI_FN(nativeGetTitle)(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    char title[17];
    emu_get_title(title);
    /* ROM titles are nominally ASCII; replace anything else so NewStringUTF gets valid input. */
    for (char *c = title; *c; c++) {
        if ((unsigned char)*c < 0x20 || (unsigned char)*c > 0x7E) *c = ' ';
    }
    return (*env)->NewStringUTF(env, title);
}
