# PandaGenie

AI-powered modular Android assistant.

PandaGenie turns natural-language requests into real actions on your phone. The Android app plans tasks with your selected LLM, discovers installed module capabilities at runtime, and executes the right module actions with visible permission and trace controls.

[Official Website](https://cf.pandagenie.ai) | [Module Marketplace](https://cf.pandagenie.ai/marketplace) | [Submit a Module](https://cf.pandagenie.ai/sign) | [Discord](https://discord.gg/Cfc7pjrjt2) | [中文说明](README_CN.md)

---

## Download

Latest Android release:

- Version: `v1.0.13`
- Release tag: [`20260427`](https://github.com/Rorschach123/PandaGenieSource/releases/tag/20260427)
- APK: [`PandaGenie-v1.0.13.apk`](https://github.com/Rorschach123/PandaGenieSource/releases/download/20260427/PandaGenie-v1.0.13.apk)

The release APK link above was verified against the current GitHub release asset. The app version in the Android project is `versionName "1.0.13"` / `versionCode 13`.

---

## What PandaGenie Does

PandaGenie is a module dispatcher for Android:

1. You describe a task in chat.
2. PandaGenie builds a prompt from the installed module manifests.
3. The selected LLM returns a structured plan.
4. The executor runs module actions step by step.
5. You can inspect task details, execution trace, permissions, inputs, and outputs.

Core ideas:

- Any OpenAI-compatible or supported LLM backend can drive task planning.
- Modules are hot-loadable `.mod` packages, so new capabilities do not require rebuilding the main app.
- Module manifests are the source of truth for AI-visible capabilities.
- Execution is designed around visibility, sandboxing, signatures, and user control.

---

## Current Repository Structure

This repository contains the public PandaGenie module ecosystem:

```text
PandaGenieSource/
├── README.md / README_CN.md        # English and Chinese project docs
├── CONTRIBUTING.md / CONTRIBUTING_CN.md
├── modules.json                    # Marketplace index, updated 2026-04-28
├── modules/                        # Signed release .mod packages
├── source/                         # Source code for official modules
│   ├── shared_api/                 # Shared module API and helper classes
│   ├── calculator/
│   ├── filemanager/
│   ├── archive/
│   └── ...                         # 37 official modules in total
├── module-dev-toolkit/             # PowerShell toolkit for building and signing modules
│   ├── mk_module.ps1
│   ├── init_dev_signing.ps1
│   ├── init_module_signing.ps1
│   ├── list_keystore_info.ps1
│   └── MODULE_DEVELOPMENT_GUIDE.md
├── docs/                           # Architecture and signing diagrams
├── tools/                          # Utility scripts
└── keys/                           # Local signing keys, ignored by git
```

Important structure changes reflected in this update:

- `modules.json` now lists 37 official modules and points each module to both the CDN download endpoint and GitHub raw `.mod` asset.
- `source/shared_api` is now the common API surface used by modules instead of duplicating helper contracts everywhere.
- `module-dev-toolkit` is the preferred local workflow for module packaging, signing, and keystore inspection.
- Newer official modules include Weather, OCR, Flashlight, Translator, Compass, URL Codec, and Hello World.
- Native-heavy modules such as File Manager, Archive, and Calculator keep their `native/` and `jni_bridge/` folders under the module source directory.

---

## Module Package Layout

A module source directory usually looks like this:

```text
source/my_module/
├── manifest.json                   # Capability metadata consumed by PandaGenie and the LLM
├── index.html                      # Optional H5 UI rendered by the app
├── plugin_src/                     # Java/Kotlin plugin implementation
├── native/                         # Optional native code
├── jni_bridge/                     # Optional JNI bridge
└── libs/                           # Optional local libraries, ignored when generated/downloaded
```

The compiled release artifact is a `.mod` file under `modules/`.

At runtime the app reads `manifest.json`, injects the module APIs into the task prompt, and calls the plugin entrypoint:

```java
public interface ModulePlugin {
    String invoke(Context context, String action, String paramsJson) throws Exception;
}
```

---

## Security Model

PandaGenie modules use a dual-signature distribution model:

| Layer | Purpose |
| --- | --- |
| Developer signature | Identifies the module author and binds the module to its manifest metadata. |
| Official signature | Confirms the module passed official review and can be trusted by production builds. |

The Android app also isolates module private storage:

- Java APIs remap module files and cache paths to per-module private directories.
- Native file access is restricted by the app-side sandbox layer for private app storage.
- Execution trace screens expose module calls, permissions, inputs, outputs, and timing for review.

---

## Official Modules

`modules.json` currently contains 37 official modules, last updated on `2026-04-28`.

| Module | Version | Description |
| --- | ---: | --- |
| Calculator | 1.3 | Scientific calculator with arithmetic, trigonometry, logarithms, factorials, combinations and expression parsing. |
| File Manager | 2.2 | Browse directories, create/copy/move/delete files, search files, and manage local storage. |
| Archive | 1.6 | ZIP, password ZIP, TAR, GZ, and TAR.GZ compression/extraction. |
| Signature Checker | 1.5 | Verify APK and module signatures, including official and developer signatures. |
| App Manager | 1.5 | List, launch, inspect, uninstall apps, and open Android app detail pages. |
| File Stats | 1.5 | File details, hashes, comparison, checksum verification, directory stats, duplicate and large-file scans. |
| Reminder | 1.4 | Calendar events, alarms, timers, birthday reminders, and upcoming schedule lookup. |
| Text Tools | 1.5 | Word count, Base64, URL encode/decode, regex, text transforms, UUIDs, and text hashes. |
| Device Info | 1.5 | Device, OS, CPU, RAM, storage, display, and public Android API summary. |
| Image Tools | 1.5 | Image information, resize, compression, format conversion, rotation, and cropping. |
| Clipboard Manager | 1.5 | Read, set, clear, and manage clipboard history. |
| Battery Manager | 1.5 | Battery level, charging state, health, temperature, voltage, and related status. |
| Network Tools | 1.5 | Ping, DNS lookup, local/public IP, connectivity checks, and network info. |
| Contacts Manager | 1.5 | Search, view, list, export, and find duplicate contacts. |
| Notes | 1.5 | Local note creation, view, edit, delete, search, and export. |
| Daily Fortune | 1.3 | Personalized fortune results by date and name, with lunar-calendar support. |
| Dice Tool | 1.2 | Dice rolls, target sums, big/small judgment, all-same rolls, combinations, and probability statistics. |
| LED Banner | 1.3 | Scrolling, fading, or static text banners with colors, gradients, font size, and effects. |
| System Cleaner | 1.6 | Scan and clean temporary files, caches, empty folders, thumbnails, and APK installers. |
| Color Tools | 1.4 | HEX/RGB/HSL/CMYK conversion, harmonious palettes, random colors, and CSS color lookup. |
| Unit Converter | 1.4 | Length, weight, temperature, area, volume, speed, time, data storage, and more. |
| Password Generator | 1.6 | Strong passwords, passphrases, custom complexity, and password strength checks. |
| QR Code Tools | 1.6 | Generate QR codes and decode QR codes from images. |
| Snake Game | 1.2 | Classic Snake with difficulty settings. |
| Farming Game | 1.2 | Plant, water, fertilize, weed, harvest, save records, and scheduled tasks. |
| Gomoku | 1.2 | 15x15 five-in-a-row game against AI. |
| Tetris Game | 1.2 | Classic Tetris with movement, rotation, row clearing, and difficulty settings. |
| Sudoku | 1.2 | 9x9 Sudoku with generated puzzles and difficulty settings. |
| Tic-Tac-Toe | 1.2 | Classic 3x3 game against AI. |
| Link Parser | 1.3 | Extract titles, descriptions, images, links, downloadable files, headers, and content types from URLs. |
| Weather Assistant | 1.4 | Current weather and multi-day forecasts through the Open-Meteo free API. |
| OCR Text Recognition | 1.3 | Extract Chinese and English text from images with automatic language detection. |
| Flashlight | 1.3 | Toggle and inspect the device camera torch state. |
| Translator | 1.4 | Translate text across Chinese, English, Japanese, Korean, French, German, Spanish, and more. |
| Digital Compass | 1.3 | Heading, azimuth, and cardinal direction from device sensors. |
| URL Codec | 1.2 | URL encoding, decoding, and component parsing. |
| Hello World | 1.1 | PandaGenie self-introduction and capability-list module for "who are you" style questions. |

Browse all modules in the [Module Marketplace](https://cf.pandagenie.ai/marketplace).

---

## Build and Publish Modules

The recommended local workflow is the module dev toolkit:

```powershell
cd PandaGenieSource
.\module-dev-toolkit\mk_module.ps1 -Module calculator
```

Common helper scripts:

```powershell
.\module-dev-toolkit\init_dev_signing.ps1
.\module-dev-toolkit\init_module_signing.ps1
.\module-dev-toolkit\list_keystore_info.ps1
```

For the full module workflow, read:

- [`module-dev-toolkit/MODULE_DEVELOPMENT_GUIDE.md`](module-dev-toolkit/MODULE_DEVELOPMENT_GUIDE.md)
- [`CONTRIBUTING.md`](CONTRIBUTING.md)

---

## Changelog

### v1.0.13 - 2026-04-27

- Updated the latest APK download to the `20260427` GitHub release asset.
- Refreshed module documentation for the current 37-module marketplace index.
- Documented recent module source structure changes, including `shared_api`, the module dev toolkit, and newer official modules.
- App-side work in this release cycle includes task action visibility fixes, scheduled execution condition dialogs, favorite de-duplication, refined welcome panda branding, and registration password confirmation.

### v1.0.12 - 2026-04-26

- Added the Hello World self-introduction module.
- Improved floating-window close behavior.
- Added clearer capability-boundary messaging.

### v1.0.11 - 2026-04-23

- Introduced the unified vector icon system.
- Removed decorative emoji from core user-facing UI strings.
- Merged permission prompts into cleaner authorization cards.
- Improved module deletion cleanup and prompt analytics.

---

## License

This repository is released under the license in [`LICENSE`](LICENSE).
