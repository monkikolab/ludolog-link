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
cd pc && ./gradlew createDistributable       # Windows app, to try it
cd pc && ./gradlew buildRelease              # Windows installer (.msi) and portable .zip
```

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
