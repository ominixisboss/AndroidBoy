# AndroidBoy

A standalone Game Boy and Game Boy Color emulator for Android, built on the
[SameBoy](https://sameboy.github.io) 1.0.3 core by Lior Halphon.

It doesn't need RetroArch or any other frontend: you install one APK, add your ROMs, and play.

## Features

- **SameBoy's accurate core.** Emulates the Game Boy, Game Boy Pocket, Super Game Boy (1 and 2),
  Game Boy Color and Game Boy Advance. By default, Game Boy games run on a Game Boy Color, as in SameBoy.
- **No BIOS files needed.** SameBoy's own open-source boot ROMs are built in, so no Nintendo code is included.
- **Game library.** Add `.gb`/`.gbc` ROMs, or `.zip` files containing them, from any storage provider.
  You can also use "Open with" from a file manager. Favourites and recently played games get their
  own sections, and each game shows its box art and when you last played it. Long-press a game to
  favourite it or add it to your home screen; long-pressing the app icon offers the last few games.
- **Box art** comes from the [libretro thumbnail collection](https://github.com/libretro-thumbnails)
  (the one RetroArch shows). A game is identified by its checksum in No-Intro's list of known
  cartridges, which gives the exact name its picture is filed under; failing that, by its file
  name. You can pick your own picture, and turn downloading off under Settings → Game list.
- **Backup & restore** (main menu) puts saves, save states, cheats, imported skins, box art,
  settings and favourites (and the games, if you like) in one zip, to keep or move to a new phone.
  The RetroAchievements login is never included.
- **Saving.** Battery saves (including the RTC) are written automatically every few seconds.
  There are 9 save-state slots, each showing a screenshot from when it was saved, plus an automatic
  "resume where you left off" state.
  You can import and export `.sav` files to move saves between devices and emulators.
- **Controls.** Multi-touch on-screen controls with haptic feedback: an 8-way d-pad and A/B, where
  sliding between A and B presses both. Buttons spring down and bounce back when released, and the
  d-pad rocks towards the direction you hold (turn off under Settings → Controls → Button
  animations; they also follow the system's animation setting). Physical gamepads and keyboards
  work too, and the touch controls hide while one is in use. **Move on-screen buttons** (in-game
  menu) lets you drag the buttons anywhere and pinch to resize them, for each skin in portrait and
  landscape. **Controller buttons…** lets you choose which button does what.
- **Turbo.** The in-game menu's **Turbo buttons…** makes on-screen A and/or B fire repeatedly
  while held; on a controller, Y is turbo A and X is turbo B. The speed is a setting.
- **Slow motion** at 75%, 50% or 25% speed, from the in-game menu.
- **Screenshots** (in-game menu) are saved to Pictures/AndroidBoy at 4× with sharp pixels.
- **Soft skins** (Slate, Lilac, Sage, Blush, Sand, Ocean, Charcoal and Midnight): a plain, lightly
  textured background with big pastel controls outlined in dark ink, the game screen edge to edge,
  and **toolbars** instead of buttons on the skin. The top bar has back, the menu, achievements,
  the link cable, a screenshot button and a rotation lock, with the game's name under it; the
  bottom bar has the speed (slow motion, normal, fast-forward), pause, rewind (hold), sound and
  full screen (a small button in the corner brings the bars back). Settings → Controls → Toolbars
  shows them with every skin, or never.
- **Game Menu.** The in-game menu fills the screen, grouped into Quick access, Input, Display,
  Speed, System and More, each item with an icon and a line about what it does.
- **Skins.** Built-in themes styled after the original handhelds, with shaded Game Boy-style
  buttons, a moulded d-pad and slanted rubber Start/Select: Classic grey, Pocket silver, black, red
  and pink, Light gold, Gold edition, Super grey, Red & white, Berry, Grape, Kiwi, Dandelion, Teal,
  Ice blue, Coral, Mint, Lime, Sakura, Navy & gold, Sunset, Atomic purple and Advance indigo.
  Modern ones too: OLED black, Terminal green and Amber terminal (outlines), Neon, Synthwave,
  Vaporwave, Pastel, Frosted and Smoke glass, and a minimal translucent overlay. Eleven image skins
  come with the app (Midnight, Arcade, Woodgrain, Space, Camo, Candy, Carbon, Ocean, Lava, Pixel
  and Marble), and you can import your own as a `.zip` of images plus a layout file;
  see [docs/skins.md](docs/skins.md). The skin picker groups them into Soft, Game Boy classics,
  Colours, Modern & minimal, Artwork (the image skins) and Imported.
- **Screen filters.** All of SameBoy's filters run on the GPU (OpenGL ES 3.0): LCD, monochrome LCD,
  CRT, flat CRT, bilinear, Scale2x/4x, HQ2x, OmniScale and more. There's also SameBoy's frame
  blending, which some games rely on for flicker transparency.
- **Display.** Fit-to-screen or integer-only scaling. Also SameBoy's color correction modes,
  monochrome palettes and Super Game Boy borders.
- **Audio.** Emulation is paced by the audio output, so sound stays smooth.
  Fast-forward runs at 2×, 3×, 4×, 8× or unlimited speed.
- **Rewind.** Hold rewind to play backwards through the last 30 seconds (adjustable from 10 seconds
  to 5 minutes, or off), using SameBoy's rewind.
- **RetroAchievements.** Log in with a free [retroachievements.org](https://retroachievements.org)
  account (main menu → RetroAchievements) and games with achievement sets unlock them as you play,
  with a banner and the badge. The in-game menu's **Achievements…** lists the game's achievements
  and your progress. **Hardcore mode** counts unlocks as hardcore; loading save states (including
  the automatic resume), rewinding and cheats are off while it's on. Achievement progress is saved with
  each save state. Only a login token is kept on the device, never your password, and it's left
  out of backups. The **RetroAchievements** page in the main menu shows your points and every
  Game Boy and Game Boy Color game you've unlocked achievements in, with your progress; tap a game
  to open it on the website. In a game, the achievements list shows what the game says you're doing
  (rich presence, which is also shown on your profile) and its **Leaderboards**, each with the top
  ten and the players around you. During a leaderboard attempt its live score or time sits in the
  corner, as does an achievement's progress when it changes.
- **Free homebrew games.** Main menu → **Free homebrew games** browses and searches
  [Homebrew Hub](https://hh.gbdev.io), the gbdev community's archive of freely distributed
  homebrew games, demos and music for Game Boy and Game Boy Color. Pick one to read about it, then
  download it straight into your library. ROM hacks of commercial games aren't listed.
- **Cheats.** The in-game menu's **Cheats…** holds each game's GameShark and Game Genie codes:
  type them in, or **Find online** to search the [libretro cheat database](https://github.com/libretro/libretro-database)
  (the one RetroArch uses) and pick the cheats you want. Tap a cheat to switch it on or off;
  long-press to edit or delete it. Cheats are saved per game in RetroArch's `.cht` format, and
  are off while RetroAchievements hardcore mode is on. **Cheat search…** finds where a game keeps a
  number (lives, money, health): start a search, play until the number changes, say how it changed,
  and repeat until a few addresses are left; tap one to make a cheat that holds it at a value.
- **Link cable.** The in-game menu's **Link cable…** connects two Game Boys, for trading and
  battling:
  - **Another game on this phone**, e.g. to trade between your own Red and Blue saves. Both games
    run, linked; switch between them from the menu.
  - **Two phones on the same Wi-Fi.** One hosts, the other joins (they find each other, or you
    type in the address the host shows). Each phone runs both Game Boys in lockstep and only the
    players' buttons cross the network, so the link's timing is exact however slow the Wi-Fi; the
    hosting phone sets both games up and sends the other the exact state of both, so they stay in
    step. Buttons take effect 4 frames (about 70 ms) after you press them. Each phone keeps its
    own game's save.

  While linked, save states, reset, rewind and (between phones) cheats are off, as they'd put the
  two Game Boys out of step.
- **Game Boy Printer.** The in-game menu's **Link port…** plugs in a Game Boy Printer; printouts
  (Pokédex entries, Game Boy Camera pictures…) are saved to Pictures/AndroidBoy.
- **Game Boy Camera.** A Game Boy Camera cartridge sees through your phone's camera (front or back,
  switchable from the menu). The camera permission is only asked for when one is running.
- **Settings** are grouped into Display, Emulation, Controls, Sound and Game list.
- **Rumble** for rumble cartridges, optionally for all games.

### Controls

| Game Boy | Gamepad | Keyboard |
| --- | --- | --- |
| D-pad | D-pad / left stick | Arrow keys / WASD |
| A | B (east) | X / L |
| B | A (south) | Z / K |
| Start | Start | Enter |
| Select | Select | Backspace / Right Shift |
| Turbo A / turbo B | Y / X | |
| Fast-forward (hold) | R1 / R2 | Space |
| Rewind (hold) | L1 / L2 | R |
| Menu | Mode / Menu, or Back | Esc |

The built-in themes also have on-screen rewind and fast-forward buttons next to the menu button.
Custom skins can include them too.

The face buttons are mapped by position, not by label. The right face button is A and the bottom
one is B, matching the Game Boy's layout. All of these can be changed with **Controller buttons…**
in the in-game menu.

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

`./gradlew testDebugUnitTest` runs the Robolectric tests: skins (including the press animations),
cheats and cheat search, box art matching, backups, controls, the camera and printer images, and
the two-phone link protocol over a real socket. CI uploads the skin tests' preview images, and
frame-by-frame strips of a press and release, as the `skin-previews` artifact.

`tests/run_host_test.sh` compiles the SameBoy core and the app's emulator wrapper for your desktop.
It runs a small test cartridge (`tests/testrom.asm`) on every supported model. It checks boot ROM
loading, video, audio rate, joypad input, battery saves, save states, the SGB border, rewind, and
the frame parity used for frame blending, and GameShark and Game Genie cheats. Two more cartridges
test accessories: `tests/printrom.asm` prints on the Game Boy Printer, and `tests/linkrom.asm`
passes bytes over the link cable between a Game Boy and a Game Boy Color, which must then run
identically from saved states with the same buttons (what two linked phones rely on). The ROMs are
checked in; each `.asm` says how to rebuild it with RGBDS. It also runs rcheevos against the
emulator with a stand-in server (`tests/achievements_test.c`): logging in, identifying the
cartridge by its MD5, unlocking achievements from what the game writes to memory, rich presence,
leaderboards, and fetching your progress for the achievements page. You only need a C compiler.

## Project layout

```
sameboy/                  SameBoy 1.0.3: Core/ and BootROMs/ sources, unmodified
third_party/rcheevos/     rcheevos 12.5.0 (RetroAchievements client library, MIT), unmodified
app/src/main/cpp/         Native code
  emulator.c/.h           Platform-independent wrapper around the core
  jni_bridge.c            JNI bindings, boot ROMs from assets, frame output
  achievements.c          RetroAchievements: rcheevos client, memory map, web requests via Java
  CMakeLists.txt
app/src/main/java/...     The Android app (framework APIs only, no AndroidX)
  MainActivity            Game library, save import/export
  EmulatorActivity        Game screen, input, menu, save states
  EmulatorThread          Emulation loop, audio output and pacing
  GlScreenView            OpenGL ES 3 renderer running SameBoy's filters
  CanvasScreenView        Fallback renderer for devices without OpenGL ES 3
  Skin, ThemeSkin         Skin layout and drawing; the built-in themes
  SoftSkin                The Soft skins
  GameToolbars,           The game screen's toolbars and full-screen menu; their icons
    GameMenuView, Icons
  ImageSkin, SkinLibrary  Imported skins: loading, validation, zip import
  SkinView                Draws the active skin and handles touch
  Achievements            RetroAchievements login, web requests and events; the banner and screens
  AchievementsActivity    Your RetroAchievements points and progress in each game
  Homebrew, HomebrewActivity  Homebrew Hub search, entry details and downloads
  GameStore, BoxArt       Favourites and recently played; box art matching and downloads
  Backup                  Backup and restore
  ControllerMapping,      Controller buttons; where the on-screen buttons are
    ControlLayout
  CheatSearch             Finding a number in RAM and turning it into a cheat
  CameraFeed              The phone camera as the Game Boy Camera's sensor
  Gallery                 Saving screenshots and printouts to Pictures
  LinkSession, NetLink,   The link cable: two games on one phone, or two phones over the network
    LinkDialogs
  Cheat, CheatDatabase,   Cheat codes and .cht files; the libretro cheat database;
    CheatDialogs          the in-game cheat screens
app/src/main/assets/BootROMs/   Prebuilt SameBoy boot ROMs
app/src/main/assets/shaders/    SameBoy's filter shaders (unmodified) and the GLES master shader
app/src/main/assets/skins/      Image skins bundled with the app
app/src/test/             Robolectric tests for skins (layout, touch, import), achievements and
                          Homebrew Hub parsing, and cheats; skin tests write previews
                          to app/build/skin-previews
docs/skins.md             The skin file format; docs/skins/example is a complete example
tools/build_bootroms.sh   Rebuilds the boot ROMs from sameboy/BootROMs (needs RGBDS 0.7+)
tools/make_skins.py       Draws the example skin and the bundled image skins (needs Pillow)
tests/                    Host-side tests for the core wrapper, with test cartridges (link, printer)
```

To update SameBoy, replace `sameboy/Core`, `sameboy/BootROMs` and `sameboy/version.mk` with the new
release's copies, and copy its `Shaders/*.fsh` files (except `MasterShader.fsh`) into
`app/src/main/assets/shaders/`. Then run `tools/build_bootroms.sh`, `tests/run_host_test.sh` and
`./gradlew testDebugUnitTest`.

## License

SameBoy is © Lior Halphon and licensed under the Expat (MIT) license; see `sameboy/LICENSE`.
That includes the filter shaders in `app/src/main/assets/shaders/`, and `Master.glsl` there is a port
of SameBoy's master shader. rcheevos (`third_party/rcheevos`) is © RetroAchievements.org, MIT license.
The app shows both licenses under *About → Licenses*. Nothing from SameBoy's iOS directory is used,
so its extra distribution condition doesn't apply. The rest of this repository is released under
CC0 (see `LICENSE`).

Only play games you own.
