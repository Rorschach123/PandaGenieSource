<div align="center">

<h1>&#x1F43C; PandaGenie</h1>

<p><strong>AI-Powered Modular Android Assistant</strong></p>

<p>
Tell PandaGenie what you need in natural language.<br/>
It plans, executes, and delivers — powered by <strong>any LLM</strong> and a growing library of <strong>hot-loadable modules</strong>.
</p>

<p>
  <a href="#join-us--developers-welcome">Join Us</a> &nbsp;&#x2022;&nbsp;
  <a href="https://cf.pandagenie.ai">Submit a Module</a> &nbsp;&#x2022;&nbsp;
  <a href="https://github.com/Rorschach123/PandaGenieModules">Module Marketplace</a> &nbsp;&#x2022;&nbsp;
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
- **Sandboxed execution** — file access, network, and permission controls per module
- **Offline voice** — built-in Vosk speech recognition
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

> **Want more?** That's where **you** come in.

---

## Download

<!-- TODO: Add download link -->
> &#x1F4E5; **APK Download**: *Coming soon*

---

## Create Your Own Module

Building a PandaGenie module is **incredibly simple** — perfect for vibe coding with AI assistants like Cursor.

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
            return new JSONObject()
                .put("success", true)
                .put("output", "Done: " + p.optString("input"))
                .toString();
        }
        return new JSONObject().put("success", false).put("error", "Unknown action").toString();
    }
}
```

### Build & Test Locally

```powershell
# In PandaGenieModules/module-dev-toolkit/
.\mk_module.ps1 -Action init-dev-signing    # First time only
.\mk_module.ps1 -Action pack -Modules "my_module"

adb push ..\modules\my_module.mod /sdcard/PandaGenie/modules/
```

### Get Official Signature & Publish

Once your module works, head to **[https://cf.pandagenie.ai](https://cf.pandagenie.ai)** to upload it. The system validates your module and applies the official signature — you can then publish it to the marketplace with one click.

For the full development guide, see [PandaGenieModules/module-dev-toolkit/MODULE_DEVELOPMENT_GUIDE.md](https://github.com/Rorschach123/PandaGenieModules/blob/main/module-dev-toolkit/MODULE_DEVELOPMENT_GUIDE.md).

---

## Project Structure

This project is split into three repositories:

| Repo | Purpose |
|------|---------|
| **[PandaGenieSource](.)** (this repo) | Module source code and build tools |
| **[PandaGenieModules](https://github.com/Rorschach123/PandaGenieModules)** | Compiled `.mod` files, marketplace index, dev toolkit |
| **[PandaGenieServer](https://github.com/Rorschach123/PandaGenieServer)** | Backend API: multi-LLM proxy, module marketplace, signing service |

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
└── tools/
    ├── pack_modules.ps1       # Pack & sign .mod files
    └── build_all_native.ps1   # Compile native libraries
```

---

## Join Us — Developers Welcome!

> **We believe the best modules will come from the community, not just us.**

PandaGenie is a **co-creation platform** — we sincerely invite developers of all levels to join and build a richer module ecosystem together. Whether you're a seasoned Android developer or someone who just learned to code last week with an AI assistant, **there's room for you here**.

### Why Build a PandaGenie Module?

- **Incredibly low barrier** — 3 files, one Java interface, done. You can **vibe code** the entire thing with AI assistants like Cursor. This whole project was built that way.
- **Instant distribution** — your module reaches all PandaGenie users through the built-in marketplace
- **Revenue sharing** — if PandaGenie generates revenue in the future (premium features, donations, sponsorships, etc.), **module developers will receive a share of that revenue** proportional to their module's usage and impact. We are committed to making this a platform where contributors are fairly rewarded.

### How to Submit Your Module

There are **two ways** to get your module officially signed and published:

#### Option A: Online Signing Portal (Recommended)

Visit **[https://cf.pandagenie.ai](https://cf.pandagenie.ai)** — the official PandaGenie module signing service:

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

The module ecosystem is still young — there's **so much to build**:

- &#x1F3A8; **Image tools** — resize, convert, watermark
- &#x1F4DD; **Note taking** — create/search notes
- &#x1F4E7; **SMS manager** — search, export messages
- &#x1F4F6; **Network tools** — ping, DNS lookup, speed test
- &#x1F50B; **Battery manager** — stats, optimization tips
- &#x1F3B5; **Audio tools** — metadata, convert formats
- &#x1F4CB; **Clipboard manager** — history, templates
- &#x1F4CD; **Location tools** — nearby places, coordinates
- ...and anything else you can imagine!

> Every module you build makes PandaGenie smarter for everyone. **Let's build the future of AI-powered mobile together.**

---

## Tech Stack

| Component | Technology |
|-----------|-----------|
| App | Kotlin, Jetpack Compose, Material 3 |
| AI | Any OpenAI-compatible / Claude API |
| Speech | Vosk (offline) |
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

</div>
