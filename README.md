<div align="center">

# PandaGenie

**AI-powered modular Android assistant**

Tell PandaGenie what you need in natural language. It plans, executes, and returns results through your LLM and hot-loadable Android modules.

[Official Website](https://cf.pandagenie.ai) | [Task & Module Marketplace](https://cf.pandagenie.ai/marketplace) | [PandaGenieSDK](https://github.com/Rorschach123/PandaGenieSDK) | [Submit a Module](https://cf.pandagenie.ai/sign) | [Discord](https://discord.gg/Cfc7pjrjt2) | [Chinese](README_CN.md)

</div>

---

## PandaGenie Ecosystem

PandaGenie is split into a few related projects so app users, module developers, and Android app developers can start from the right place.

| Project | What it is for |
|---|---|
| [Official Website](https://cf.pandagenie.ai) | APK download, task/module marketplace, SDK registration, developer docs, and publishing entry points. |
| [PandaGenieSource](https://github.com/Rorschach123/PandaGenieSource) | This repository: Android app source, official module source, packaging scripts, and module catalog metadata. |
| [PandaGenieSDK](https://github.com/Rorschach123/PandaGenieSDK) | Android AAR for app-to-app capability discovery and invocation. Use this when another Android app wants to expose functions to PandaGenie or build an AI assistant that calls approved apps. |
| [PandaGenie Module Template](https://github.com/Rorschach123/PandaGenie-Module-Template) | Starter project for hot-loadable PandaGenie modules. Use this when you want to add a new module inside PandaGenie. |
| [Module Submission](https://cf.pandagenie.ai/sign) | Upload and sign modules for the official module workflow. |
| [SDK Registration](https://cf.pandagenie.ai/sdk) | Register an Android app package/signature/role so it can participate in PandaGenieSDK calls. |
| [Discord](https://discord.gg/Cfc7pjrjt2) | Developer support, module publishing help, SDK review questions, and feedback. |

Quick path:

- **I want to try the app**: download the APK from the [official website](https://cf.pandagenie.ai).
- **I want to write a PandaGenie module**: start from the [module template](https://github.com/Rorschach123/PandaGenie-Module-Template), then submit it through [Module Submission](https://cf.pandagenie.ai/sign).
- **I want my Android app to be callable by PandaGenie**: integrate [PandaGenieSDK](https://github.com/Rorschach123/PandaGenieSDK), expose provider capabilities, then register the release package and signature on [SDK Registration](https://cf.pandagenie.ai/sdk).
- **I want to build another AI assistant**: integrate PandaGenieSDK as an Agent app, register the agent role, discover approved providers, and feed their capability manifests into your planner.

## 1.0.35 Highlight: On-Device Memory

PandaGenie 1.0.35 introduces app-level on-device memory. It is not a normal module; it is a data-layer capability wired into chat, Agent mode, LLM requests, and settings so the assistant can carry preferences, task habits, corrections, and recurring context across conversations.

- **Three modes**: choose Full app memory, Private memory, or Memory off in the Chat & App Memory settings card.
- **Local first**: memory is stored in app-private storage at `app_memory/memories.json`. It is not cloud-synced and is not imported/exported; clearing local app data removes it.
- **Retrieval injection**: `AppMemoryStore` retrieves up to 8 relevant memories for the current query and keeps the prompt budget around 1800 characters before appending them to LLM and Agent system prompts.
- **Privacy boundaries**: API keys, tokens, passwords, phone numbers, email addresses, and ID numbers are detected locally. Full mode stores sensitive values in the local Vault and sends only `{{vault:name}}` variables to the model; Private mode keeps redacted context only; Off mode does not extract, store, or inject memory.
- **Lifecycle control**: short-term memories are retained for 30 days by default, explicit preferences are prioritized, and the local store is capped at 500 deduplicated entries.

## Memory Implementation

| Layer | Implementation | Notes |
|---|---|---|
| Settings | `SettingsActivity` / `SettingsDataStore` | Exposes and persists the three memory modes. |
| Storage and retrieval | `data/memory/AppMemoryStore.kt` | Handles writes, dedupe, tags, retention, ranking, and prompt construction. |
| Sensitive values | `SecureVaultStore` + `MemoryVaultVariables` | Full mode stores sensitive values under Vault category `app_memory_sensitive`; prompts only receive variable names. |
| Chat integration | `ChatViewModel` | Remembers user/assistant turns and prepares safe text, history, and memory context before dispatch. |
| LLM and Agent injection | `ChatTaskService` | Appends `memoryContext` to both regular LLM calls and Agent-mode system prompts. |
| Module boundary | App-layer capability | Third-party modules cannot directly read the full memory store; memory only guides AI planning and does not change module permissions. |

## Current Module Catalog

`modules.json` currently lists **52 official modules** with **333 APIs**, last updated on **2026-05-15**.

| ID | Module | Description | Version | APIs |
|---|---|---|---|---|
| `calculator` | Calculator | Scientific calculator with arithmetic, trigonometry, logarithms, factorials, combinations and expression parsing | 1.3 | 17 |
| `filemanager` | File Manager | File manager with directory browsing, file CRUD, search and more | 2.3 | 13 |
| `archive` | Archive | Archive module supporting ZIP (with password), TAR, GZ, TAR.GZ formats | 1.7 | 9 |
| `signature_checker` | Signature Checker | Verify APK and module signatures (official & developer), display fingerprints and developer info to ensure integrity | 1.5 | 4 |
| `app_manager` | App Manager | Manage installed apps: list, launch, view details (package name, version, install time, source), uninstall, and open system app info page | 1.5 | 6 |
| `file_stats` | File Stats | File statistics module: file details, magic bytes detection for true file type, hash calculation (MD5/SHA1/SHA256), file comparison, checksum verification, directory stats, duplicate finder, large file scanner, empty file/folder finder, filename search, text stats | 1.9 | 11 |
| `reminder` | Reminder | Reminder assistant: create/query/update/delete calendar events, set alarms and timers, birthday reminders, view upcoming schedule | 1.4 | 11 |
| `text_tools` | Text Tools | Text tools: word count, Base64 encode/decode, URL encode/decode, regex match/replace, text transform, UUID generation, text hashing, plus LLM-powered summarization, rewriting, extraction and formatting. | 1.6 | 11 |
| `device_info` | Device Info | Device information module: model/OS, CPU, RAM, storage, display metrics, and one-call summary using public Android APIs only. | 1.6 | 6 |
| `image_tools` | Image Tools | Image tools: view info, resize, compress, convert format, rotate, crop, list gallery images, and find exact or visually similar duplicate photos | 1.8 | 9 |
| `clipboard` | Clipboard Manager | Clipboard manager: read, set, clear clipboard, and manage clipboard history | 1.5 | 7 |
| `battery` | Battery Manager | Battery management module: view battery level, charging status, health, temperature, voltage and more | 1.5 | 3 |
| `network_tools` | Network Tools | Network tools: ping reachability, TCP port check, DNS lookup, local/public IP, connectivity check, network info | 1.6 | 8 |
| `contacts` | Contacts Manager | Contacts manager: search, view details, list all, export to VCF, find duplicates | 1.5 | 6 |
| `notes` | Notes | Note-taking module: create, view, edit, delete, search, export notes, and use AI to summarize or organize local notes | 1.6 | 10 |
| `fortune` | Daily Fortune | Daily fortune module with personalized fortune by date and name, lunar calendar conversion, seven fortune levels and rich fortune vocabulary | 1.3 | 4 |
| `magic_dice` | Dice Tool | Dice tool module: roll 1-6 dice, roll with target sum, big/small judgment, all-same (leopard), combination enumeration and probability statistics | 1.2 | 6 |
| `led_banner` | LED Banner | LED support banner module: create scrolling/fading/static text banners with custom colors, gradients, font size, effects (glow, flash, shake), built-in cyber and idol support color templates | 1.7 | 4 |
| `system_cleaner` | System Cleaner | System cleaner: scan and clean temp files, cache, empty folders, thumbnail cache, and APK installer files to free storage space | 2.1 | 5 |
| `color_picker` | Color Tools | Color picker and conversion across HEX, RGB, HSL, CMYK; harmonious palettes (complementary, analogous, triadic, split-complementary, tetradic); random colors; closest CSS named color lookup | 1.4 | 4 |
| `unit_converter` | Unit Converter | Universal unit converter: length, weight, temperature, area, volume, speed, time, data storage, and more | 1.4 | 4 |
| `password_gen` | Password Generator | Secure password generator: create strong passwords with customizable length, complexity, and character types. Also includes passphrase generation and password strength checker. | 1.7 | 4 |
| `qrcode` | QR Code Tools | QR code tools: generate QR codes from text or URLs, decode QR codes from images, view and share generated codes | 1.6 | 5 |
| `snake_game` | Snake Game | Classic Snake game. Control the snake to eat food and grow longer while avoiding walls and yourself. Supports difficulty settings | 1.4 | 4 |
| `farming_game` | Farming Game | Farming simulation game. Plant seeds, water, fertilize and weed to help plants grow. Save harvest records. Supports scheduled tasks. Single save slot | 1.4 | 10 |
| `gomoku_game` | Gomoku | Classic Gomoku (Five in a Row) game. Player (black) vs AI (white) on a 15x15 board. First to get five in a row wins | 1.4 | 4 |
| `tetris_game` | Tetris Game | Classic Tetris game. Control blocks to move and rotate, stack them to complete rows for points. Supports difficulty settings | 1.5 | 7 |
| `sudoku_game` | Sudoku | Classic Sudoku game. Fill a 9x9 grid with digits 1-9 so each row, column and 3x3 box is unique. Supports difficulty-based puzzle generation | 1.4 | 7 |
| `tictactoe_game` | Tic-Tac-Toe | Classic Tic-Tac-Toe game. Player (X) vs AI (O) on a 3x3 grid. First to get three in a row wins | 1.4 | 4 |
| `link_parser` | Link Parser | Parse URLs to extract titles, descriptions, images, links, downloadable files, and use AI to summarize pages or answer page-specific questions | 1.5 | 10 |
| `weather` | Weather Assistant | Weather assistant module, query current weather, multi-day forecasts, and today-vs-tomorrow temperature/weather alerts using the Open-Meteo free API | 1.5 | 5 |
| `ocr` | OCR Text Recognition | OCR module for extracting text from images, supports Chinese and English with auto language detection; automatically removes leading attachment placeholders and returns clear non-image errors | 1.10 | 3 |
| `flashlight` | Flashlight | Flashlight control module, toggle camera torch on/off, check current status | 1.3 | 5 |
| `translator` | Translator | Text translation module supporting Chinese, English, Japanese, Korean, French, German, Spanish and more with auto detection; regional or non-standard targets such as Argentine Spanish, Cantonese, or Traditional Chinese automatically use LLM translation. | 1.8 | 5 |
| `compass` | Digital Compass | Digital compass module using device sensors to show current heading, azimuth and cardinal direction | 1.3 | 2 |
| `url_codec` | URL Codec | URL encoding/decoding tool. Supports URL encoding, URL decoding, and URL component parsing. Use when user says 'URL encode', 'URL decode', or 'parse URL' | 1.2 | 6 |
| `hello_world` | Hello World | PandaGenie self-introduction module. When users ask 'who are you', 'what can you do', 'what features do you have', etc., AI automatically invokes this to return a structured introduction and capability list. | 1.2 | 3 |
| `document_tools` | Document Tools | Document content tools for extracting, querying, creating, deleting, replacing, appending, converting documents, importing/creating CSV or XLSX tables, plus LLM-powered document summarization and Q&A. | 1.5 | 13 |
| `device_controls` | Device Controls | Device, WiFi/Wi-Fi and Bluetooth controls: read and adjust brightness and stream volume, check Wi-Fi/Bluetooth status, and open Wi-Fi, internet and Bluetooth system panels. | 1.3 | 17 |
| `fullscreen_countdown` | Fullscreen Countdown | Landscape fullscreen countdown timer with hour/minute/second settings, automatic mm:ss or hh:mm:ss display, one-minute vibration and sound alerts, and final per-second vibration and sound prompts. | 1.2 | 2 |
| `phone_test_recorder` | Phone Test Recorder | Record real usage tests such as charging, overnight standby, and gaming heat, then generate Coolapk-style curve reports and comparison conclusions. | 1.0 | 6 |
| `storage_radar_report` | Storage Radar Report | Scan storage for large files, videos, APKs, archives, downloads, WeChat folders, and duplicate candidates, then generate a readable cleanup report. | 1.1 | 4 |
| `phone_check_report` | Phone Check Report | Generate a phone inspection report for new/used devices with device, OS, display, battery, sensors, Camera2, Widevine, storage speed, and preinstalled app highlights. | 1.2 | 5 |
| `long_image_generator` | Long Image Generator | Convert text, Markdown, HTML, Word DOCX documents, and web pages into 1080px-wide PNG long images for sharing articles, reports, web content, and notes. | 1.7 | 6 |
| `file_downloader` | File Downloader | Download files by analyzing web pages or direct links, constructing likely download candidates, and saving selected files to the PandaGenie downloads folder. | 1.1 | 6 |
| `claw_skill_vetter` | Skill Vetter | Lightweight security vetter for Claude Code skills. Scans text, files, or URLs for destructive commands, secret exfiltration, persistence, and hidden-instruction risks. | 1.0.4 | 5 |
| `claw_ontology` | Ontology | Lightweight local ontology module. Stores subject-relation-object facts in the module sandbox with query, import, delete, and summary actions. | 1.0.4 | 7 |
| `claw_humanizer` | Humanizer | LLM-backed text humanizer that rewrites stiff, templated, or AI-sounding copy into natural, readable mobile-friendly language. | 1.0.4 | 4 |
| `claw_doc_updater` | Doc Updater | Mobile doc update helper adapted from Auto Document Updater ideas. Compares text versions and generates change summaries, update plans, and todos. | 1.0.4 | 4 |
| `claw_skill_discovery` | Skill Discovery | Mobile skill discovery module inspired by curated Top Skills. Supports top downloads, certified skills, newest skills, and keyword search. | 1.0.4 | 5 |
| `claw_workflow_planner` | Workflow Planner | Workflow planning module adapted from Automation Workflows and Proactive Agent ideas. Turns goals into mobile-friendly steps, module suggestions, and risk notes. | 1.0.4 | 4 |
| `claw_prompt_shield` | Prompt Shield | Prompt security module adapted from SkillScan and Shield CN ideas. Checks prompt injection, credential leaks, data exfiltration, and dangerous actions with Chinese scenarios in mind. | 1.0.4 | 3 |

## Download

> [Download APK v1.0.35](https://github.com/Rorschach123/PandaGenieSource/releases/download/20260515/PandaGenie-v1.0.35.apk)

- Release tag: [`20260515`](https://github.com/Rorschach123/PandaGenieSource/releases/tag/20260515)
- Android project version: `versionName "1.0.35"` / `versionCode 10035`
- Official download: <https://cf.pandagenie.ai/app-update/latest-download?v=10035>

## Changelog

<details open>
<summary><b>v1.0.35</b> (2026-05-15)</summary>

- App version bump: updated Android to `versionName "1.0.35"` / `versionCode 10035` and published a new final APK.
- On-device memory system: added Full app memory, Private memory, and Memory off modes, with local extraction, storage, retrieval, and LLM/Agent prompt injection.
- Memory privacy guardrails: the regular memory store stays in app-private storage; secrets, tokens, phone numbers, and emails are detected locally. Full mode stores sensitive values as Vault variables, while Private mode keeps only redacted context.
- Module ecosystem refresh: `modules.json` stays at 52 official modules and expands to 333 APIs, including Contacts, Device Controls, Notes, App Manager, Reminder, Unit Converter, and QR Code updates.
- Server sync: updated shared configs, premium credits, share rate limits, and website release metadata.

</details>

## Build

```powershell
cd E:\ProjectAI\PandaGenie\PandaGenie
.\gradlew.bat assembleRelease -PfinalRelease --no-daemon
```

## License

PandaGenie is licensed under LGPL-3.0.
