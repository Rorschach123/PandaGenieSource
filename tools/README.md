# PandaGenieSource 构建工具

`tools/` 保存官方模块批量构建、原生库编译和 `.mod` 打包脚本。正常模块开发优先从 `module-dev-toolkit/` 初始化签名，再用这里的脚本做批量构建。

## 常用脚本

| 脚本 | 作用 |
|---|---|
| `pack_modules.ps1` | 编译模块插件 DEX、收集 native `.so`、生成并双签每个 `.mod`。默认输出到 `PandaGenieSource/modules/`，日志在 `logs/`。 |
| `build_all_native.ps1` | 批量编译所有包含 native 代码的模块，会调用各模块自己的 `build_native.ps1`。 |

签名初始化脚本位于 `PandaGenieSource/module-dev-toolkit/`。

## 目录关系

```text
PandaGenie/
├─ PandaGenieSource/              # 模块源码、工具链、打包输出
│  ├─ source/<moduleId>/           # 单个模块源码
│  │  ├─ manifest.json
│  │  ├─ index.html
│  │  └─ plugin_src/
│  ├─ module-dev-toolkit/          # 签名初始化、mk_module.ps1、开发指南
│  ├─ modules/                     # 已打包 .mod 输出
│  ├─ modules.json                 # 模块索引
│  └─ tools/                       # 当前目录
└─ Keystore/                       # 本地签名材料，不提交到仓库
   ├─ module_signing/
   └─ dev_signing/
```

## 签名流程

`pack_modules.ps1` 从 `PandaGenie/Keystore/` 读取签名材料：

| 步骤 | Keystore 路径 |
|---|---|
| 开发者签名 | `Keystore/dev_signing/private/dev-keystore.p12` |
| 官方签名 | `Keystore/module_signing/private/official-keystore.p12` |

初始化签名：

```powershell
cd PandaGenieSource\module-dev-toolkit
.\mk_module.ps1 -Action init-dev-signing
.\mk_module.ps1 -Action init-signing
```

## English

This directory contains build utilities for official PandaGenie modules.

- `pack_modules.ps1`: compiles plugin DEX files, collects native libraries, and signs `.mod` packages.
- `build_all_native.ps1`: builds native libraries for modules that contain native code.

Keystores are expected under `PandaGenie/Keystore/` and must not be committed.
