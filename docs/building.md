# Building Ludolog Link

This repository builds against the other two, cloned next to it:

```
ludolog-front-end/   https://github.com/monkikolab/ludolog-front-end
ludolog-assets/      https://github.com/monkikolab/ludolog-assets
ludolog-link/        this repository
```

(Other locations: `-PludologFrontEnd=<path>` and `-PludologAssets=<path>`.)
Requirements: JDK 21, and the Android SDK for the device app.

```bash
cd console && ./gradlew assembleDebug        # Android app
cd console && ./gradlew assembleDev          # Link Dev, next to the official Link
cd pc && ./gradlew createDistributable       # Windows app, to try it
cd pc && ./gradlew buildRelease              # Windows installer (.msi) and portable .zip
```

**Link Dev** (package `com.felp.ludologlink.dev`) is for testing on devices that keep the official
apps. It talks to Ludolog Dev instead of Ludolog, listens on its own ports (the official ones plus
10) and keeps its save backups in `LudologLinkDev`, so it only finds other Link Dev devices. The PC
app does the same when started with `LUDOLOG_LINK_DEV=1`, and then keeps its settings and data in
`%APPDATA%\LudologLinkDev`.

The version comes from `version.properties` in ludolog-front-end, shared by all three apps. The
Android app must be signed with the same key as Ludolog: its release build reads the same
`keystore.properties` (from `console/` or from ludolog-front-end).

## What's where

- `console/`: the Android app.
- `pc/`: the Windows app (Compose Desktop). It compiles some of Ludolog's own source files (the
  Companion, the catalog, the scraper) straight from ludolog-front-end; see
  [how Ludolog and Ludolog Link work together](https://github.com/monkikolab/ludolog-front-end/blob/main/docs/ludolog-link.md).
- `kit/`: code shared by both apps (protocol and look).
- `docs/`: the [user guide](manual.md) and the [known limitations](limitations.md).
