# PSXJ

PSXJ is a PlayStation emulator written in Java 21. Games are already playable, and compatibility and hardware accuracy are still improving.

The current hardware target is an NTSC-U SCPH-5501 (PU-18). The emulator uses a real BIOS and keeps the console implementation in a separate `core` module; the desktop application and native backends live in `gui`.

PSXJ is not affiliated with Sony. No BIOS, games, SDK files, keys, or other copyrighted console data are included. You need your own legally obtained BIOS and disc images.

## What is implemented

- R3000A interpreter, COP0, instruction cache, load/branch delays and exceptions
- GTE command set and CPU/GTE interlocks
- software GPU, VRAM transfers, texture cache, DMA and display timing
- 24-voice SPU, ADPCM, ADSR, interpolation, reverb, CD audio and XA audio
- CD-ROM controller with CUE/BIN and raw image support
- timers, MDEC, SIO, memory cards, digital pad, DualShock, mouse, NeGcon and lightguns
- lightweight FlatLaf/Swing frontend with native-style window controls, animated controls and AWT/SDL renderers
- vector region flags for NTSC-U, NTSC-J and PAL games, with a distinct unknown-region badge
- nine dated save-state slots and a display-resolution in-game overlay
- RetroAchievements login, achievement browser and unlock notifications
- automatically cached game covers and logos without API keys

## Requirements

- Java 21 or newer
- a 512 KiB PlayStation BIOS
- a game in CUE/BIN, BIN, ISO, IMG, EXE, PS-EXE or PSX format
- CMake and a C compiler when building from source (for the bundled RetroAchievements runtime)

CHD and M3U are not supported yet. Java and the native JAR must use the same CPU architecture.

## Running PSXJ

Build and start it from the repository:

```powershell
.\gradlew.bat
```

```shell
./gradlew
```

`run` is the default Gradle task, so `./gradlew run` does the same thing. On the first launch, select a BIOS, add a game folder, then choose a game and press **Boot**.

Closing PSXJ terminates the emulator process. When it was started through Gradle, a `GradleDaemon` process may remain available for later builds; it is not the emulator. Run `./gradlew --stop` if you also want to stop Gradle's build daemons.

Application data is stored in `~/.psxj/`. Memory cards and save states have their own subdirectories.

To build a runnable JAR:

```shell
./gradlew shadowJar
```

The result is written to `build/libs/psxj-<version>-<platform>.jar`. By default Gradle builds for the current machine. Cross-package with, for example:

```shell
./gradlew shadowJar -PnativeTarget=linux-arm64
```

Available targets are `windows-x64`, `windows-x86`, `windows-arm64`, `linux-x64`, `linux-arm32`, `linux-arm64`, `macos-x64`, and `macos-arm64`.

The first source build fetches the pinned `rcheevos 12.4.0` sources and compiles a small native bridge. If CMake is not on `PATH`, pass `-PcmakeExecutable=/path/to/cmake`. Cross-packaging needs a library built for the destination machine, supplied with `-PraNativeLibrary=/path/to/psxj_ra`.

## Controls

| PlayStation | Keyboard | Typical gamepad |
| --- | --- | --- |
| D-pad | Arrow keys | D-pad / left stick |
| Cross | X | A / Cross |
| Circle | S | B / Circle |
| Square | Z | X / Square |
| Triangle | A | Y / Triangle |
| L1 / R1 | Q / W | Shoulder buttons |
| L2 / R2 | 1 / 2 | Triggers |
| Start | Enter | Start / Options |
| Select | Space | Back / Create / Share |
| L3 / R3 | — | Stick clicks |

F5 opens the save-state section and F8 opens the load-state section in the slide-out panel on the left. Occupied slots show the save date, and the Achievements section lists earned state, points and cached badge icons. Pressing F5 or F8 while the drawer is already open switches section without replaying its animation. Escape closes the panel. The overlay is rendered at the window's native output resolution and uses a pointing-hand cursor over interactive items. By default it pauses emulation while open; this can be disabled under **Settings → Emulation**.

Memory cards are ordinary `.mcd` files under `~/.psxj/memcards/`. A completed sector write is flushed to disk immediately. Save states do not contain card images, so loading a state cannot roll a card back or erase a save made in-game.

## RetroAchievements

Open **Settings → RetroAchievements** to sign in. PSXJ sends the password only for the initial login and stores the returned account token in its local configuration; the password itself is not saved. When a supported game image is identified, achievements are evaluated on every emulated frame, including frames that are not presented during a slowdown. Unlocks appear as display-resolution cards after the badge has been loaded, accompanied by a short notification sound. The log reports the total, supported and already unlocked achievement counts after each game loads; lost connections are shown in the overlay while rcheevos queues submissions.

The integration uses the official [rcheevos runtime](https://github.com/RetroAchievements/rcheevos) and requires an internet connection for login, game data and unlock submission. PSXJ currently enables softcore achievements only. Hardcore mode will remain disabled until the emulator integration has been reviewed and accepted by RetroAchievements; save states therefore do not create a misleading hardcore session.

## Game artwork

The library downloads artwork automatically and does not require an account, token or settings page. Covers are resolved by disc serial through [xlenore's PSX cover collection](https://github.com/xlenore/psx-covers), with a filename fallback to the [Libretro PlayStation thumbnail set](https://github.com/libretro-thumbnails/Sony_-_PlayStation). The details panel uses Libretro's transparent game logo when one is available and otherwise shows the front cover. Successful downloads are cached under `~/.psxj/cache/game-media/` and remain available offline. Remote artwork belongs to its respective rightsholders and is not included in PSXJ releases.

## CPU overclock and controllers

The CPU percentage under **Settings → Emulation** can be changed while a game is running and has no artificial upper limit. It changes the number of R3000A cycles available per console hardware cycle: values above 100% help CPU-bound software but do not incorrectly accelerate the GPU, SPU, CD-ROM or timers. A game already synchronized to video at 100% will therefore not become fast-forwarded; this is hardware-style CPU overclocking, not a global speed multiplier.

Controllers are rediscovered every 250 ms, so an SDL-supported gamepad can be connected, disconnected or replaced during a running game. Connection changes are reported in the in-game overlay. Disabling vibration affects only rumble output and not controller input.

## Audio

OpenAL is the default backend; SDL is available in **Settings → Audio**. The default latency is 80 ms. Try 48–64 ms only when the game already holds full speed; use 80–120 ms on a busy machine or if the log reports an underrun.

Audio output runs on its own high-priority host thread. The SPU itself stays on the emulated clock because running it ahead of CPU writes, DMA and interrupts would change console behaviour. Sustained slowdowns down to roughly 80% are corrected with pitch-preserving time stretching rather than sample-rate conversion. The correction switches back to bit-exact PCM when it is no longer needed, so a temporary slowdown cannot leave the BIOS music permanently processed or heavier.

For a diagnostic log:

```shell
java -Dorg.slf4j.simpleLogger.log.psxj=debug \
     -Dorg.slf4j.simpleLogger.logFile=psxj-debug.log \
     -jar psxj-0.1.0-windows-x64.jar
```

Debug logging is expensive. Do not use it for performance measurements.

## JVM profiles

The default `balanced` profile is the right choice for most machines:

```shell
./gradlew run
```

Two alternatives are built in:

```shell
./gradlew run -PrunProfile=throughput
./gradlew run -PrunProfile=low-pause
```

| Profile | JVM | When to use it |
| --- | --- | --- |
| `balanced` | G1, 768 MiB–2 GiB heap | normal play; good throughput without reserving the full heap |
| `throughput` | Parallel GC, fixed 1.5 GiB heap | CPU-bound games on a machine with spare RAM; pauses can be longer |
| `low-pause` | generational ZGC, fixed 1.5 GiB heap | testing whether GC pauses contribute to audio or frame jitter |

Use `throughput` for the highest CPU headroom in geometry-heavy games. `low-pause` is useful when the average speed is already sufficient but the host occasionally pauses. Close CPU-heavy background programs before comparing profiles; the emulation clock is deliberately single-timeline even though presentation and audio output have their own threads.

Equivalent direct-launch commands are:

```shell
# Balanced
java -Xms768m -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=10 \
     -XX:+ParallelRefProcEnabled -jar psxj-0.1.0-windows-x64.jar

# Throughput
java -Xms1536m -Xmx1536m -XX:+UseParallelGC -XX:+AlwaysPreTouch \
     -jar psxj-0.1.0-windows-x64.jar

# Low pause on Java 21/22
java -Xms1536m -Xmx1536m -XX:+UseZGC -XX:+ZGenerational \
     -XX:+AlwaysPreTouch -jar psxj-0.1.0-windows-x64.jar
```

On Java 23 and newer, generational ZGC is already the default; omit `-XX:+ZGenerational`. `AlwaysPreTouch` makes startup slower and commits the whole heap immediately. Larger heaps and manually chosen GC thread counts usually do not help this emulator.

## Building and testing

The Gradle wrapper uses Gradle 8.14.5. The daemon, build cache, parallel execution, file-system watching and configuration cache are enabled. Avoid `clean` during normal development, since it throws away most of that work.

```shell
./gradlew test
./gradlew shadowJar
./gradlew jmh
```

The unit suite covers the CPU, COP0, GTE, bus, DMA, timers, GPU, SPU, CD-ROM, MDEC, SIO, memory cards and save states.

Local PS-X EXE conformance tests can be run without opening the GUI:

```powershell
.\gradlew.bat :core:runConformance `
  -PconformanceBios="C:\path\to\scph5501.bin" `
  -PconformanceExe="C:\path\to\test.exe" `
  -PconformanceSeconds=15 `
  -PconformanceOutput="build\diagnostics\result.png"
```

An optional input script can drive test menus, for example `-PconformanceInput="200:L1,300:DOWN,300:TRIANGLE,300:CROSS"`. Delays are in milliseconds.

Retail save states can also be profiled deterministically without opening a renderer or audio backend:

```powershell
.\gradlew.bat :core:profileState `
  -PprofileBios="C:\path\to\scph5501.bin" `
  -PprofileGame="C:\path\to\game.cue" `
  -PprofileState="C:\path\to\state.slot1.json" `
  -PprofileWarmupCycles=67737600 `
  -PprofileCycles=338688000 `
  -PprofileSampleCycles=33868800 `
  -PprofileStalls=true `
  -PprofileButtons=LEFT `
  -PprofileRecording="build\diagnostics\game.jfr"
```

The deterministic runner reports emulated speed and host FPS for each sample. `profileStalls` prints any host pause over 50 ms with the CPU PC, IRQ state, GC time and sampled CPU/GPU/CD/SPU/MDEC/DMA activity. `profileButtons` can hold one or more controls (`LEFT+L1`, for example) while reproducing a busy scene. Add `-PprofileStateHash=true` when validating that a scheduler or rendering optimization produces the same final machine state.

The same stall detector is enabled in normal emulation and writes `Emulation stall` records to the application log. Set `-Dpsxj.stallThresholdMs=100` to use a different threshold, or `-Dpsxj.stallDiagnostics=false` to disable it.

Some external tests are exhaustive rather than hung. In particular, AmiDog's full GTE suite can take hours on a real PlayStation. Run its `OFFICIAL / TIMING` group separately when checking command interlocks.

## Hardware references

- [Sony PlayStation Hardware overview](https://psx.arthus.net/sdk/Psy-Q/DOCS/Devrefs/Hardware.pdf)
- [PSX-SPX CPU](https://psx-spx.consoledev.net/cpuspecifications/)
- [PSX-SPX GTE](https://psx-spx.consoledev.net/geometrytransformationenginegte/) and [GTE pipeline timing](https://psx-spx.consoledev.net/gtepipelinetimings/)
- [PSX-SPX GPU](https://psx-spx.consoledev.net/graphicsprocessingunitgpu/), [DMA](https://psx-spx.consoledev.net/dmachannels/), [timers](https://psx-spx.consoledev.net/timers/) and [memory control](https://psx-spx.consoledev.net/memorycontrol/)
- [PSX-SPX SPU](https://psx-spx.consoledev.net/soundprocessingunitspu/), [CD-ROM](https://psx-spx.consoledev.net/cdromdrive/) and [SIO](https://psx-spx.consoledev.net/serialinterfacessio/)
- [JaCzekanski/ps1-tests](https://github.com/JaCzekanski/ps1-tests)

Hardware changes should come with a focused regression test and, where possible, a result from real hardware or a hardware-derived reference. A game booting is useful compatibility evidence, but it does not prove subsystem timing.

## Repository layout

```text
core/  console hardware, scheduler, image readers and host-independent APIs
gui/   desktop UI, configuration and AWT/SDL/OpenAL integrations
```

`core` does not depend on Swing, AWT, LWJGL, application settings or the game library. Frontend code talks to it through the audio, input and rendering interfaces in `core`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development notes. Please do not attach BIOS or game files to issues.

PSXJ uses the [PolyForm Noncommercial License 1.0.0](LICENSE). It is source-available, but it is not OSI open source and commercial use is not permitted by this license. Third-party notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
