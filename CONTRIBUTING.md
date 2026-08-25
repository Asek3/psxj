# Contributing to PSXJ

Thank you for helping improve PSXJ.

## Before opening a change

- Keep all code and tests original or clearly compatible with the project license.
- Do not submit BIOS, game, Sony SDK, leaked documentation, keys, or copyrighted assets.
- Do not paste code from another emulator merely because its repository is visible. Record the behavioral fact being implemented and write a fresh implementation.
- Keep hardware accuracy separate from frontend enhancements and host-side optimizations.

## Development

Use JDK 21 or newer. Packaging and the default `run` task also need CMake and a C compiler for the pinned rcheevos runtime. Run:

```shell
./gradlew test --stacktrace
```

The daemon, incremental compiler, build cache, and configuration cache are part of the normal development workflow. Use `clean` only to diagnose stale generated output or for an intentionally pristine release build.

On Windows, use `.\gradlew.bat`.

For an emulation fix, add the smallest regression test that fails before the change. State which observable PSX behavior is covered and how it was verified. Do not weaken timing, exception, save-state, or cache tests to make a change pass.

## Pull requests

A pull request should:

- explain the user-visible or hardware-visible outcome;
- list the tests run;
- identify compatibility and performance risk;
- avoid unrelated formatting or generated-file churn;
- update the README when architectural coverage or a known limitation changes.

The project uses PolyForm Noncommercial 1.0.0. By contributing, you agree that your contribution may be distributed under that license.
