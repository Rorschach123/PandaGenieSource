# ClawHub 适配模块

这个目录记录从 ClawHub 技能思路迁移到 PandaGenie Android 模块沙箱的一批模块。适配原则是：保留适合手机本地执行、可由用户明确触发、权限边界清晰的能力；跳过依赖桌面 shell、浏览器自动化、账号交易 API 或长期后台 Agent 的能力。

## 已实现模块

| 模块 | 来源思路 | 移动端适配方式 |
|---|---|---|
| `claw_skill_vetter` | Skill Vetter | 本地扫描文本、文件和 URL，识别风险指令和 prompt injection。 |
| `claw_ontology` | Ontology | 在模块沙箱中维护小型私有知识图谱。 |
| `claw_humanizer` | Humanizer | 使用 LLM 把文本改写得更自然、易读。 |
| `claw_skill_discovery` | ClawHub Curation | 通过网络查询热门、认证、最新和搜索到的 ClawHub 技能。 |
| `claw_doc_updater` | Auto Document Updater | 本地文档对比、changelog、更新计划和 todo 提取。 |
| `claw_workflow_planner` | Automation Workflows | 使用 LLM 规划手机工作流，并生成本地检查清单和风险提示。 |
| `claw_prompt_shield` | SkillScan / Shield CN | 中文友好的 prompt injection、密钥泄露和风险动作扫描。 |

## 暂未适配

- Self-Improving Agent：需要自主代码修改和长工具循环，不适合手机模块沙箱。
- GitHub、Gog、Polymarket：依赖账号、API 或桌面环境假设较强。
- Weather：PandaGenie 已有维护中的天气模块，避免重复。
- 完整后台版 Auto Document Updater / ClawHub Curation：已收敛为本地文档规划和只读发现能力。

## English

This folder tracks a batch of PandaGenie modules adapted from ClawHub skill ideas.

The mobile adaptation keeps capabilities that can run safely inside PandaGenie's Android module sandbox, while skipping desktop shell workflows, browser automation, trading APIs, account-heavy integrations, and long-running autonomous agents.
