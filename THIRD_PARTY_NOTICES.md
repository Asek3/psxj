# Third-party notices

PSXJ depends on the following third-party projects. They are not relicensed under the PSXJ license:

| Component | Use | License |
| --- | --- | --- |
| [LWJGL](https://www.lwjgl.org/) and LWJGL SDL bindings | Native OpenAL, SDL, graphics, memory, and platform bindings | BSD 3-Clause |
| [SDL](https://www.libsdl.org/) | Window, renderer, audio, gamepad, joystick, and rumble APIs through LWJGL | zlib License |
| [OpenAL Soft 1.25.2](https://github.com/kcat/openal-soft/tree/1.25.2) | OpenAL implementation distributed in LWJGL 3.4.2 native artifacts | GNU Library General Public License 2.0 or later; bundled HRTF data under Apache License 2.0 |
| [FlatLaf](https://www.formdev.com/flatlaf/) | Swing look and feel | Apache License 2.0 |
| [Gson](https://github.com/google/gson) | Configuration and save-state JSON | Apache License 2.0 |
| [JNA 5.19.1](https://github.com/java-native-access/jna) | Java access to the bundled achievement runtime | Apache License 2.0 or LGPL 2.1-or-later; PSXJ uses the Apache License 2.0 option |
| [rcheevos 12.4.0](https://github.com/RetroAchievements/rcheevos/tree/v12.4.0) | RetroAchievements game hashing, client protocol and achievement evaluation | MIT License |
| [Kenney Interface Sounds](https://kenney.nl/assets/interface-sounds) (`confirmation_001.wav`) | Achievement unlock notification sound | CC0 1.0 Universal |
| [Error Prone annotations](https://github.com/google/error-prone) | Runtime annotations pulled transitively by Gson | Apache License 2.0 |
| [SLF4J](https://www.slf4j.org/) | Logging API and simple runtime backend | MIT License |
| [JUnit 5](https://junit.org/junit5/) | Test framework; not included as a runtime dependency | Eclipse Public License 2.0 |
| [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html) | Reproducible build bootstrap scripts and wrapper JAR | Apache License 2.0 |
| [Shadow Gradle Plugin](https://gradleup.com/shadow/) | Build-time self-contained JAR packaging | Apache License 2.0 |

Runtime artifacts embed this notice and the applicable terms or canonical license references from [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md). Upstream projects retain their own copyrights, trademarks, and license terms.
