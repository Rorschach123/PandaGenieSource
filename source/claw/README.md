# claw modules

This directory records the ClawHub skill adaptation batch.

ClawHub's top downloaded skills currently surface agent-oriented tools such as
Self-Improving Agent, Skill Vetter, Ontology, Humanizer, GitHub, Gog,
Polymarket, Weather, ClawHub Curation, and Auto Document Updater. For the
mobile module market, this batch keeps the parts that can run inside
PandaGenie's Android module sandbox without desktop shells, browser automation,
GitHub accounts, trading APIs, or long-running background agents.

Implemented by developer `claw`:

| Module | Source skill idea | Mobile adaptation |
| --- | --- | --- |
| `claw_skill_vetter` | Skill Vetter | Local text/file/URL scanner for risky skill instructions and prompt-injection patterns. |
| `claw_ontology` | Ontology | Small private knowledge graph stored under the module sandbox. |
| `claw_humanizer` | Humanizer | LLM-backed rewrite helper for natural, readable mobile text. |
| `claw_skill_discovery` | Top ClawHub Skills / ClawHub Curation | Network lookup for top, certified, newest, and searched ClawHub skills. |
| `claw_doc_updater` | Auto Document Updater | Local document comparison, changelog, update plan, and todo extraction helper. |
| `claw_workflow_planner` | Automation Workflows / Proactive Agent | LLM-backed mobile workflow planner plus local checklist and risk checks. |
| `claw_prompt_shield` | SkillScan / Shield CN | Local Chinese-aware prompt injection, secret leak, and risky-action scanner. |

Skipped for this batch:

- Self-Improving Agent: requires autonomous code edits and tool loops that are not appropriate for a mobile module sandbox.
- GitHub, Gog, Polymarket: account/API-heavy workflows with stronger desktop or server assumptions.
- Weather: PandaGenie already has a maintained weather module, so this batch avoids a duplicate market entry.
- Full autonomous Auto Document Updater and ClawHub Curation agents: adapted into local doc planning and read-only discovery modules instead of background repo automation.
