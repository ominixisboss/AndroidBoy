# AndroidBoy

A standalone Game Boy and Game Boy Color emulator for Android, built on the
[SameBoy](https://sameboy.github.io) 1.0.3 core by Lior Halphon.

It doesn't need RetroArch or any other frontend: you install one APK, add your ROMs, and play.

## Features

- **SameBoy's accurate core.** Emulates the Game Boy, Game Boy Pocket, Super Game Boy (1 and 2),
  Game Boy Color and Game Boy Advance. By default, Game Boy games run on a Game Boy Color, as in SameBoy.
- **No BIOS files needed.** SameBoy's own open-source boot ROMs are built in, so no Nintendo code is included.
- **Game library.** Add `.gb`/`.gbc` ROMs, or `.zip` files containing them, from any storage provider.
  You can also use "Open with" from a file manager.
- **Saving.** Battery saves (including the RTC) are written automatically every few seconds.
  There are 9 save-state slots, plus an automatic "resume where you left off" state.
  You can import and export `.sav` files to move saves between devices and emulators.
- **Controls.** Multi-touch on-screen controls with haptic feedback: an 8-way d-pad and A/B, where
  sliding between A and B presses both. Physical gamepads and keyboards work too, and the touch
  controls hide while one is in use.
- **Display.** Nearest-neighbour scaling, with fit-to-screen or integer-only modes. Also SameBoy's
  color correction modes, monochrome palettes and Super Game Boy borders.
- **Audio.** Emulation is paced by the audio output, so sound stays smooth.
  Fast-forward runs at 2×, 3×, 4×, 8× or unlimited speed.
- **Rumble** for rumble cartridges, optionally for all games.

### Controls

| Game Boy | Gamepad | Keyboard |
| --- | --- | --- |
| D-pad | D-pad / left stick | Arrow keys / WASD |
| A | B (east) | X / L |
| B | A (south) | Z / K |
| Start | Start | Enter |
| Select | Select | Backspace / Right Shift |
| Fast-forward (hold) | R1 / R2 | Space |
| Menu | Mode / Menu, or Back | Esc |

The face buttons are mapped by position, not by label. The right face button is A and the bottom
one is B, matching the Game Boy's layout.

## Building

Requirements: JDK 17 and the Android SDK. Gradle downloads the NDK and CMake if they're missing.

```sh
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # minified; signed with the debug key unless configured below
```

To sign release builds with your own key, set `ANDROIDBOY_KEYSTORE`, `ANDROIDBOY_KEYSTORE_PASSWORD`,
`ANDROIDBOY_KEY_ALIAS` and `ANDROIDBOY_KEY_PASSWORD`.

Every push is built by GitHub Actions (`.github/workflows/android.yml`). You can download the APKs
from the run's artifacts.

### Core smoke test

`tests/run_host_test.sh` compiles the SameBoy core and the app's emulator wrapper for your desktop.
It runs a small test cartridge (`tests/testrom.asm`) on every supported model. It checks boot ROM
loading, video, audio rate, joypad input, battery saves, save states and the SGB border. You only
need a C compiler.

## Project layout

```
sameboy/                  SameBoy 1.0.3: Core/ and BootROMs/ sources, unmodified
app/src/main/cpp/         Native code
  emulator.c/.h           Platform-independent wrapper around the core
  jni_bridge.c            JNI bindings, boot ROMs from assets, bitmap output
  CMakeLists.txt
app/src/main/java/...     The Android app (framework APIs only, no AndroidX)
  MainActivity            Game library, save import/export
  EmulatorActivity        Game screen, input, menu, save states
  EmulatorThread          Emulation loop, audio output and pacing
  ScreenView/GamepadView  Rendering and touch controls
app/src/main/assets/BootROMs/   Prebuilt SameBoy boot ROMs
tools/build_bootroms.sh   Rebuilds those from sameboy/BootROMs (needs RGBDS 0.7+)
tests/                    Host-side smoke test
```

To update SameBoy, replace `sameboy/Core`, `sameboy/BootROMs` and `sameboy/version.mk` with the new
release's copies. Then run `tools/build_bootroms.sh` and `tests/run_host_test.sh`.

## License

SameBoy is © Lior Halphon and licensed under the Expat (MIT) license; see `sameboy/LICENSE`.
The app shows the license under *About → Licenses*. Nothing from SameBoy's iOS directory is used,
so its extra distribution condition doesn't apply. The rest of this repository is released under
CC0 (see `LICENSE`).

Only play games you own.
