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
  There are 9 save-state slots, each showing a screenshot from when it was saved, plus an automatic
  "resume where you left off" state.
  You can import and export `.sav` files to move saves between devices and emulators.
- **Controls.** Multi-touch on-screen controls with haptic feedback: an 8-way d-pad and A/B, where
  sliding between A and B presses both. Buttons spring down and bounce back when released, and the
  d-pad rocks towards the direction you hold (turn off under Settings → Controls → Button
  animations; they also follow the system's animation setting). Physical gamepads and keyboards
  work too, and the touch controls hide while one is in use.
- **Skins.** Built-in themes styled after the original handhelds, with shaded Game Boy-style
  buttons, a moulded d-pad and slanted rubber Start/Select: Classic grey, Pocket silver, black, red
  and pink, Light gold, Gold edition, Super grey, Red & white, Berry, Grape, Kiwi, Dandelion, Teal,
  Ice blue, Atomic purple and Advance indigo. Modern ones too: OLED black (outlines), Neon, Pastel,
  Frosted glass, and a minimal translucent overlay. Three image skins come with the app (Midnight,
  Arcade and Woodgrain), and you can import your own as a `.zip` of images plus a layout file; see
  [docs/skins.md](docs/skins.md).
- **Screen filters.** All of SameBoy's filters run on the GPU (OpenGL ES 3.0): LCD, monochrome LCD,
  CRT, flat CRT, bilinear, Scale2x/4x, HQ2x, OmniScale and more. There's also SameBoy's frame
  blending, which some games rely on for flicker transparency.
- **Display.** Fit-to-screen or integer-only scaling. Also SameBoy's color correction modes,
  monochrome palettes and Super Game Boy borders.
- **Audio.** Emulation is paced by the audio output, so sound stays smooth.
  Fast-forward runs at 2×, 3×, 4×, 8× or unlimited speed.
- **Rewind.** Hold rewind to play backwards through the last 30 seconds (adjustable from 10 seconds
  to 5 minutes, or off), using SameBoy's rewind.
- **Settings** are grouped into Display, Emulation, Controls and Sound.
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
| Rewind (hold) | L1 / L2 | R |
| Menu | Mode / Menu, or Back | Esc |

The built-in themes also have on-screen rewind and fast-forward buttons next to the menu button.
Custom skins can include them too.

The face buttons are mapped by position, not by label. The right face button is A and the bottom
one is B, matching the Game Boy's layout.

## Building

Requirements: JDK 21 (17 works for building, but the skin tests need 21) and the Android SDK. Gradle downloads the NDK and CMake if they're missing.

```sh
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # minified; signed with the shared debug key unless configured below
```

To sign a local release build with your own key, set `ANDROIDBOY_KEYSTORE` (the keystore's path),
`ANDROIDBOY_KEYSTORE_PASSWORD` and `ANDROIDBOY_KEY_ALIAS`. Also set `ANDROIDBOY_KEY_PASSWORD` if the
key's password differs from the keystore's.

Every push is built by GitHub Actions (`.github/workflows/android.yml`). You can download the APKs
from the run's artifacts. Those builds are signed with the shared debug key (see below).

### Releases

`.github/workflows/release.yml` runs on every push to `main`. If the `versionName` in
`app/build.gradle` has no GitHub release yet, it builds the APK, signs it (see below), and
publishes a release tagged `v<versionName>`. To publish a new release, bump `versionName`
(and `versionCode`) and merge to `main`.

### Signing releases

Releases must be signed with the same key every time, or Android won't install them as updates.

By default, every build and release is signed with the shared debug key in `app/debug.keystore`
(password `android`, alias `androiddebugkey`). It's committed on purpose, so local builds, CI builds
and releases all share one signature and install over each other. It's public, so anyone could sign
an APK that installs as an update of yours. That's the usual trade-off for sideloaded open-source
apps. The key never goes inside the APK; like every signed app, the APK only carries the matching
public certificate. The release workflow checks that the APK was signed with exactly this key.

To use your own private key instead, keep it in the repository's Actions secrets. Switching keys
means users uninstall once (after exporting their saves), because Android refuses updates signed
with a different key.

1. Generate the key on your own computer. `keytool` comes with any JDK.

   ```sh
   keytool -genkeypair -keystore androidboy-release.jks -alias androidboy \
       -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=AndroidBoy"
   ```

   Pick a strong password when prompted.

2. Back up `androidboy-release.jks` and its password somewhere safe, such as a password manager.
   Don't commit it. If you lose it, you can't publish updates that install over existing copies.

3. Add these secrets under **Settings → Secrets and variables → Actions → New repository secret**:

   | Secret | Value |
   | --- | --- |
   | `ANDROIDBOY_KEYSTORE_BASE64` | Output of `base64 -w0 androidboy-release.jks` (macOS: `base64 -i androidboy-release.jks`) |
   | `ANDROIDBOY_KEYSTORE_PASSWORD` | The password you chose |
   | `ANDROIDBOY_KEY_ALIAS` | `androidboy` |

   With the [GitHub CLI](https://cli.github.com), you can run instead:

   ```sh
   base64 -w0 androidboy-release.jks | gh secret set ANDROIDBOY_KEYSTORE_BASE64
   gh secret set ANDROIDBOY_KEYSTORE_PASSWORD
   gh secret set ANDROIDBOY_KEY_ALIAS --body androidboy
   ```

Each release's notes include the signing certificate's SHA-256 fingerprint.

### Tests

`./gradlew testDebugUnitTest` runs the skin tests, including the press animations. CI uploads
their preview images, and frame-by-frame strips of a press and release, as the `skin-previews`
artifact.

`tests/run_host_test.sh` compiles the SameBoy core and the app's emulator wrapper for your desktop.
It runs a small test cartridge (`tests/testrom.asm`) on every supported model. It checks boot ROM
loading, video, audio rate, joypad input, battery saves, save states, the SGB border, rewind, and
the frame parity used for frame blending. You only need a C compiler.

## Project layout

```
sameboy/                  SameBoy 1.0.3: Core/ and BootROMs/ sources, unmodified
app/src/main/cpp/         Native code
  emulator.c/.h           Platform-independent wrapper around the core
  jni_bridge.c            JNI bindings, boot ROMs from assets, frame output
  CMakeLists.txt
app/src/main/java/...     The Android app (framework APIs only, no AndroidX)
  MainActivity            Game library, save import/export
  EmulatorActivity        Game screen, input, menu, save states
  EmulatorThread          Emulation loop, audio output and pacing
  GlScreenView            OpenGL ES 3 renderer running SameBoy's filters
  CanvasScreenView        Fallback renderer for devices without OpenGL ES 3
  Skin, ThemeSkin         Skin layout and drawing; the built-in themes
  ImageSkin, SkinLibrary  Imported skins: loading, validation, zip import
  SkinView                Draws the active skin and handles touch
app/src/main/assets/BootROMs/   Prebuilt SameBoy boot ROMs
app/src/main/assets/shaders/    SameBoy's filter shaders (unmodified) and the GLES master shader
app/src/main/assets/skins/      Image skins bundled with the app
app/src/test/             Robolectric tests for skins (layout, touch, import); write previews
                          to app/build/skin-previews
docs/skins.md             The skin file format; docs/skins/example is a complete example
tools/build_bootroms.sh   Rebuilds the boot ROMs from sameboy/BootROMs (needs RGBDS 0.7+)
tools/make_skins.py       Draws the example skin and the bundled image skins (needs Pillow)
tests/                    Host-side smoke test for the core wrapper
```

To update SameBoy, replace `sameboy/Core`, `sameboy/BootROMs` and `sameboy/version.mk` with the new
release's copies, and copy its `Shaders/*.fsh` files (except `MasterShader.fsh`) into
`app/src/main/assets/shaders/`. Then run `tools/build_bootroms.sh`, `tests/run_host_test.sh` and
`./gradlew testDebugUnitTest`.

## License

SameBoy is © Lior Halphon and licensed under the Expat (MIT) license; see `sameboy/LICENSE`.
That includes the filter shaders in `app/src/main/assets/shaders/`, and `Master.glsl` there is a port
of SameBoy's master shader. The app shows the license under *About → Licenses*. Nothing from SameBoy's iOS directory is used,
so its extra distribution condition doesn't apply. The rest of this repository is released under
CC0 (see `LICENSE`).

Only play games you own.
