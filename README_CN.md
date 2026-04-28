# PandaGenie

AI 驱动的模块化 Android 手机助手。

PandaGenie 可以把自然语言需求变成手机上的真实执行动作。Android App 会根据当前安装模块的 `manifest.json` 自动构建能力上下文，让你选择的大模型规划任务，再由执行器逐步调用模块完成操作，并保留权限、输入输出和执行轨迹供用户检查。

[官方网站](https://cf.pandagenie.ai) | [模块市场](https://cf.pandagenie.ai/marketplace) | [提交模块](https://cf.pandagenie.ai/sign) | [Discord](https://discord.gg/Cfc7pjrjt2) | [English](README.md)

---

## 下载体验

当前最新 Android 版本：

- 版本号：`v1.0.13`
- Release 标签：[`20260427`](https://github.com/Rorschach123/PandaGenieSource/releases/tag/20260427)
- APK 下载：[`PandaGenie-v1.0.13.apk`](https://github.com/Rorschach123/PandaGenieSource/releases/download/20260427/PandaGenie-v1.0.13.apk)

上面的 APK 链接已按当前 GitHub Release 资产验证。Android 工程当前版本为 `versionName "1.0.13"` / `versionCode 13`。

---

## PandaGenie 做什么

PandaGenie 的定位是 Android 上的 AI 模块调度器：

1. 用户在聊天框里描述任务。
2. PandaGenie 读取已安装模块的能力清单并构建提示词。
3. 大模型返回结构化任务计划。
4. 执行器按步骤调用模块动作。
5. 用户可以查看任务配置、执行轨迹、权限、输入输出和耗时。

核心特点：

- 支持 OpenAI 兼容接口和其他可接入的大模型后端。
- 模块以 `.mod` 包热加载，新能力不需要重新编译主 App。
- `manifest.json` 是模块对 AI 暴露能力的唯一事实来源。
- 执行链路强调透明、安全、沙箱隔离、签名验证和用户可控。

---

## 当前仓库结构

本仓库维护 PandaGenie 的公开模块生态：

```text
PandaGenieSource/
├── README.md / README_CN.md        # 英文和中文说明
├── CONTRIBUTING.md / CONTRIBUTING_CN.md
├── modules.json                    # 模块市场索引，更新于 2026-04-28
├── modules/                        # 已签名发布的 .mod 模块包
├── source/                         # 官方模块源码
│   ├── shared_api/                 # 模块共用 API 和辅助类
│   ├── calculator/
│   ├── filemanager/
│   ├── archive/
│   └── ...                         # 当前共 37 个官方模块
├── module-dev-toolkit/             # 模块构建与签名 PowerShell 工具集
│   ├── mk_module.ps1
│   ├── init_dev_signing.ps1
│   ├── init_module_signing.ps1
│   ├── list_keystore_info.ps1
│   └── MODULE_DEVELOPMENT_GUIDE.md
├── docs/                           # 架构图和签名流程图
├── tools/                          # 辅助脚本
└── keys/                           # 本地签名密钥，已被 git 忽略
```

本次文档同步的结构变化：

- `modules.json` 已更新为 37 个官方模块，并为每个模块保留 CDN 下载地址和 GitHub raw `.mod` 地址。
- `source/shared_api` 成为模块共用 API 和辅助类入口，减少各模块重复维护接口定义。
- `module-dev-toolkit` 成为推荐的本地模块打包、签名和证书查看流程。
- 新增或较新的官方模块包括天气助手、OCR 文字识别、手电筒、翻译助手、指南针、URL 编解码、自我介绍等。
- 文件管理器、压缩解压、计算器等包含原生能力的模块，在各自目录下保留 `native/` 和 `jni_bridge/`。

---

## 模块包结构

一个模块源码目录通常长这样：

```text
source/my_module/
├── manifest.json                   # PandaGenie 和大模型读取的能力声明
├── index.html                      # 可选 H5 界面
├── plugin_src/                     # Java/Kotlin 插件实现
├── native/                         # 可选原生代码
├── jni_bridge/                     # 可选 JNI 桥接代码
└── libs/                           # 可选本地依赖，生成或下载内容会被忽略
```

编译后的发布产物是 `modules/` 目录下的 `.mod` 文件。

运行时 App 会读取 `manifest.json`，把模块 API 注入任务提示词，然后调用模块入口：

```java
public interface ModulePlugin {
    String invoke(Context context, String action, String paramsJson) throws Exception;
}
```

---

## 安全机制

PandaGenie 模块采用双签名分发模型：

| 签名层级 | 作用 |
| --- | --- |
| 开发者签名 | 标识模块作者，并将模块与 manifest 元数据绑定。 |
| 官方签名 | 表示模块通过官方审核，可被生产版本信任加载。 |

Android App 同时为模块私有存储做隔离：

- Java API 会把模块文件和缓存路径映射到每个模块自己的私有目录。
- 原生文件访问由 App 侧沙箱层限制，避免越权读取 App 私有数据或其他模块数据。
- 执行轨迹页面会展示模块调用、权限、输入、输出和耗时，便于用户审计。

---

## 官方模块

`modules.json` 当前包含 37 个官方模块，最后更新时间为 `2026-04-28`。

| 模块 | 版本 | 说明 |
| --- | ---: | --- |
| 计算器 | 1.3 | 支持四则运算、三角函数、对数、阶乘、排列组合和表达式解析。 |
| 文件管理器 | 2.2 | 支持目录浏览、文件增删改查、复制移动和搜索。 |
| 压缩解压 | 1.6 | 支持 ZIP、密码 ZIP、TAR、GZ、TAR.GZ 压缩与解压。 |
| 签名校验 | 1.5 | 校验 APK 和模块签名，区分官方签名与开发者签名。 |
| 应用管理 | 1.5 | 查看、启动、卸载应用，查看应用详情并跳转系统应用信息页。 |
| 文件信息统计 | 1.5 | 文件详情、哈希、对比、完整性校验、目录统计、重复文件和大文件扫描。 |
| 提醒助手 | 1.4 | 日历事件、闹钟、倒计时、生日提醒和近期日程查询。 |
| 文本工具 | 1.5 | 字数统计、Base64、URL 编解码、正则、文本转换、UUID 和文本哈希。 |
| 设备信息 | 1.5 | 机型、系统、CPU、内存、存储、屏幕参数和设备摘要。 |
| 图片工具 | 1.5 | 图片信息、缩放、压缩、格式转换、旋转和裁剪。 |
| 剪贴板管理 | 1.5 | 读取、设置、清空剪贴板和管理剪贴板历史。 |
| 电池管理 | 1.5 | 查看电量、充电状态、健康状况、温度、电压等信息。 |
| 网络工具 | 1.5 | Ping、DNS 查询、本机/公网 IP、联网检查和网络信息。 |
| 联系人管理 | 1.5 | 搜索、查看、列出、导出联系人并查找重复联系人。 |
| 笔记助手 | 1.5 | 本地笔记创建、查看、编辑、删除、搜索和导出。 |
| 每日运势 | 1.3 | 按日期和姓名生成个性化运势，支持农历日期转换。 |
| 骰子工具 | 1.2 | 掷骰、目标点数、大小判定、豹子、组合枚举和概率统计。 |
| LED 灯牌 | 1.3 | 滚动、浮现、静止文字横幅，支持颜色、渐变、字号和特效。 |
| 系统清理 | 1.6 | 扫描清理临时文件、缓存、空文件夹、缩略图缓存和 APK 安装包。 |
| 颜色工具 | 1.4 | HEX/RGB/HSL/CMYK 互转、配色方案、随机色和 CSS 命名色匹配。 |
| 单位转换 | 1.4 | 长度、重量、温度、面积、体积、速度、时间、数据存储等单位互转。 |
| 密码生成器 | 1.6 | 强密码、助记密码短语、自定义复杂度和密码强度检测。 |
| 二维码工具 | 1.6 | 生成二维码并从图片中识别二维码。 |
| 贪吃蛇 | 1.2 | 经典贪吃蛇游戏，支持难度设置。 |
| 种菜游戏 | 1.2 | 种植、浇水、施肥、除草、收获、记录保存和定时任务。 |
| 五子棋 | 1.2 | 15x15 棋盘人机对战，率先五子连珠获胜。 |
| 俄罗斯方块 | 1.2 | 控制方块移动旋转，消行计分，支持难度设置。 |
| 数独 | 1.2 | 9x9 数独题目生成和难度设置。 |
| 井字棋 | 1.2 | 经典 3x3 人机对战。 |
| 链接解析 | 1.3 | 提取网页标题、描述、图片、链接、下载文件、HTTP 头和内容类型。 |
| 天气助手 | 1.4 | 使用 Open-Meteo 免费 API 查询当前天气和多日天气预报。 |
| OCR 文字识别 | 1.3 | 从图片中提取中英文文字，支持自动语言检测。 |
| 手电筒 | 1.3 | 开关闪光灯并查询当前状态。 |
| 翻译助手 | 1.4 | 支持中、英、日、韩、法、德、西等多语言互译。 |
| 指南针 | 1.3 | 使用设备传感器获取方位角和基本方位信息。 |
| URL 编解码 | 1.2 | URL 编码、解码和组件解析。 |
| 自我介绍 | 1.1 | 当用户询问“你是谁”“能做什么”等问题时返回助手介绍和能力列表。 |

也可以在 [模块市场](https://cf.pandagenie.ai/marketplace) 浏览全部模块。

---

## 构建和发布模块

推荐使用模块开发工具集：

```powershell
cd PandaGenieSource
.\module-dev-toolkit\mk_module.ps1 -Module calculator
```

常用辅助脚本：

```powershell
.\module-dev-toolkit\init_dev_signing.ps1
.\module-dev-toolkit\init_module_signing.ps1
.\module-dev-toolkit\list_keystore_info.ps1
```

完整模块开发流程请阅读：

- [`module-dev-toolkit/MODULE_DEVELOPMENT_GUIDE.md`](module-dev-toolkit/MODULE_DEVELOPMENT_GUIDE.md)
- [`CONTRIBUTING_CN.md`](CONTRIBUTING_CN.md)

---

## 更新日志

### v1.0.13 - 2026-04-27

- 更新最新 APK 下载地址到 `20260427` GitHub Release 资产。
- 同步当前 37 个官方模块的模块市场索引和说明。
- 补充 `shared_api`、模块开发工具集和新增官方模块相关结构说明。
- 本轮 App 侧包含任务卡片操作按钮显示修复、收藏任务定时条件弹窗、收藏重复判断、欢迎页熊猫品牌图优化、注册密码二次确认等优化。

### v1.0.12 - 2026-04-26

- 新增 Hello World 自我介绍模块。
- 优化悬浮窗关闭行为。
- 增加能力边界说明。

### v1.0.11 - 2026-04-23

- 引入统一矢量图标系统。
- 移除核心用户界面中的装饰性 emoji。
- 优化授权卡片展示。
- 改进模块删除清理和提示词匿名分析。

---

## 许可证

本仓库遵循 [`LICENSE`](LICENSE) 中的开源许可证。
