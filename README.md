<div align="center">

<h1>&#x1F43C; PandaGenie</h1>

<p><strong>AI-Powered Modular Android Assistant</strong></p>

<p>
Tell PandaGenie what you need in natural language.<br/>
It plans, executes, and delivers — powered by <strong>any LLM</strong> and a growing library of <strong>hot-loadable modules</strong>.
</p>

<p>
  <a href="https://cf.pandagenie.ai">Official Website</a> &nbsp;&#x2022;&nbsp;
  <a href="https://discord.gg/Cfc7pjrjt2">Discord</a> &nbsp;&#x2022;&nbsp;
  <a href="#join-us--developers-welcome">Join Us</a> &nbsp;&#x2022;&nbsp;
  <a href="https://cf.pandagenie.ai/sign">Submit a Module</a> &nbsp;&#x2022;&nbsp;
  <a href="https://cf.pandagenie.ai/marketplace">Module Marketplace</a> &nbsp;&#x2022;&nbsp;
  <a href="#create-your-own-module">Create a Module</a> &nbsp;&#x2022;&nbsp;
  <a href="README_CN.md">&#x1F1E8;&#x1F1F3; 中文</a>
</p>

</div>

---

## Why PandaGenie

The explosion of generative AI marks a new internet revolution — following the PC era and the mobile era. Computing power is now quantified as **Tokens**, much like data plans from telecom carriers. If this is a new cycle in history, ChatGPT was Year Zero, and an entirely new class of applications is about to emerge.

Here's the fundamental insight: everything in computing is **computation**. Users want **results**, not processes. So the role of AI is to **bridge every step** — turning natural language intent directly into executed outcomes.

PandaGenie starts from this premise: **make your smartphone a true AI assistant**, where any task can be accomplished through a single conversation.

### Design Principles

| Principle | Description |
|-----------|-------------|
| **Transparent & Secure** | Every step the AI takes, every piece of data it touches — fully visible and user-controllable. All module code is open-source and auditable |
| **Low Token Cost** | Tokens are like a data plan — not unlimited. Precise prompt engineering and task planning minimize consumption |
| **Built for Everyone** | Users only need to know *what* they want, not *which button to press* or *how many steps to click through* |
| **Connecting Devs & Users** | Developers build modules, modules carry capabilities, AI dispatches on user commands — a new model of developer-user collaboration |

---

## How It Works

> "Compress all photos in /Download into a zip" — that's all you say.

PandaGenie connects to your preferred LLM (GPT, Claude, DeepSeek, or any OpenAI-compatible API), reads the capabilities of all installed modules, and automatically plans multi-step tasks. No coding, no clicking through menus.

<p align="center">
  <img src="docs/architecture.svg" width="100%" alt="PandaGenie Architecture" />
</p>

**Key highlights:**

- **Any LLM backend** — OpenAI, Anthropic Claude, self-hosted, or any compatible API
- **Hot-loadable modules** — drop a `.mod` file, restart, done. No APK rebuild needed
- **AI auto-discovers** new capabilities from module manifests
- **Sandboxed execution** — file access, network, and permission controls per module with two-tier enforcement
- **Dual-signature security** — tamper-proof module verification

---

## Architecture

PandaGenie follows a clean separation between the **AI brain** and the **module ecosystem**:

```
User  ──>  AI Engine  ──>  Task Executor  ──>  Module Runtime  ──>  Plugin.invoke()
             │                   │                    │
        Build prompt        Run steps           Sandbox + verify
        from modules       resolve vars        ClassLoader isolation
```

The app **never hardcodes** any module. All capabilities are declared in each module's `manifest.json` and dynamically injected into the AI system prompt at runtime.

---

## Module System

Each `.mod` file is a self-contained package:

<p align="center">
  <img src="docs/mod-structure.svg" width="100%" alt=".mod File Structure" />
</p>

A module only needs to implement **one interface**:

```java
public interface ModulePlugin {
    String invoke(Context context, String action, String paramsJson) throws Exception;
}
```

The AI reads your `manifest.json`, understands what your module can do, and calls `invoke()` with the right `action` and `params` — automatically.

---

## Security: Dual-Signature Model

Every `.mod` carries two layers of JAR signatures for tamper-proof distribution:

<p align="center">
  <img src="docs/signing-flow.svg" width="100%" alt="Dual-Signature Flow" />
</p>

| Layer | Purpose |
|-------|---------|
| **DEV** (Developer) | Identifies the module author. Fingerprint bound to manifest |
| **OFFICIAL** | Proves the module passed official review. Verified against app-embedded cert |

Developer Mode allows loading DEV-only signed modules for testing.

---

## Available Modules

| Module | Description | Type |
|--------|-------------|------|
| &#x1F4C1; **File Manager** | Browse, create, copy, move, delete, search files | Native |
| &#x1F9EE; **Calculator** | Scientific math: expressions, trig, logs, factorial | Native |
| &#x1F4E6; **Archive** | ZIP (with password), TAR, GZ compression | Native |
| &#x1F4F1; **App Manager** | List, launch, uninstall apps, view app info | Java |
| &#x1F4CA; **File Stats** | Hash, compare, dir stats, duplicate finder | Java |
| &#x23F0; **Reminder** | Calendar events, alarms, timers, birthday reminders | Java |
| &#x1F50F; **Signature Checker** | Verify APK and module signatures | Java |
| &#x1F4DD; **Text Tools** | Word count, encoding, hash, regex, diff | Java |
| &#x1F4F1; **Device Info** | CPU, memory, storage, battery, sensors | Java |
| &#x1F5BC;&#xFE0F; **Image Tools** | Resize, compress, rotate, metadata, convert | Java |
| &#x1F4CB; **Clipboard** | Copy, paste, history, clear clipboard | Java |
| &#x1F50B; **Battery** | Battery status, health, temperature, charging | Java |
| &#x1F310; **Network Tools** | Ping, DNS lookup, port scan, speed test | Java |
| &#x1F4C7; **Contacts** | Search, add, edit, delete contacts | Java |
| &#x1F4D3; **Notes** | Create, edit, search, organize notes | Java |
| &#x1F3B2; **Magic Dice** | Roll dice, random numbers, coin flip | Java |
| &#x1F3AF; **Fortune** | Daily fortune, random quotes | Java |
| &#x1F4A1; **LED Banner** | Scrolling text banner with landscape mode | H5 |
| &#x1F9F9; **System Cleaner** | Scan and clean temp files, cache | Java |
| &#x1F3A8; **Color Picker** | HEX/RGB/HSL conversion, palette generator | Java |
| &#x1F4CF; **Unit Converter** | Length, weight, temperature, speed, data | Java |
| &#x1F511; **Password Gen** | Secure password and passphrase generator | Java |
| &#x1F4F7; **QR Code** | Generate and decode QR codes | H5+Java |
| &#x1F40D; **Snake Game** | Classic Snake with difficulty settings | H5+Java |
| &#x1FA86; **Tetris** | Classic Tetris with scoring | H5+Java |
| &#x1F9E9; **Sudoku** | 9x9 Sudoku puzzle with hints | H5+Java |
| &#x26AB; **Gomoku** | Five in a Row with AI opponent | H5+Java |
| &#x274E; **Tic-Tac-Toe** | Classic Tic-Tac-Toe with AI | H5+Java |
| &#x1F517; **Link Parser** | Parse URLs, extract titles/images/links/downloads, check accessibility | Java |
| &#x1F331; **Farming Game** | Plant, water, harvest simulation | H5+Java |

> &#x1F4E6; **[Browse all modules on the Marketplace](https://cf.pandagenie.ai/marketplace)** — or **create your own** below!

---

## Download

> &#x1F4E5; **[Download APK (v1.0.8)](https://github.com/Rorschach123/PandaGenieSource/releases/download/20260420/app-release.apk)**

---

## Changelog

<details open>
<summary><b>v1.0.8</b> (2026-04-20)</summary>

- **Rich HTML5 Module Output** — All 35 modules now return beautifully styled HTML5 mini-cards with interactive UIs via `_displayHtml`. Game modules render playable canvases, file modules show visual file trees, calculators present formatted results — all inside the chat bubble
- **Sandbox Auto-Allow for Scheduled Tasks** — New sub-option under "Ask each time" in Security settings: when enabled (default ON), scheduled and conditional tasks automatically bypass permission prompts with temporary session-level grants — no permanent permissions are written
- **Smart Welcome UX** — Empty chat now persistently shows the panda mascot with interactive suggestion chips until the user sends their first message. Greeting bubble no longer replaces the helpful prompt suggestions
- **Graceful "No Capability" Response** — When a request can't be fulfilled by any module, the AI now responds with a friendly message listing all available capabilities from installed and market modules, plus a link to build custom modules on pandagenie.ai
- **Direct APK Download** — Official website now serves APK downloads directly from Cloudflare KV edge storage for faster, more reliable downloads worldwide

</details>

<details>
<summary><b>v1.0.7</b> (2026-04-18)</summary>

- **Execution Trace (Action View)** — After each task, tap "Execution Trace" to see a full graphical flow diagram: every module involved, input/output data, permissions used, data access paths, and step timing — all in one intuitive vertical flow. Tap any step card to expand detailed input/output fields, permission grants, and data operations
- **Zero-Token Config Market Match** — Before calling the LLM, PandaGenie now searches the shared Config Market for a matching task configuration. If a high-confidence match is found, it executes directly — **completely bypassing the LLM and consuming zero tokens**. Toggle on/off in Settings → Modules
- **Smart LLM Response Handling** — Non-JSON LLM responses (quota exhaustion, conversational replies, error messages) are now detected and displayed gracefully instead of showing "Invalid JSON" errors. Actionable suggestions guide users to resolve quota issues
- **First-Open Welcome UX** — New users see a friendly panda greeting with interactive suggestion chips ("Browse Module Store", "What can you do?", etc.) instead of a bare "No modules installed" message

</details>

<details>
<summary><b>v1.0.6</b> (2026-04-18)</summary>

- **Config Market Scheduling** — Conditional execution now uses the full task scheduler (once / daily / weekly / monthly / event trigger)
- **Config Market Delete** — Own uploaded configurations show a prominent delete button
- **Local Capability Response** — When no LLM is configured, asking "what can you do?" returns a local capability list with setup instructions
- **Chat Feedback** — Submit feedback directly from the chat input bar
- **Data Vault** — Secure encrypted storage with master password, accessible from Security settings
- **File Manager v1.8** — Batch move/copy/delete, hidden file filtering, same-directory skip, search type filter (`file`/`dir`/`all`), increased display limits
- **Sandbox Permission Fix** — "Allow all directories" grants now work correctly across `/sdcard` ↔ `/storage/emulated/0` path formats
- **Module Name Fix** — i18n module names display correctly in sandbox prompts instead of raw JSON
- **Update Check** — Interval reduced to 30 minutes for faster update delivery
- 35+ modules updated in marketplace

</details>

<details>
<summary><b>v1.0.5</b> (2026-04-16)</summary>

- Multi-conversation support with sidebar drawer
- Conditional task isolation (scheduled tasks write to dedicated conversations)
- Android runtime permission auto-request (Calendar, Contacts, etc.)
- Unified JSON response format (no more parse errors in chat)
- Six new themes (Bamboo Breeze, Sunset Lava, etc.) + localized audit log
- QR Code module v1.2: image scan, camera scan, detect API
- Link Parser module (community developer Jarvan)
- Variable reference enhancements: `_random` pick, smart JSON object unpack
- Multiple bug fixes and UX improvements

</details>

---

## Create Your Own Module

Building a PandaGenie module is **incredibly simple** — perfect for vibe coding with AI assistants like Cursor.

### Use the Module Template (Recommended)

The fastest way to get started — click the button below to create your own module repo from our template:

[![Use this template](https://img.shields.io/badge/Use%20this-Template-6c5ce7?style=for-the-badge)](https://github.com/Rorschach123/PandaGenie-Module-Template/generate)

Or clone it manually:

```bash
git clone https://github.com/Rorschach123/PandaGenie-Module-Template.git my-awesome-module
```

The template includes a working example module with `manifest.json`, `MyModulePlugin.java`, and `index.html` — just rename, edit, and build.

### 3 Files. That's It.

```
source/my_module/
├── manifest.json      ← Tell AI what you can do
├── index.html         ← Optional UI page
└── plugin_src/
    └── .../MyPlugin.java   ← Your logic
```

### Quick Example

**manifest.json** — describe your APIs:

```json
{
  "id": "my_module",
  "name": "My Module",
  "name_en": "My Module",
  "description": "Does something cool",
  "version": "1.0",
  "apis": [
    {
      "name": "doSomething",
      "desc": "Does the thing",
      "desc_en": "Does the thing",
      "params": ["input"],
      "paramDesc": ["The input"],
      "paramDesc_en": ["The input"]
    }
  ]
}
```

**MyPlugin.java** — implement one method:

```java
public class MyPlugin implements ModulePlugin {
    @Override
    public String invoke(Context ctx, String action, String params) throws Exception {
        JSONObject p = new JSONObject(params);
        if ("doSomething".equals(action)) {
            JSONObject output = new JSONObject().put("result", "hello");
            return new JSONObject()
                .put("success", true)
                .put("output", output.toString())
                .put("_displayText", "| Item | Value |\n|---|---|\n| Result | hello |")
                .toString();
        }
        return new JSONObject().put("success", false).put("error", "Unknown action").toString();
    }
}
```

### Plugin Output Format

Every `invoke()` call must return a JSON string with these fields:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `success` | boolean | Yes | Whether the operation succeeded |
| `output` | string | Yes | Machine-readable result (JSON string for structured data) |
| `error` | string | On failure | Human-readable error message |
| `_displayText` | string | No | Rich formatted text for chat display (supports Markdown tables, links, bold) |
| `_openModule` | boolean | No | If `true`, the app opens the module's HTML UI |

**Rich Display Formats** — The `_displayText` field supports:

- **Markdown tables** — `| Col1 | Col2 |\n|---|---|\n| val1 | val2 |` → rendered as Unicode box-drawing tables
- **Bold** — `**text**` → rendered bold
- **Links** — `[text](url)` or bare `https://...` → clickable
- **Inline code** — `` `code` `` → monospace with accent color

Example with table output:

```java
private String formatResult(JSONObject data) {
    StringBuilder sb = new StringBuilder();
    sb.append("📊 Analysis Result\n\n");
    sb.append("| Metric | Value |\n");
    sb.append("|---|---|\n");
    sb.append("| Files | ").append(data.optInt("count")).append(" |\n");
    sb.append("| Total Size | ").append(data.optString("size")).append(" |\n");
    return sb.toString();
}
```

### The `.mod` File Format

A `.mod` file is a signed ZIP archive with a specific structure:

```
my_module.mod (ZIP)
├── manifest.json          # Module metadata, API definitions, permissions
├── plugin.jar             # Compiled plugin (contains DEX bytecode)
├── index.html             # Optional: module UI page
├── common.css             # Optional: shared stylesheet
├── META-INF/
│   ├── MANIFEST.MF        # JAR manifest
│   ├── DEV.SF / DEV.RSA   # Developer signature
│   └── OFFICIAL.SF / ...  # Official signature (after review)
└── libs/                  # Optional: native libraries
    ├── arm64-v8a/
    │   └── libmodule.so
    └── armeabi-v7a/
        └── libmodule.so
```

The `plugin.jar` inside the `.mod` contains DEX bytecode (not standard Java bytecode), produced by the Android `d8` tool. The pack script handles this conversion automatically.

### Build & Test Locally

```powershell
# In PandaGenieSource/module-dev-toolkit/ (or module-dev-toolkit/ from repo root)
.\mk_module.ps1 -Action init-dev-signing    # First time only
.\mk_module.ps1 -Action pack -Modules "my_module"

adb push ..\modules\my_module.mod /sdcard/PandaGenie/modules/
```

### Get Official Signature & Publish

Once your module works, head to **[https://cf.pandagenie.ai/sign](https://cf.pandagenie.ai/sign)** to upload it. The system validates your module and applies the official signature — you can then publish it to the marketplace with one click.

For the full development guide, see [module-dev-toolkit/MODULE_DEVELOPMENT_GUIDE.md](module-dev-toolkit/MODULE_DEVELOPMENT_GUIDE.md) (also on [GitHub](https://github.com/Rorschach123/PandaGenieSource/blob/main/module-dev-toolkit/MODULE_DEVELOPMENT_GUIDE.md)).

---

## Project Structure

Module source, build tooling, compiled `.mod` outputs, `modules.json`, and `module-dev-toolkit/` all live in this repository. A separate template repo helps you bootstrap new modules:

| Repo | Purpose |
|------|---------|
| **[PandaGenieSource](.)** (this repo) | Module source (`source/`), `tools/`, `module-dev-toolkit/`, built `.mod` files (`modules/`), and `modules.json` |
| **[PandaGenie-Module-Template](https://github.com/Rorschach123/PandaGenie-Module-Template)** | GitHub template repo — one-click starting point for new modules |

```
PandaGenieSource/
├── source/                    # Module source files
│   ├── shared_api/            # ModulePlugin interface
│   ├── calculator/
│   ├── filemanager/
│   ├── archive/
│   ├── app_manager/
│   ├── file_stats/
│   ├── reminder/
│   └── signature_checker/
├── module-dev-toolkit/        # mk_module.ps1, signing init, dev guide
├── modules/                   # Packed .mod outputs (from pack scripts)
├── modules.json               # Marketplace-style module index (updated by pack)
└── tools/
    ├── pack_modules.ps1       # Pack & sign .mod files
    └── build_all_native.ps1   # Compile native libraries
```

---

## Join Us — Developers Welcome!

> **We believe the best modules will come from the community, not just us.**

[![Discord](https://img.shields.io/discord/1234567890?color=5865F2&logo=discord&logoColor=white&label=Discord)](https://discord.gg/Cfc7pjrjt2)

**Join our Discord community:** [https://discord.gg/Cfc7pjrjt2](https://discord.gg/Cfc7pjrjt2) — discuss ideas, get help, share your modules, and collaborate with other developers.

PandaGenie is a **co-creation platform** — we sincerely invite developers of all levels to join and build a richer module ecosystem together. Whether you're a seasoned Android developer or someone who just learned to code last week with an AI assistant, **there's room for you here**.

### Why Build a PandaGenie Module?

- **Incredibly low barrier** — 3 files, one Java interface, done. You can **vibe code** the entire thing with AI assistants like Cursor. This whole project was built that way.
- **Instant distribution** — your module reaches all PandaGenie users through the built-in marketplace
- **Revenue sharing** — if PandaGenie generates revenue in the future (premium features, donations, sponsorships, etc.), **module developers will receive a share of that revenue** proportional to their module's usage and impact. We are committed to making this a platform where contributors are fairly rewarded.

### How to Submit Your Module

There are **two ways** to get your module officially signed and published:

#### Option A: Online Signing Portal (Recommended)

Visit **[https://cf.pandagenie.ai/sign](https://cf.pandagenie.ai/sign)** — the official PandaGenie module signing service:

1. Build your `.mod` file locally using the dev toolkit
2. Upload it on the website — it will automatically validate the file format, developer signature, and security checks
3. If everything passes, the official signature is applied and you can **download the signed `.mod`**
4. You'll also be asked if you want to **publish it to the module marketplace** — one click and it's live!

#### Option B: Pull Request

1. **Fork** this repo
2. Create your module in `source/<your_module_id>/`
3. Test it with Developer Mode enabled on the app
4. **Submit a Pull Request** — after review, we'll add the official signature and publish

### Co-creation Guidelines

- **Open & transparent code** — all module code is publicly auditable to ensure trustworthy behavior
- **Protect your developer key** — sign modules with your dev key first, submit for review, then official signing
- Clear API descriptions (the AI reads them!)
- Support both Chinese and English (`_en` fields)
- Minimal permissions — request only what you need

### Ideas for New Modules

The module ecosystem is growing fast — there's **still so much to build**:

- &#x1F4E7; **SMS manager** — search, export messages
- &#x1F3B5; **Audio tools** — metadata, convert formats
- &#x1F4CD; **Location tools** — nearby places, coordinates
- &#x1F4C8; **Health tracker** — step count, sleep, exercise
- &#x1F4B0; **Finance tools** — expense tracking, currency conversion
- &#x1F4E2; **Social tools** — share content across platforms
- ...and anything else you can imagine!

> Every module you build makes PandaGenie smarter for everyone. **Let's build the future of AI-powered mobile together.**

### Join Our Community

[![Discord](https://img.shields.io/badge/Discord-Join%20Us-5865F2?logo=discord&logoColor=white)](https://discord.gg/Cfc7pjrjt2)

Have questions? Want to show off your module? Need help getting started? Join our **[Discord server](https://discord.gg/Cfc7pjrjt2)** — we'd love to meet you.

---

## Contributing

We welcome contributions of all kinds! See **[CONTRIBUTING.md](CONTRIBUTING.md)** for guidelines on:

- Building and submitting new modules
- Reporting bugs and suggesting features
- Code style and PR process

---

## Contributors

<a href="https://github.com/Rorschach123/PandaGenieSource/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=Rorschach123/PandaGenieSource" />
</a>

---

## Tech Stack

| Component | Technology |
|-----------|-----------|
| App | Kotlin, Jetpack Compose, Material 3 |
| AI | Any OpenAI-compatible / Claude API |
| Modules | Java plugins, DEX ClassLoader, optional JNI/C++ |
| Signing | PKCS12 keystores, jarsigner, DPAPI |
| Build | PowerShell, Android SDK (d8, javac) |

---

## License

This project is licensed under the LGPL-3.0 License. See [LICENSE](LICENSE) for details.

---

<div align="center">

**Built with &#x2764;&#xFE0F; and a lot of vibe coding**

*PandaGenie — Let AI handle the boring stuff on your phone*

[![Discord](https://img.shields.io/badge/Discord-Join%20Community-5865F2?logo=discord&logoColor=white)](https://discord.gg/Cfc7pjrjt2)

</div>
