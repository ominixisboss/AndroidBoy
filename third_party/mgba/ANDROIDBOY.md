# mGBA in AndroidBoy

This is [mGBA](https://mgba.io) 0.10.5 (tag `0.10.5`, commit 26b7884), by Jeffrey Pfau and
contributors, under the Mozilla Public License 2.0 (see `LICENSE`). AndroidBoy uses its Game Boy
Advance core for GBA games; Game Boy and Game Boy Color games stay on SameBoy.

The files are unmodified. To keep the repository small, only what a `LIBMGBA_ONLY` build of the
GBA core needs is kept:

- `CMakeLists.txt`, `version.cmake`, `LICENSE`, `README.md`, `include/`
- `src/arm`, `src/core`, `src/feature`, `src/gb`, `src/gba`, `src/util`, `src/sm83`,
  `src/debugger`, `src/script` (the last four are referenced by the build files, not compiled)
- `src/platform/posix`, `src/platform/cmake`, `src/platform/test`, `src/platform/video-backend.h`
- `src/third-party/blip_buf`, `src/third-party/inih`

To update, check out a new tag of https://github.com/mgba-emu/mgba and copy the same paths.
The app builds it from `app/src/main/cpp/CMakeLists.txt`; the host test from
`tests/mgba_host/CMakeLists.txt`.
