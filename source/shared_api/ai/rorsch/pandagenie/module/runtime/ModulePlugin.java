package ai.rorsch.pandagenie.module.runtime;

import android.content.Context;

/**
 * PandaGenie 模块插件的统一入口接口。
 * <p>
 * <b>模块用途：</b>定义宿主（主应用）与动态加载的模块插件之间的调用约定，使各功能模块以相同方式被调度。
 * </p>
 * <p>
 * <b>提供的 API 契约：</b>实现类须提供 {@link #invoke(Context, String, String)}，通过 {@code action} 区分操作，
 * 通过 {@code paramsJson} 传递 JSON 参数；返回值通常为 JSON 字符串（含 {@code success}、{@code output}、
 * {@code error} 等字段，部分模块还会附带 {@code _displayText} 供界面展示）。
 * </p>
 * <p>
 * <b>加载方式：</b>由 {@code ModuleRuntime} 通过反射实例化具体实现类并调用本接口方法，插件类名与包路径由模块清单配置。
 * </p>
 */
public interface ModulePlugin {
    /**
     * 执行模块的一次操作调用。
     *
     * @param context   Android 上下文，用于访问系统服务、文件路径等（不可为 null，由运行时传入）
     * @param action    操作名称，由各插件自行约定（如 {@code listApps}、{@code evaluate} 等）
     * @param paramsJson 该次调用的 JSON 参数字符串；可为空或 null，实现中通常按空对象 {@code {}} 解析
     * @return 调用结果的 JSON 字符串，结构由具体插件定义，失败时一般包含 {@code success:false} 与 {@code error}
     * @throws Exception 参数非法、权限不足或底层异常时抛出，由上层统一捕获或转换为错误 JSON
     */
    String invoke(Context context, String action, String paramsJson) throws Exception;
}
