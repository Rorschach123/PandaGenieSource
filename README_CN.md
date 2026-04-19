<div align="center">

<h1>&#x1F43C; PandaGenie</h1>

<p><strong>AI 驱动的模块化 Android 助手</strong></p>

<p>
用自然语言告诉 PandaGenie 你的需求，<br/>
它会自动规划、执行并返回结果 — 由<strong>任意大模型</strong>驱动，搭配持续增长的<strong>热加载模块库</strong>。
</p>

<p>
  <a href="https://cf.pandagenie.ai">官方网站</a> &nbsp;&#x2022;&nbsp;
  <a href="https://discord.gg/Cfc7pjrjt2">Discord</a> &nbsp;&#x2022;&nbsp;
  <a href="#加入我们--欢迎所有开发者">加入我们</a> &nbsp;&#x2022;&nbsp;
  <a href="https://cf.pandagenie.ai/sign">提交模块</a> &nbsp;&#x2022;&nbsp;
  <a href="https://cf.pandagenie.ai/marketplace">模块市场</a> &nbsp;&#x2022;&nbsp;
  <a href="#创建你的模块">创建模块</a> &nbsp;&#x2022;&nbsp;
  <a href="README.md">&#x1F1EC;&#x1F1E7; English</a>
</p>

</div>

---

## 为什么做 PandaGenie

生成式 AI 的爆发正在掀起一场新的互联网革命，它与 PC 互联网、移动互联网一脉相承 — 算力被量化为 Token，就像流量之于运营商。如果这是一次新的历史轮回，那 ChatGPT 的问世就是元年，之后将涌现大量基于 AI 的全新应用形态。

回到本质：计算机世界的一切操作，归根结底都是**计算**。用户要的是**结果**，不是过程。既然如此，AI 的价值就在于**串联所有环节** — 将用户的自然语言需求，直接转化为可执行的结果。

PandaGenie 正是从这个原点出发：**让智能手机变成真正的 AI 助理**，用户的任何需求都可以通过一个对话框完成。

### 设计原则

| 原则 | 说明 |
|------|------|
| **透明安全** | AI 执行的每一步操作、访问的每一项数据完全可见可控。模块代码开源透明，接受全网审计 |
| **低 Token 消耗** | Token 如同流量套餐，不是无限的。通过精确的 prompt 工程和任务规划，最大限度降低消耗 |
| **面向普通用户** | 用户只需知道"我要做什么"，不需要知道"点哪个按钮、走几步流程" |
| **连接开发者与用户** | 开发者构建模块，模块承载能力，AI 按用户指令调度执行 — 一种全新的开发者-用户协作模式 |

---

## 工作原理

> "把 /Download 里所有照片压缩成一个 zip" — 你只需要说这一句话。

PandaGenie 连接你选择的大模型（GPT、Claude、DeepSeek 或任何 OpenAI 兼容 API），读取所有已安装模块的能力描述，自动规划多步骤任务。不需要写代码，不需要在菜单里翻来翻去。

<p align="center">
  <img src="docs/architecture.svg" width="100%" alt="PandaGenie 架构图" />
</p>

**核心亮点：**

- **任意大模型后端** — OpenAI、Claude、DeepSeek、本地部署，或任何兼容 API
- **热加载模块** — 放入 `.mod` 文件，重启 APP 即生效，无需重新编译 APK
- **AI 自动发现能力** — 新模块的 API 自动注入 AI 提示词
- **沙箱执行** — 文件访问、网络、权限按模块独立管控，双层权限拦截机制
- **双重签名安全** — 防篡改模块验证机制

---

## 架构设计

PandaGenie 将 **AI 大脑** 与 **模块生态** 完全解耦：

```
用户  ──>  AI 引擎  ──>  任务执行器  ──>  模块运行时  ──>  Plugin.invoke()
             │                │                 │
        从模块构建提示词     逐步执行          沙箱 + 签名验证
                          变量解析           独立 ClassLoader
```

APP **不硬编码**任何模块信息。所有能力均由模块的 `manifest.json` 声明，运行时动态注入 AI 系统提示词。

---

## 模块系统

每个 `.mod` 文件是一个自包含的模块包：

<p align="center">
  <img src="docs/mod-structure.svg" width="100%" alt=".mod 文件结构" />
</p>

模块只需实现**一个接口**：

```java
public interface ModulePlugin {
    String invoke(Context context, String action, String paramsJson) throws Exception;
}
```

AI 读取你的 `manifest.json`，理解模块能做什么，然后自动调用 `invoke()` 并传入正确的 `action` 和参数。

---

## 安全机制：双重签名

每个 `.mod` 携带两层 JAR 签名，确保分发安全：

<p align="center">
  <img src="docs/signing-flow.svg" width="100%" alt="双重签名流程" />
</p>

| 签名层 | 用途 |
|--------|------|
| **DEV**（开发者） | 标识模块作者身份，指纹绑定到 manifest |
| **OFFICIAL**（官方） | 证明模块通过官方审核，使用 APP 内嵌证书验证 |

开发者模式下允许加载仅有 DEV 签名的模块，方便测试。

---

## 已有模块

| 模块 | 功能描述 | 类型 |
|------|---------|------|
| &#x1F4C1; **文件管理器** | 浏览、创建、复制、移动、删除、搜索文件 | 原生 |
| &#x1F9EE; **计算器** | 科学计算：表达式、三角函数、对数、阶乘 | 原生 |
| &#x1F4E6; **压缩解压** | ZIP（支持密码）、TAR、GZ 压缩解压 | 原生 |
| &#x1F4F1; **应用管理** | 查看、启动、卸载应用，查看应用详情 | Java |
| &#x1F4CA; **文件统计** | 哈希计算、文件对比、目录统计、重复文件查找 | Java |
| &#x23F0; **提醒助手** | 日历事件、闹钟、倒计时、生日提醒 | Java |
| &#x1F50F; **签名校验** | 验证 APK 和模块签名信息 | Java |
| &#x1F4DD; **文本工具** | 字数统计、编码转换、哈希、正则、文本对比 | Java |
| &#x1F4F1; **设备信息** | CPU、内存、存储、电池、传感器信息 | Java |
| &#x1F5BC;&#xFE0F; **图片工具** | 缩放、压缩、旋转、元数据、格式转换 | Java |
| &#x1F4CB; **剪贴板** | 复制、粘贴、历史记录、清空剪贴板 | Java |
| &#x1F50B; **电池管理** | 电池状态、健康度、温度、充电信息 | Java |
| &#x1F310; **网络工具** | Ping、DNS查询、端口扫描、网速测试 | Java |
| &#x1F4C7; **通讯录** | 搜索、添加、编辑、删除联系人 | Java |
| &#x1F4D3; **备忘录** | 创建、编辑、搜索、整理笔记 | Java |
| &#x1F3B2; **魔法骰子** | 掷骰子、随机数、抛硬币 | Java |
| &#x1F3AF; **每日运势** | 每日运势、随机名言 | Java |
| &#x1F4A1; **LED灯牌** | 滚动文字横幅，支持横屏播放 | H5 |
| &#x1F9F9; **系统清理** | 扫描清理临时文件、缓存 | Java |
| &#x1F3A8; **颜色工具** | HEX/RGB/HSL互转、调色板生成 | Java |
| &#x1F4CF; **单位转换** | 长度、重量、温度、速度、数据单位 | Java |
| &#x1F511; **密码生成** | 安全密码和助记短语生成器 | Java |
| &#x1F4F7; **二维码** | 生成和识别二维码 | H5+Java |
| &#x1F40D; **贪吃蛇** | 经典贪吃蛇，支持难度设置 | H5+Java |
| &#x1FA86; **俄罗斯方块** | 经典俄罗斯方块 | H5+Java |
| &#x1F9E9; **数独** | 9x9数独谜题，支持提示 | H5+Java |
| &#x26AB; **五子棋** | 五子棋人机对弈 | H5+Java |
| &#x274E; **井字棋** | 经典井字棋人机对弈 | H5+Java |
| &#x1F517; **链接解析** | 解析URL、提取标题/图片/链接/下载文件、检查可访问性 | Java |
| &#x1F331; **开心农场** | 种植、浇水、收获模拟经营 | H5+Java |

> &#x1F4E6; **[在模块市场浏览所有模块](https://cf.pandagenie.ai/marketplace)** — 或在下方了解如何**创建你自己的模块**！

---

## 下载体验

> &#x1F4E5; **[下载 APK (v1.0.7)](https://github.com/Rorschach123/PandaGenieSource/releases/latest/download/app-release.apk)**

---

## 更新日志

<details open>
<summary><b>v1.0.7</b> (2026-04-18)</summary>

- **执行追踪（行为查看）** — 任务完成后，点击"执行追踪"按钮即可查看完整的图形化流程图：涉及的每个模块、输入输出数据、使用的权限、数据访问路径以及每步耗时 — 全部以直观的垂直流程展示。点击任意步骤卡片可展开查看详细的输入输出字段、权限授予情况和数据操作记录
- **零 Token 配置市场匹配** — 在调用大模型之前，PandaGenie 会先在共享配置市场中搜索匹配的任务配置。如果找到高置信度的匹配，将直接执行 — **完全绕过大模型，实现零 Token 消耗**。可在设置 → 模块中开关此功能
- **智能 LLM 响应处理** — 非 JSON 格式的大模型回复（如额度用尽、日常对话回复、错误信息）现在能被正确识别并友好展示，而不再显示"JSON格式不正确"的错误。同时提供可操作的建议引导用户解决额度问题
- **首次打开欢迎体验** — 新用户看到的是友好的熊猫问候语和交互式建议按钮（"逛逛模块商店"、"你能做什么？"等），而不是冷冰冰的"尚未安装任何功能"

</details>

<details>
<summary><b>v1.0.6</b> (2026-04-18)</summary>

- **配置市场定时调度** — 条件执行现在支持完整的任务调度器（单次 / 每天 / 每周 / 每月 / 事件触发）
- **配置市场删除** — 自己上传的配置显示醒目的删除按钮
- **本地能力响应** — 未配置大模型时，询问"你能做什么"返回本地能力列表和配置引导
- **聊天反馈** — 直接从输入栏提交反馈
- **数据保险库** — 使用主密码的安全加密存储，可从安全设置访问
- **文件管理器 v1.8** — 批量移动/复制/删除、隐藏文件过滤、同目录跳过、搜索类型筛选（`file`/`dir`/`all`）、提高显示上限
- **沙箱权限修复** — "允许所有目录"授权现在能正确跨 `/sdcard` ↔ `/storage/emulated/0` 路径格式生效
- **模块名称修复** — 沙箱提示中正确显示国际化模块名称而非原始 JSON
- **更新检查** — 间隔缩短为30分钟，加快更新推送
- 模块市场更新 35+ 模块

</details>

---

## 创建你的模块

开发 PandaGenie 模块**超级简单** — 特别适合用 AI 编程助手（如 Cursor）进行 vibe coding。

### 使用模块模板快速开始（推荐）

最快的起步方式 — 点击下方按钮，一键从模板创建你自己的模块仓库：

[![Use this template](https://img.shields.io/badge/%E4%BD%BF%E7%94%A8%E6%A8%A1%E6%9D%BF-一键创建-6c5ce7?style=for-the-badge)](https://github.com/Rorschach123/PandaGenie-Module-Template/generate)

或手动克隆：

```bash
git clone https://github.com/Rorschach123/PandaGenie-Module-Template.git my-awesome-module
```

模板包含一个可运行的示例模块（`manifest.json`、`MyModulePlugin.java`、`index.html`）— 重命名、修改、编译即可。

### 只需 3 个文件

```
source/my_module/
├── manifest.json      ← 告诉 AI 你能做什么
├── index.html         ← 可选的 UI 页面
└── plugin_src/
    └── .../MyPlugin.java   ← 你的逻辑
```

### 快速示例

**manifest.json** — 描述你的 API：

```json
{
  "id": "my_module",
  "name": "我的模块",
  "name_en": "My Module",
  "description": "做一些很酷的事情",
  "description_en": "Does something cool",
  "version": "1.0",
  "apis": [
    {
      "name": "doSomething",
      "desc": "执行操作",
      "desc_en": "Does the thing",
      "params": ["input"],
      "paramDesc": ["输入内容"],
      "paramDesc_en": ["The input"]
    }
  ]
}
```

**MyPlugin.java** — 实现一个方法：

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
                .put("_displayText", "| 项目 | 值 |\n|---|---|\n| 结果 | hello |")
                .toString();
        }
        return new JSONObject().put("success", false).put("error", "Unknown action").toString();
    }
}
```

### 插件输出格式

每次 `invoke()` 调用必须返回包含以下字段的 JSON 字符串：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `success` | boolean | 是 | 操作是否成功 |
| `output` | string | 是 | 机器可读结果（结构化数据用 JSON 字符串） |
| `error` | string | 失败时 | 人类可读的错误信息 |
| `_displayText` | string | 否 | 聊天中展示的富文本（支持 Markdown 表格、链接、粗体） |
| `_openModule` | boolean | 否 | 为 `true` 时 APP 会打开模块的 HTML 界面 |

**富文本格式** — `_displayText` 字段支持：

- **Markdown 表格** — `| 列1 | 列2 |\n|---|---|\n| 值1 | 值2 |` → 渲染为 Unicode 表格
- **粗体** — `**文本**` → 加粗显示
- **链接** — `[文本](url)` 或裸链接 `https://...` → 可点击
- **行内代码** — `` `code` `` → 等宽字体高亮

表格输出示例：

```java
private String formatResult(JSONObject data) {
    StringBuilder sb = new StringBuilder();
    sb.append("📊 分析结果\n\n");
    sb.append("| 指标 | 值 |\n");
    sb.append("|---|---|\n");
    sb.append("| 文件数 | ").append(data.optInt("count")).append(" |\n");
    sb.append("| 总大小 | ").append(data.optString("size")).append(" |\n");
    return sb.toString();
}
```

### `.mod` 文件格式

`.mod` 文件是一个签名的 ZIP 压缩包，结构如下：

```
my_module.mod (ZIP)
├── manifest.json          # 模块元数据、API 定义、权限声明
├── plugin.jar             # 编译后的插件（包含 DEX 字节码）
├── index.html             # 可选：模块 UI 页面
├── common.css             # 可选：共享样式表
├── META-INF/
│   ├── MANIFEST.MF        # JAR 清单
│   ├── DEV.SF / DEV.RSA   # 开发者签名
│   └── OFFICIAL.SF / ...  # 官方签名（审核通过后）
└── libs/                  # 可选：原生库
    ├── arm64-v8a/
    │   └── libmodule.so
    └── armeabi-v7a/
        └── libmodule.so
```

`plugin.jar` 内部包含 DEX 字节码（非标准 Java 字节码），由 Android `d8` 工具转换生成。打包脚本会自动处理此转换。

### 本地构建与测试

```powershell
# 在 PandaGenieSource/module-dev-toolkit/ 下（或仓库根目录下的 module-dev-toolkit/）
.\mk_module.ps1 -Action init-dev-signing    # 仅首次需要
.\mk_module.ps1 -Action pack -Modules "my_module"

adb push ..\modules\my_module.mod /sdcard/PandaGenie/modules/
```

### 获取官方签名与发布

模块开发完成后，前往 **[https://cf.pandagenie.ai/sign](https://cf.pandagenie.ai/sign)** 上传你的 `.mod` 文件。系统会自动验证模块并加盖官方签名 — 随后你可以一键发布到模块市场。

完整开发指南请参考 [module-dev-toolkit/MODULE_DEVELOPMENT_GUIDE.md](module-dev-toolkit/MODULE_DEVELOPMENT_GUIDE.md)（[GitHub 上的副本](https://github.com/Rorschach123/PandaGenieSource/blob/main/module-dev-toolkit/MODULE_DEVELOPMENT_GUIDE.md)）。

---

## 项目结构

模块源码、构建脚本、打包产物（`modules/`）、`modules.json` 与 `module-dev-toolkit/` 均在本仓库。另有一个独立的模板仓库用于快速新建模块项目：

| 仓库 | 用途 |
|------|------|
| **[PandaGenieSource](.)** （本仓库） | 模块源码（`source/`）、`tools/`、`module-dev-toolkit/`、构建输出的 `.mod`（`modules/`）及 `modules.json` |
| **[PandaGenie-Module-Template](https://github.com/Rorschach123/PandaGenie-Module-Template)** | GitHub 模板仓库 — 一键创建新模块项目 |

```
PandaGenieSource/
├── source/                    # 模块源码
│   ├── shared_api/            # ModulePlugin 接口
│   ├── calculator/
│   ├── filemanager/
│   ├── archive/
│   ├── app_manager/
│   ├── file_stats/
│   ├── reminder/
│   └── signature_checker/
├── module-dev-toolkit/        # mk_module.ps1、签名初始化、开发指南
├── modules/                   # 打包输出的 .mod（由打包脚本生成）
├── modules.json               # 类市场模块索引（打包时更新）
└── tools/
    ├── pack_modules.ps1       # 打包签名 .mod 文件
    └── build_all_native.ps1   # 编译原生库
```

---

## 加入我们 — 欢迎所有开发者！

> **我们相信最好的模块将来自社区，而不仅仅是我们自己。**

[![Discord](https://img.shields.io/discord/1234567890?color=5865F2&logo=discord&logoColor=white&label=Discord)](https://discord.gg/Cfc7pjrjt2)

**加入 Discord 社区：** [https://discord.gg/Cfc7pjrjt2](https://discord.gg/Cfc7pjrjt2) — 讨论想法、获取帮助、分享模块，与其他开发者协作。

PandaGenie 是一个**共创平台** — 我们真诚地邀请各种水平的开发者加入，共建更丰富的模块生态。无论你是经验丰富的 Android 开发者，还是上周才借助 AI 助手学会编程的新手，**这里都有你的位置**。

### 为什么要开发 PandaGenie 模块？

- **门槛极低** — 3 个文件，一个 Java 接口，搞定。你完全可以用 **vibe coding** 的方式，让 Cursor 等 AI 编程助手帮你生成完整的模块代码。整个项目本身就是这样构建的。
- **即刻分发** — 你的模块将通过内置模块市场触达所有 PandaGenie 用户
- **收益共享** — 如果 PandaGenie 未来产生收益（付费功能、捐赠、赞助等），**模块开发者将按其模块的使用量和贡献度获得相应的收益分成**。我们承诺让这个平台成为贡献者能够得到公平回报的生态。

### 如何提交你的模块

有**两种方式**可以让你的模块获得官方签名并发布：

#### 方式一：在线签名平台（推荐）

访问 **[https://cf.pandagenie.ai/sign](https://cf.pandagenie.ai/sign)** — PandaGenie 官方模块签名服务：

1. 使用开发者工具包在本地构建你的 `.mod` 文件
2. 在网页上上传 — 系统将自动验证文件格式、开发者签名和安全检查
3. 如果一切通过，官方签名将被自动应用，你可以**下载已签名的 `.mod` 文件**
4. 系统还会询问你是否要**发布到模块市场** — 一键即可上架！

#### 方式二：Pull Request

1. **Fork** 本仓库
2. 在 `source/<your_module_id>/` 下创建你的模块
3. 在 APP 中开启开发者模式测试
4. **提交 Pull Request** — 审核通过后由官方签名发布

### 共创要求

- **代码透明开放** — 所有模块代码公开可审计，保证模块行为可信
- **保管好开发者签名** — 先对模块进行开发者签名，提交审核后由官方签名发布
- API 描述清晰准确（AI 会读取它来理解能力！）
- 支持中英双语（`_en` 后缀字段）
- 最小权限原则 — 只申请必要的权限

### 模块灵感

模块生态在快速成长 — 仍有**很多值得构建的方向**：

- &#x1F4E7; **短信管理** — 搜索、导出短信
- &#x1F3B5; **音频工具** — 元数据编辑、格式转换
- &#x1F4CD; **位置工具** — 附近地点、坐标转换
- &#x1F4C8; **健康追踪** — 步数、睡眠、运动记录
- &#x1F4B0; **财务工具** — 记账、汇率换算
- &#x1F4E2; **社交工具** — 跨平台分享内容
- ...以及任何你能想到的功能！

> 你构建的每一个模块，都让 PandaGenie 对所有用户变得更智能。**让我们一起构建 AI 驱动移动端的未来。**

### 加入社区

[![Discord](https://img.shields.io/badge/Discord-加入我们-5865F2?logo=discord&logoColor=white)](https://discord.gg/Cfc7pjrjt2)

有问题？想展示你的模块？需要帮助？加入我们的 **[Discord 服务器](https://discord.gg/Cfc7pjrjt2)** — 期待与你交流。

---

## 参与贡献

欢迎各种形式的贡献！详见 **[贡献指南](CONTRIBUTING_CN.md)** ：

- 构建和提交新模块
- 报告 Bug 和建议新功能
- 代码规范和 PR 流程

---

## 贡献者

<a href="https://github.com/Rorschach123/PandaGenieSource/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=Rorschach123/PandaGenieSource" />
</a>

---

## 技术栈

| 组件 | 技术 |
|------|------|
| APP | Kotlin、Jetpack Compose、Material 3 |
| AI | 任意 OpenAI 兼容 / Claude API |
| 模块 | Java 插件、DEX ClassLoader、可选 JNI/C++ |
| 签名 | PKCS12 密钥库、jarsigner、DPAPI |
| 构建 | PowerShell、Android SDK（d8、javac） |

---

## 开源协议

本项目采用 LGPL-3.0 协议开源。详见 [LICENSE](LICENSE)。

---

<div align="center">

**Built with &#x2764;&#xFE0F; and a lot of vibe coding**

*PandaGenie — 让 AI 帮你处理手机上的琐事*

[![Discord](https://img.shields.io/badge/Discord-加入社区-5865F2?logo=discord&logoColor=white)](https://discord.gg/Cfc7pjrjt2)

</div>
