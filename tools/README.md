# Build Tools

- `pack_modules.ps1`: Pack modules (compile plugin DEX, collect native .so, dual-sign each .mod). Output to `PandaGenieModules/modules/`, logs in `logs/`
- `build_all_native.ps1`: Compile all native module libraries (calls each module's `build_native.ps1`)

Signing initialization scripts are in **`PandaGenieModules/module-dev-toolkit/`**.

## Directory Layout

```text
PandaGenie/
├── PandaGenieSource/           ← this repo (module source code)
│   ├── source/<moduleId>/      ← module source files
│   │   ├── manifest.json
│   │   ├── index.html
│   │   └── plugin_src/
│   └── tools/                  ← build scripts (this dir)
│       ├── pack_modules.ps1
│       └── build_all_native.ps1
├── PandaGenieModules/          ← module distribution repo
│   ├── module-dev-toolkit/     ← signing init, mk_module.ps1, dev guide
│   └── modules/                ← compiled .mod files
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
cd PandaGenieModules\module-dev-toolkit
.\mk_module.ps1 -Action init-dev-signing
.\mk_module.ps1 -Action init-signing
```
