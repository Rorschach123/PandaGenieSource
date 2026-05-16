<div align="center">

# PandaGenieSource

**PandaGenie Android App、官方模块与模块开发工具源码**

[官网](https://cf.pandagenie.ai) | [下载 APK](https://cf.pandagenie.ai/app-update/latest-download) | [任务/模块市场](https://cf.pandagenie.ai/marketplace) | [提交模块](https://cf.pandagenie.ai/sign) | [PandaGenieSDK](https://github.com/Rorschach123/PandaGenieSDK) | [Discord](https://discord.gg/Cfc7pjrjt2) | [English](README_EN.md)

</div>

---

## 项目定位

PandaGenie 是一个面向 Android 的 AI 模块化手机助手。用户用自然语言说出任务，App 会结合大模型、任务市场和热加载模块完成规划、执行、追踪和结果展示。

这个仓库包含：

- PandaGenie Android App 源码。
- 官方热加载模块源码与 `modules.json` 模块目录。
- 模块开发、打包、签名、发布相关工具。
- 与服务端、官网、SDK 生态协同的 App 侧实现。

如果你想让一个独立 Android App 被 PandaGenie 或其他 AI 助手调用，请使用 [PandaGenieSDK](https://github.com/Rorschach123/PandaGenieSDK)。如果你想开发 PandaGenie 内部热加载模块，请使用 [PandaGenie Module Template](https://github.com/Rorschach123/PandaGenie-Module-Template)。

## 生态项目

| 项目 | 作用 |
|---|---|
| [PandaGenie 官网](https://cf.pandagenie.ai) | APK 下载、任务市场、模块市场、SDK 注册、模块提交和开发者入口。 |
| [PandaGenieSource](https://github.com/Rorschach123/PandaGenieSource) | 当前仓库：App 源码、官方模块、模块打包工具和模块目录。 |
| [PandaGenieSDK](https://github.com/Rorschach123/PandaGenieSDK) | Android AAR，用于应用之间的能力发现、信任校验和调用。 |
| [PandaGenie SDK Provider Template](https://github.com/Rorschach123/PandaGenieSDK-Provider-Template) | 独立 Android App 接入 SDK 并暴露能力的示例模板。 |
| [PandaGenie Module Template](https://github.com/Rorschach123/PandaGenie-Module-Template) | PandaGenie 热加载模块开发模板。 |
| [模块提交页](https://cf.pandagenie.ai/sign) | 上传、签名并提交模块到官方流程。 |
| [SDK 注册页](https://cf.pandagenie.ai/sdk) | 注册可交互应用或 AI 助手应用，审核通过后进入 SDK 信任列表。 |

## 已具备能力

- 自然语言任务规划：逐步确认、全自动、Agent 执行、管家任务。
- 热加载模块：模块可声明文件、网络、定位、联系人、AI 模型等能力权限。
- 任务市场：共享现成任务，支持按使用模块、分类、语言筛选。
- 模块市场：安装、更新、查看模块能力和权限。
- 本地记忆：支持全应用记忆、隐私记忆、关闭记忆三种模式。
- 附件输入：图片、文件、拍照、语音等输入统一变成动态参数，分享任务时不会泄露本地路径。
- 执行追踪：面向普通用户展示步骤、权限、输入、输出和可复制/可打开结果。
- 开发者调试：调试页、模块 API smoke 测试、回归用例和发布验证脚本。

## 官方模块分工

`modules.json` 当前包含 **52 个官方模块**、**333 个 API**，最后更新于 **2026-05-15**。

| 类型 | 代表模块 | 主要能力 |
|---|---|---|
| 文件与文档 | `filemanager`, `file_stats`, `archive`, `document_tools`, `long_image_generator` | 文件增删改查、压缩解压、文档读取/转换/问答、长图生成。 |
| 图片与识别 | `image_tools`, `ocr`, `qrcode`, `color_picker` | 图片压缩转换、OCR、二维码生成/识别、颜色工具。 |
| 系统与设备 | `device_info`, `device_controls`, `battery`, `network_tools`, `system_cleaner` | 设备信息、亮度/音量/Wi-Fi、网络检查、存储体检和清理建议。 |
| 生活与效率 | `weather`, `reminder`, `notes`, `contacts`, `clipboard`, `password_gen` | 天气、日程、笔记、联系人、剪贴板、安全密码。 |
| 文本与 AI | `text_tools`, `translator`, `link_parser`, `hello_world` | 文本处理、翻译、网页解析、AI 摘要/改写/提取。 |
| 游戏与页面 | `tetris_game`, `snake_game`, `sudoku_game`, `gomoku_game`, `fullscreen_countdown` | 通过打开页面类 API 启动交互页面或游戏。 |
| 安全与开发 | `signature_checker`, `claw_*` | 签名校验、提示词安全、技能审查、工作流规划等。 |

完整目录请查看仓库中的 `modules.json` 或官网 [模块市场](https://cf.pandagenie.ai/marketplace)。

## 1.0.35 重点：本地记忆

PandaGenie 1.0.35 引入 App 级本地记忆。它不是普通模块，而是接入聊天、Agent、LLM 请求和设置页的数据层能力，用来在多轮会话之间保留偏好、任务习惯、纠错信息和常用上下文。

| 层级 | 实现 | 说明 |
|---|---|---|
| 设置入口 | `SettingsActivity` / `SettingsDataStore` | 管理全应用记忆、隐私记忆、关闭记忆三种模式。 |
| 存储与检索 | `data/memory/AppMemoryStore.kt` | 负责写入、去重、标签、保留周期、相关性排序和 prompt 构建。 |
| 敏感值处理 | `SecureVaultStore` + `MemoryVaultVariables` | 敏感值保存在本地保险箱，模型只看到变量名。 |
| 聊天接入 | `ChatViewModel` | 发送前准备安全文本、历史上下文和记忆上下文。 |
| LLM/Agent 注入 | `ChatTaskService` | 将 `memoryContext` 注入普通 LLM 和 Agent 系统提示。 |

## 下载

- 官方下载：[PandaGenie APK](https://cf.pandagenie.ai/app-update/latest-download)
- 当前版本：`versionName "1.0.35"` / `versionCode 10035`
- Release tag：[`20260515`](https://github.com/Rorschach123/PandaGenieSource/releases/tag/20260515)

## 编译

```powershell
cd E:\ProjectAI\PandaGenie\PandaGenie
.\gradlew.bat assembleRelease -PfinalRelease --no-daemon
```

## 模块开发

推荐路径：

1. 从 [PandaGenie-Module-Template](https://github.com/Rorschach123/PandaGenie-Module-Template) 创建模块仓库。
2. 在 `manifest.json` 中声明模块信息、API、参数、权限和中英文描述。
3. 使用 `module-dev-toolkit` 打包并用开发者签名。
4. 到 [模块提交页](https://cf.pandagenie.ai/sign) 上传、签名并发布。

## English

English documentation is available in [README_EN.md](README_EN.md).

## License

PandaGenie is licensed under LGPL-3.0.
