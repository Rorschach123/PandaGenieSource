package ai.rorsch.pandagenie.module.runtime;

import android.content.Context;

public interface ModulePlugin {
    String invoke(Context context, String action, String paramsJson) throws Exception;
}
