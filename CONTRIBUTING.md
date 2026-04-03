<div align="center">

# Contributing to PandaGenie

**We'd love your help making PandaGenie better!**

Whether you're building a new module, fixing a bug, improving docs, or just have an idea — you're welcome here.

</div>

---

## Quick Start: Build a Module (Easiest Way to Contribute)

The fastest way to contribute is to **create a module**. It only takes 3 files and one Java interface.

```
source/my_module/
├── manifest.json          ← Describe your APIs for the AI
├── index.html             ← Optional UI page
└── plugin_src/
    └── .../MyPlugin.java  ← Your logic (one method)
```

**Full guide:** [MODULE_DEVELOPMENT_GUIDE.md](https://github.com/Rorschach123/PandaGenieModules/blob/main/module-dev-toolkit/MODULE_DEVELOPMENT_GUIDE.md)

---

## Types of Contributions

| Type | Where | How |
|------|-------|-----|
| **New module** | `source/<module_id>/` | Create module, test locally, submit PR or upload to [cf.pandagenie.ai](https://cf.pandagenie.ai) |
| **Bug fix** | Existing module code | Fork → fix → PR |
| **Documentation** | `*.md` files, code comments | Fork → edit → PR |
| **Translation** | `manifest.json` `_en` fields, README | Add/improve English or Chinese translations |
| **Module ideas** | GitHub Issues | Open an issue with the `module-idea` label |
| **Bug reports** | GitHub Issues | Open an issue with steps to reproduce |

---

## Development Setup

### Prerequisites

- **Java JDK 8+** (for compiling module plugins)
- **Android SDK** (d8, javac)
- **PowerShell** (build scripts)
- **ADB** (for pushing modules to device)

### Building a Module Locally

```powershell
cd PandaGenieModules/module-dev-toolkit/

# First time: generate your developer signing key
.\mk_module.ps1 -Action init-dev-signing

# Build your module
.\mk_module.ps1 -Action pack -Modules "my_module"

# Push to device for testing
adb push ..\modules\my_module.mod /sdcard/PandaGenie/modules/
```

### Testing

1. Enable **Developer Mode** in the PandaGenie app settings
2. Push your `.mod` file to `/sdcard/PandaGenie/modules/`
3. Restart the app — your module appears automatically
4. Test by chatting naturally: the AI will discover and use your module's APIs

---

## Submitting a Pull Request

### For New Modules

1. **Fork** this repository
2. Create your module directory: `source/<your_module_id>/`
3. Include at minimum:
   - `manifest.json` with clear API descriptions (both Chinese and English)
   - `plugin_src/` with your Java implementation
4. Test locally with Developer Mode
5. Submit a **Pull Request** with:
   - What the module does
   - Example user commands that trigger it
   - Any permissions it requires and why

### For Bug Fixes / Improvements

1. **Fork** the repository
2. Create a branch: `git checkout -b fix/description`
3. Make your changes
4. Test thoroughly
5. Submit a **Pull Request** describing the fix

---

## Code Guidelines

### Module Manifest (`manifest.json`)

- Always include both `desc` (Chinese) and `desc_en` (English) for all API descriptions
- Parameter descriptions should be clear enough for the AI to understand when and how to use them
- Use the minimum set of permissions your module actually needs

### Java Plugin Code

- Implement the `ModulePlugin` interface — one `invoke()` method
- Always return valid JSON: `{"success": true/false, ...}`
- Handle errors gracefully — return `{"success": false, "error": "description"}` instead of throwing
- Keep dependencies minimal; use only Android SDK APIs when possible

### General

- Keep code readable — clear variable names over clever tricks
- Respect the existing code style of the file you're editing
- Test on a real device when possible

---

## Submitting via the Online Portal

If you prefer not to use Git/PR:

1. Build your `.mod` file locally
2. Go to **[cf.pandagenie.ai](https://cf.pandagenie.ai)**
3. Upload → automatic validation → official signing → publish to marketplace

This is the fastest path from code to distribution.

---

## Reporting Issues

When filing a bug report, please include:

- **Device model and Android version**
- **PandaGenie app version**
- **Module involved** (if applicable)
- **Steps to reproduce**
- **Expected vs actual behavior**
- **Logs** (if available, from the app's detail view)

---

## Community

- **Discord:** [https://discord.gg/Cfc7pjrjt2](https://discord.gg/Cfc7pjrjt2) — get help, share ideas, show off your modules
- **GitHub Issues:** For bug reports, feature requests, and module ideas

---

## License

By contributing, you agree that your contributions will be licensed under the [LGPL-3.0 License](LICENSE).

---

<div align="center">

**Every module you build makes PandaGenie smarter for everyone.**

**Let's build together.**

</div>
