package ai.rorsch.moduleplugins.hello_world;

import android.content.Context;
import ai.rorsch.pandagenie.module.runtime.HtmlOutputHelper;
import ai.rorsch.pandagenie.module.runtime.ModulePlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * PandaGenie「自我介绍」模块插件。
 * <p>
 * 当用户询问「你是谁」「能做什么」「怎么用」等问题时由 AI 自动调用，
 * 从服务端实时获取模块列表，返回结构化的自我介绍、能力概览和使用技巧。
 */
public class HelloPlugin implements ModulePlugin {

    private static final String API_BASE = "https://cf.pandagenie.ai";
    private static final int TIMEOUT = 8000;
    private static final Random RANDOM = new Random();

    private static boolean isZh() {
        return Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
    }

    @Override
    public String invoke(Context context, String action, String paramsJson) throws Exception {
        try {
            JSONObject params = new JSONObject(emptyJson(paramsJson));
            boolean zh = isZh();
            switch (action) {
                case "introduce":
                    return doIntroduce(zh);
                case "capabilities":
                    return doCapabilities(zh, params);
                case "usage_tips":
                    return doUsageTips(zh);
                default:
                    return error("Unsupported action: " + action);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            return error(msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName());
        }
    }

    // ── 从服务端拉取模块列表 ──

    /**
     * 从 /modules/list API 获取所有已发布模块，失败时返回空数组（降级为静态介绍）。
     */
    private JSONArray fetchModules(boolean zh) {
        try {
            String urlStr = API_BASE + "/modules/list?locale=" + (zh ? "zh" : "en");
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestProperty("User-Agent", "PandaGenie-Hello/1.0");
            try {
                int code = conn.getResponseCode();
                if (code != 200) return new JSONArray();
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                JSONObject json = new JSONObject(sb.toString());
                if (json.optBoolean("success", false)) {
                    return json.optJSONArray("data") != null ? json.getJSONArray("data") : new JSONArray();
                }
                return new JSONArray();
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    // ── introduce ──

    private String doIntroduce(boolean zh) throws Exception {
        JSONArray mods = fetchModules(zh);
        int total = mods.length();

        JSONObject out = new JSONObject();
        out.put("name", "PandaGenie");
        out.put("type", zh ? "AI 手机助手" : "AI Phone Assistant");
        out.put("moduleCount", total);

        // 随机抽取 3 个模块作为亮点展示
        List<String> highlights = pickRandomModules(mods, 3);

        StringBuilder sb = new StringBuilder();
        sb.append((zh
                ? "🐼 PandaGenie — 你的 AI 手机助手\n\n"
                + "你好！我是 PandaGenie，一个开源、免费、无广告的安卓 AI 助手。\n\n"
                + "和普通的聊天机器人不同，我不仅能对话，还能真正帮你操作手机——"
                + "整理文件、查天气、翻译文字、生成密码、OCR 识别……"
                + "你说一句话，我就自动规划步骤、调用模块、完成任务。\n\n"
                + "我的核心理念是「模块化」：每项功能都是一个独立模块，装了就有，不装就没有。"
                : "🐼 PandaGenie — Your AI Phone Assistant\n\n"
                + "Hi! I'm PandaGenie, an open-source, free, ad-free Android AI assistant.\n\n"
                + "Unlike regular chatbots, I can actually operate your phone — "
                + "organize files, check weather, translate text, generate passwords, OCR recognition... "
                + "Just say what you want, and I'll plan the steps, invoke modules, and complete the task.\n\n"
                + "My core concept is 'modularity': each feature is an independent module."
        ) + "\n\n");

        if (total > 0) {
            sb.append(zh
                    ? "目前已有 " + total + " 个功能模块，而且你还可以自己开发模块扩展我的能力。\n\n"
                    : "Currently " + total + " modules available, and you can develop your own to extend my capabilities.\n\n"
            );
            if (!highlights.isEmpty()) {
                sb.append(zh ? "✨ 亮点模块随机推荐：\n" : "✨ Random module highlights:\n");
                for (String h : highlights) {
                    sb.append("  · ").append(h).append("\n");
                }
                sb.append("\n");
            }
        } else {
            sb.append(zh
                    ? "目前已有数十个功能模块，覆盖文件、图片、网络、系统、效率、娱乐等场景。\n\n"
                    : "Currently dozens of modules available, covering files, images, network, system, productivity, and entertainment.\n\n"
            );
        }

        sb.append(zh
                ? "🌐 官网：pandagenie.ai\n💻 GitHub：github.com/Rorschach123/PandaGenieSource"
                : "🌐 Website: pandagenie.ai\n💻 GitHub: github.com/Rorschach123/PandaGenieSource");

        String moduleMetric = total > 0 ? String.valueOf(total) : "30+";
        String html = HtmlOutputHelper.card(
                "🐼",
                zh ? "关于 PandaGenie" : "About PandaGenie",
                HtmlOutputHelper.metricGrid(new String[][]{
                        {zh ? "AI 手机助手" : "AI Phone Assistant", zh ? "定位" : "Type"},
                        {moduleMetric, zh ? "功能模块" : "Modules"},
                        {zh ? "开源免费" : "Open & Free", zh ? "价格" : "Price"},
                        {zh ? "无广告" : "No Ads", zh ? "体验" : "Experience"}
                })
                + HtmlOutputHelper.muted(zh
                        ? "模块化设计：装了就有，不装就没有 — 扩展 AI 的能力边界"
                        : "Modular design: install to enable, uninstall to remove — extending AI's capability boundary")
        );

        return ok(out, sb.toString().trim(), html);
    }

    // ── capabilities ──

    private String doCapabilities(boolean zh, JSONObject params) throws Exception {
        JSONArray mods = fetchModules(zh);
        int total = mods.length();

        JSONObject out = new JSONObject();
        out.put("moduleCount", total);

        if (total == 0) {
            // 降级：服务端不可用时用静态数据
            return buildStaticCapabilities(zh, out);
        }

        // 按 modules.json 的模块列表分组展示
        StringBuilder sb = new StringBuilder();
        sb.append(zh
                ? "📦 我的能力概览（共 " + total + " 个模块）\n\n"
                + "以下是我目前所有可用的功能模块：\n\n"
                : "📦 My Capabilities (" + total + " modules total)\n\n"
                + "Here are all currently available modules:\n\n");

        // 列出所有模块名称和简介
        for (int i = 0; i < mods.length(); i++) {
            JSONObject m = mods.getJSONObject(i);
            String name = m.optString("name", m.optString("id", "?"));
            String desc = m.optString("desc", "");
            sb.append(i + 1).append(". ").append(name);
            if (!desc.isEmpty()) {
                // 截取描述前30个字符避免过长
                String shortDesc = desc.length() > 30 ? desc.substring(0, 30) + "…" : desc;
                sb.append(" — ").append(shortDesc);
            }
            sb.append("\n");
        }

        sb.append("\n").append(zh
                ? "使用方式很简单：直接用自然语言告诉我你想做什么，我会自动规划步骤并调用对应模块完成。"
                : "Usage is simple: just tell me what you want in natural language, I'll plan and invoke the right modules.");

        // HTML 部分
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < mods.length(); i++) {
            JSONObject m = mods.getJSONObject(i);
            String name = m.optString("name", m.optString("id", "?"));
            String desc = m.optString("desc", "");
            rows.add(new String[]{name, desc.length() > 40 ? desc.substring(0, 40) + "…" : desc});
        }
        String html = HtmlOutputHelper.card(
                "📦",
                (zh ? "能力概览（" : "Capabilities (") + total + (zh ? " 个模块）" : " modules)"),
                HtmlOutputHelper.table(
                        new String[]{zh ? "模块" : "Module", zh ? "说明" : "Description"},
                        rows
                )
                + HtmlOutputHelper.muted(zh
                        ? "直接用自然语言描述需求，AI 自动选模块执行"
                        : "Describe needs in plain language, AI auto-selects modules")
        );

        return ok(out, sb.toString().trim(), html);
    }

    private String buildStaticCapabilities(boolean zh, JSONObject out) throws Exception {
        String[][] categories;
        if (zh) {
            categories = new String[][]{
                    {"📁 文件与存储", "filemanager, archive, file_stats, system_cleaner"},
                    {"📷 图片与媒体", "image_tools, ocr, led_banner"},
                    {"🌐 网络与通讯", "weather, network_tools, url_codec, link_parser"},
                    {"💻 系统与设备", "device_info, battery, app_manager, contacts, flashlight, compass"},
                    {"📝 效率工具", "notes, reminder, calculator, unit_converter, clipboard, password_gen, qrcode, text_tools, translator, color_picker, signature_checker"},
                    {"✨ 趣味娱乐", "magic_dice, fortune, farming_game, gomoku_game, snake_game, sudoku_game, tetris_game, tictactoe_game"}
            };
        } else {
            categories = new String[][]{
                    {"📁 Files & Storage", "filemanager, archive, file_stats, system_cleaner"},
                    {"📷 Images & Media", "image_tools, ocr, led_banner"},
                    {"🌐 Network & Comms", "weather, network_tools, url_codec, link_parser"},
                    {"💻 System & Device", "device_info, battery, app_manager, contacts, flashlight, compass"},
                    {"📝 Productivity", "notes, reminder, calculator, unit_converter, clipboard, password_gen, qrcode, text_tools, translator, color_picker, signature_checker"},
                    {"✨ Fun & Games", "magic_dice, fortune, farming_game, gomoku_game, snake_game, sudoku_game, tetris_game, tictactoe_game"}
            };
        }

        StringBuilder sb = new StringBuilder();
        sb.append(zh ? "📦 我的能力概览（30+ 模块）\n\n" : "📦 My Capabilities (30+ modules)\n\n");
        for (String[] cat : categories) {
            sb.append(cat[0]).append("\n  ").append(cat[1]).append("\n\n");
        }
        sb.append(zh
                ? "直接用自然语言描述需求，AI 自动选模块执行"
                : "Describe needs in plain language, AI auto-selects modules");

        return ok(out, sb.toString().trim(), null);
    }

    // ── usage_tips ──

    private String doUsageTips(boolean zh) throws Exception {
        JSONObject out = new JSONObject();

        String[][] tips;
        if (zh) {
            tips = new String[][]{
                    {"💬 说人话就行", "不需要记指令或命令格式，直接用日常语言说需求，比如「帮我整理下载文件夹」"},
                    {"🔀 多步组合", "一句话可以包含多个操作，AI 会自动拆解：「查天气，要下雨就提醒我带伞，顺便写到笔记里」"},
                    {"📦 模块市场", "打开「模块市场」可以浏览和安装更多功能模块，装了就有新能力"},
                    {"🔒 安全沙箱", "每个模块运行在独立沙箱中，互相隔离，无法访问其他模块或你隐私数据"},
                    {"🛠️ 自定义 LLM", "在设置中可以切换 AI 模型，支持 Gemini/Groq（免费）、OpenAI/Claude/DeepSeek 等"},
                    {"✅ 可见可控", "AI 的每一步操作都会显示给你看，你可以随时取消，不会在后台偷偷操作"}
            };
        } else {
            tips = new String[][]{
                    {"💬 Just speak naturally", "No need to memorize commands. Say what you want in everyday language, like 'organize my download folder'"},
                    {"🔀 Multi-step combos", "One sentence can contain multiple operations: 'check weather, remind me if it rains, also save to notes'"},
                    {"📦 Module Market", "Open the Module Market to browse and install more modules — each install adds new capabilities"},
                    {"🔒 Security Sandbox", "Each module runs in an isolated sandbox, cannot access other modules or your private data"},
                    {"🛠️ Custom LLM", "Switch AI models in Settings — supports Gemini/Groq (free), OpenAI/Claude/DeepSeek and more"},
                    {"✅ Transparent & Controllable", "Every AI step is shown to you, cancel anytime — nothing happens in the background secretly"}
            };
        }

        String[][] examples;
        if (zh) {
            examples = new String[][]{
                    {"📁 整理文件", "「整理下载文件夹，按文件类型分类，大于 100MB 的单独列出来」"},
                    {"📷 OCR + 翻译", "「拍照识别这段文字，翻译成英文」"},
                    {"🌦️ 天气提醒", "「查天气，要下雨就提醒我带伞」"},
                    {"🔐 安全工具", "「生成一个 16 位强密码并复制到剪贴板」"},
                    {"💾 数据备份", "「导出联系人，压缩加密备份到下载目录」"}
            };
        } else {
            examples = new String[][]{
                    {"📁 File organize", "'Organize download folder by file type, list files over 100MB separately'"},
                    {"📷 OCR + Translate", "'Scan this text from photo and translate to English'"},
                    {"🌦️ Weather alert", "'Check weather, remind me to bring an umbrella if it rains'"},
                    {"🔐 Security tools", "'Generate a 16-character strong password and copy to clipboard'"},
                    {"💾 Data backup", "'Export contacts, compress and encrypt backup to downloads'"}
            };
        }

        StringBuilder sb = new StringBuilder();
        sb.append(zh ? "💡 使用技巧\n\n" : "💡 Usage Tips\n\n");
        for (String[] tip : tips) {
            sb.append(tip[0]).append("\n  ").append(tip[1]).append("\n\n");
        }
        sb.append(zh ? "📌 常用场景示例：\n\n" : "📌 Common examples:\n\n");
        for (String[] ex : examples) {
            sb.append(ex[0]).append("\n  ").append(ex[1]).append("\n\n");
        }

        StringBuilder htmlBody = new StringBuilder();
        for (String[] tip : tips) {
            htmlBody.append(HtmlOutputHelper.keyValue(new String[][]{
                    {tip[0], tip[1]}
            }));
        }
        List<String[]> exRows = new ArrayList<>();
        for (String[] ex : examples) {
            exRows.add(new String[]{ex[0], ex[1]});
        }
        htmlBody.append(HtmlOutputHelper.table(
                new String[]{zh ? "场景" : "Scenario", zh ? "示例" : "Example"},
                exRows
        ));

        String html = HtmlOutputHelper.card(
                "💡",
                zh ? "使用技巧" : "Usage Tips",
                htmlBody.toString()
        );

        return ok(out, sb.toString().trim(), html);
    }

    // ── helpers ──

    /**
     * 从模块数组中随机抽取 n 个模块名+描述用于亮点展示。
     */
    private List<String> pickRandomModules(JSONArray mods, int n) throws org.json.JSONException {
        List<String> all = new ArrayList<>();
        for (int i = 0; i < mods.length(); i++) {
            JSONObject m = mods.getJSONObject(i);
            String name = m.optString("name", "");
            String desc = m.optString("desc", "");
            if (!name.isEmpty()) {
                all.add(name + (desc.isEmpty() ? "" : (" — " + (desc.length() > 25 ? desc.substring(0, 25) + "…" : desc))));
            }
        }
        // Fisher-Yates 部分洗牌取前 n 个
        int count = Math.min(n, all.size());
        for (int i = 0; i < count; i++) {
            int j = i + RANDOM.nextInt(all.size() - i);
            String tmp = all.get(i);
            all.set(i, all.get(j));
            all.set(j, tmp);
        }
        return all.subList(0, count);
    }

    private String emptyJson(String value) {
        return value == null || value.trim().isEmpty() ? "{}" : value;
    }

    private String ok(JSONObject output, String displayText, String displayHtml) throws Exception {
        JSONObject result = new JSONObject()
                .put("success", true)
                .put("output", output.toString());
        if (displayText != null && !displayText.isEmpty()) {
            result.put("_displayText", displayText);
        }
        if (displayHtml != null && !displayHtml.isEmpty()) {
            result.put("_displayHtml", displayHtml);
        }
        return result.toString();
    }

    private String error(String message) throws Exception {
        return new JSONObject()
                .put("success", false)
                .put("error", message)
                .toString();
    }
}
