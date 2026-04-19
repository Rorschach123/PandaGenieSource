package ai.rorsch.pandagenie.module.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Helper class for module plugins to generate consistent HTML5 mini-card output.
 * <p>
 * Output is returned via the {@code _displayHtml} field in the plugin JSON response.
 * The PandaGenie app injects a shared CSS theme so modules only need to use the
 * {@code pg-*} CSS classes documented below.
 * </p>
 * <h3>Available CSS classes (injected by host app):</h3>
 * <ul>
 *   <li>{@code .pg-card} — rounded card container with border</li>
 *   <li>{@code .pg-card-title} — card title row (use {@code .icon} span for emoji)</li>
 *   <li>{@code .pg-kv} — key-value grid (children: {@code .k} and {@code .v})</li>
 *   <li>{@code .pg-table} — styled table</li>
 *   <li>{@code .pg-badge}, {@code .pg-badge-green/orange/red/blue} — status badges</li>
 *   <li>{@code .pg-grid} / {@code .pg-grid-item} — metric grid with {@code .val} and {@code .label}</li>
 *   <li>{@code .pg-gauge} / {@code .pg-gauge-fill} — horizontal gauge bar</li>
 *   <li>{@code .pg-list} — icon list</li>
 *   <li>{@code .pg-muted} — secondary text</li>
 * </ul>
 */
public final class HtmlOutputHelper {

    private HtmlOutputHelper() {}

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Wrap content in a card with optional icon + title. */
    public static String card(String icon, String title, String bodyHtml) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='pg-card'>");
        if (title != null && !title.isEmpty()) {
            sb.append("<div class='pg-card-title'>");
            if (icon != null && !icon.isEmpty()) sb.append("<span class='icon'>").append(icon).append("</span>");
            sb.append(esc(title)).append("</div>");
        }
        sb.append(bodyHtml);
        sb.append("</div>");
        return sb.toString();
    }

    /** Key-value pairs rendered as a two-column grid. */
    public static String keyValue(String[][] pairs) {
        StringBuilder sb = new StringBuilder("<div class='pg-kv'>");
        for (String[] kv : pairs) {
            if (kv.length >= 2) {
                sb.append("<span class='k'>").append(esc(kv[0])).append("</span>");
                sb.append("<span class='v'>").append(esc(kv[1])).append("</span>");
            }
        }
        sb.append("</div>");
        return sb.toString();
    }

    /** Key-value pairs from a Map. */
    public static String keyValue(Map<String, String> pairs) {
        StringBuilder sb = new StringBuilder("<div class='pg-kv'>");
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            sb.append("<span class='k'>").append(esc(e.getKey())).append("</span>");
            sb.append("<span class='v'>").append(esc(e.getValue())).append("</span>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    /** HTML table with headers and rows. */
    public static String table(String[] headers, List<String[]> rows) {
        StringBuilder sb = new StringBuilder("<table class='pg-table'><thead><tr>");
        for (String h : headers) {
            sb.append("<th>").append(esc(h)).append("</th>");
        }
        sb.append("</tr></thead><tbody>");
        for (String[] row : rows) {
            sb.append("<tr>");
            for (String cell : row) {
                sb.append("<td>").append(esc(cell)).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    /** Inline colored badge. colorClass: "green", "orange", "red", "blue". */
    public static String badge(String text, String colorClass) {
        return "<span class='pg-badge pg-badge-" + esc(colorClass) + "'>" + esc(text) + "</span>";
    }

    /** Metric grid items — each item has a large value and a small label. */
    public static String metricGrid(String[][] items) {
        StringBuilder sb = new StringBuilder("<div class='pg-grid'>");
        for (String[] item : items) {
            if (item.length >= 2) {
                sb.append("<div class='pg-grid-item'><div class='val'>").append(esc(item[0])).append("</div>");
                sb.append("<div class='label'>").append(esc(item[1])).append("</div></div>");
            }
        }
        sb.append("</div>");
        return sb.toString();
    }

    /** Horizontal gauge bar (0-100). color: CSS color string (e.g. "#4CAF50"). */
    public static String gauge(int percent, String color) {
        int p = Math.max(0, Math.min(100, percent));
        return "<div class='pg-gauge'><div class='pg-gauge-fill' style='width:" + p +
                "%;background:" + esc(color) + "'></div></div>";
    }

    /** Icon list items — each entry is [icon, text]. */
    public static String iconList(String[][] items) {
        StringBuilder sb = new StringBuilder("<ul class='pg-list'>");
        for (String[] item : items) {
            sb.append("<li>");
            if (item.length >= 1) sb.append("<span class='icon'>").append(item[0]).append("</span>");
            if (item.length >= 2) sb.append(esc(item[1]));
            sb.append("</li>");
        }
        sb.append("</ul>");
        return sb.toString();
    }

    /** Shorthand: success badge. */
    public static String successBadge() {
        boolean zh = Locale.getDefault().getLanguage().startsWith("zh");
        return badge(zh ? "成功" : "Success", "green");
    }

    /** Shorthand: error badge. */
    public static String errorBadge() {
        boolean zh = Locale.getDefault().getLanguage().startsWith("zh");
        return badge(zh ? "失败" : "Failed", "red");
    }

    /** Muted secondary text. */
    public static String muted(String text) {
        return "<p class='pg-muted'>" + esc(text) + "</p>";
    }

    /** Simple paragraph. */
    public static String p(String text) {
        return "<p>" + esc(text) + "</p>";
    }

    /** Raw HTML passthrough (for advanced use). */
    public static String raw(String html) {
        return html;
    }
}
