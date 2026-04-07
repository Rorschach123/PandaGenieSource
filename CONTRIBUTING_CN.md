<div align="center">

# 参与贡献 PandaGenie

**欢迎你的参与，让 PandaGenie 变得更好！**

无论是开发新模块、修复 Bug、完善文档，还是提出想法 — 我们都欢迎。

</div>

---

## 快速开始：开发一个模块（最简单的贡献方式）

参与贡献最快的方式就是**创建一个模块**。只需要 3 个文件和一个 Java 接口。

```
source/my_module/
├── manifest.json          ← 告诉 AI 你能做什么
├── index.html             ← 可选的 UI 页面
└── plugin_src/
    └── .../MyPlugin.java  ← 你的逻辑（一个方法）
```

推荐使用 **[模块模板仓库](https://github.com/Rorschach123/PandaGenie-Module-Template)** 一键创建项目，开箱即用。

**完整开发指南：** [module-dev-toolkit/MODULE_DEVELOPMENT_GUIDE.md](module-dev-toolkit/MODULE_DEVELOPMENT_GUIDE.md)（[GitHub](https://github.com/Rorschach123/PandaGenieSource/blob/main/module-dev-toolkit/MODULE_DEVELOPMENT_GUIDE.md)）

---

## 贡献类型

| 类型 | 位置 | 方式 |
|------|------|------|
| **新模块** | `source/<module_id>/` | 创建模块，本地测试，提交 PR 或上传到 [cf.pandagenie.ai/sign](https://cf.pandagenie.ai/sign) |
| **Bug 修复** | 现有模块代码 | Fork → 修复 → PR |
| **文档改进** | `*.md` 文件、代码注释 | Fork → 编辑 → PR |
| **翻译** | `manifest.json` 的 `_en` 字段、README | 添加或改进中英文翻译 |
| **模块灵感** | GitHub Issues | 提交 Issue，标记 `module-idea` 标签 |
| **Bug 报告** | GitHub Issues | 提交 Issue，附上复现步骤 |

---

## 开发环境配置

### 前置要求

- **Java JDK 8+**（编译模块插件）
- **Android SDK**（d8、javac）
- **PowerShell**（构建脚本）
- **ADB**（推送模块到设备）

### 本地构建模块

```powershell
cd module-dev-toolkit/

# 首次使用：生成开发者签名密钥
.\mk_module.ps1 -Action init-dev-signing

# 构建你的模块
.\mk_module.ps1 -Action pack -Modules "my_module"

# 推送到设备
adb push ..\modules\my_module.mod /sdcard/PandaGenie/modules/
```

### 测试

1. 打开 PandaGenie APP → 设置 → 开启 **开发者模式**
2. 将 `.mod` 文件推送到 `/sdcard/PandaGenie/modules/`
3. 重启 APP — 模块自动加载
4. 用自然语言对话测试：AI 会自动发现并调用你的模块 API

---

## 提交 Pull Request

### 提交新模块

1. **Fork** 本仓库
2. 创建模块目录：`source/<你的模块ID>/`
3. 至少包含：
   - `manifest.json`，包含清晰的 API 描述（中英文双语）
   - `plugin_src/` 下的 Java 实现
4. 开启开发者模式进行本地测试
5. 提交 **Pull Request**，说明：
   - 模块功能
   - 可触发模块的用户指令示例
   - 需要的权限及原因

### 提交 Bug 修复 / 改进

1. **Fork** 本仓库
2. 创建分支：`git checkout -b fix/描述`
3. 修改代码
4. 充分测试
5. 提交 **Pull Request**，描述修复内容

---

## 代码规范

### 模块清单（`manifest.json`）

- 所有 API 描述必须同时包含 `desc`（中文）和 `desc_en`（英文）
- 参数描述要足够清晰，让 AI 能理解何时以及如何调用
- 只申请模块实际需要的最小权限

### Java 插件代码

- 实现 `ModulePlugin` 接口 — 一个 `invoke()` 方法
- 始终返回合法 JSON：`{"success": true/false, ...}`
- 优雅处理异常 — 返回 `{"success": false, "error": "描述"}` 而不是直接抛出异常
- 尽量减少依赖，优先使用 Android SDK 自带 API

### 通用规范

- 保持代码可读性 — 清晰的变量名优于花哨的技巧
- 遵循所编辑文件的现有代码风格
- 尽可能在真机上测试

---

## 通过在线平台提交

如果你不想使用 Git/PR：

1. 在本地构建 `.mod` 文件
2. 访问 **[cf.pandagenie.ai/sign](https://cf.pandagenie.ai/sign)**
3. 上传 → 自动验证 → 官方签名 → 发布到模块市场

这是从代码到上架最快的路径。

---

## 报告问题

提交 Bug 报告时，请包含以下信息：

- **设备型号和 Android 版本**
- **PandaGenie APP 版本**
- **涉及的模块**（如适用）
- **复现步骤**
- **预期行为 vs 实际行为**
- **日志**（如有，从 APP 的详情页面获取）

---

## 社区

- **Discord：** [https://discord.gg/Cfc7pjrjt2](https://discord.gg/Cfc7pjrjt2) — 获取帮助、分享想法、展示你的模块
- **GitHub Issues：** 用于 Bug 报告、功能建议和模块灵感

---

## 开源协议

参与贡献即表示你同意你的贡献将按 [LGPL-3.0 协议](LICENSE) 许可。

---

<div align="center">

**你构建的每一个模块，都让 PandaGenie 对所有用户变得更智能。**

**一起来创造吧。**

</div>
