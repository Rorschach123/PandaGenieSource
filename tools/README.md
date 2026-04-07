# Build Tools

- `pack_modules.ps1`: Pack modules (compile plugin DEX, collect native .so, dual-sign each .mod). Default output to `PandaGenieSource/modules/`, logs in `logs/`
- `build_all_native.ps1`: Compile all native module libraries (calls each module's `build_native.ps1`)

Signing initialization scripts live in **`PandaGenieSource/module-dev-toolkit/`** (or **`module-dev-toolkit/`** relative to this repo root).

## Directory Layout

```text
PandaGenie/                     ← typical local workspace (example)
├── PandaGenieSource/           ← this repo: module source, toolkit, packed .mod outputs
│   ├── source/<moduleId>/      ← module source files
│   │   ├── manifest.json
│   │   ├── index.html
│   │   └── plugin_src/
│   ├── module-dev-toolkit/     ← signing init, mk_module.ps1, dev guide
│   ├── modules/                ← compiled .mod files (pack output)
│   ├── modules.json            ← module index (updated by pack)
│   └── tools/                  ← build scripts (this dir)
│       ├── pack_modules.ps1
│       └── build_all_native.ps1
└── Keystore/                   ← signing keys (outside repos)
    ├── module_signing/
    └── dev_signing/
```

## Signing Flow

The `pack_modules.ps1` reads signing keystores from `PandaGenie/Keystore/`:

| Step | Keystore Path |
|------|---------------|
| Developer signing | `Keystore/dev_signing/private/dev-keystore.p12` |
| Official signing | `Keystore/module_signing/private/official-keystore.p12` |

To initialize signing keys, use `mk_module.ps1` in `module-dev-toolkit/`:

```powershell
cd PandaGenieSource\module-dev-toolkit
.\mk_module.ps1 -Action init-dev-signing
.\mk_module.ps1 -Action init-signing
```

If your shell is already at the repo root, use `cd module-dev-toolkit` instead.
