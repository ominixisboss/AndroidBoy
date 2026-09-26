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
