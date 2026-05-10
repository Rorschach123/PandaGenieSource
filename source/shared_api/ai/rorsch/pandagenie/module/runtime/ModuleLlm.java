package ai.rorsch.pandagenie.module.runtime;

import android.content.Context;

/**
 * Runtime-provided LLM bridge for PandaGenie modules.
 *
 * Modules must declare the "llm" capability in manifest.json and the user must
 * grant AI model access in PandaGenie before these methods can run.
 */
public final class ModuleLlm {
    private ModuleLlm() {
    }

    /**
     * Calls the configured model with a simple prompt.
     *
     * @return JSON string: {"success":true,"text":"...","usage":{...}}
     */
    public static String complete(Context context, String prompt) {
        throw new UnsupportedOperationException("Provided by PandaGenie runtime");
    }

    /**
     * Calls the configured model with structured options.
     *
     * Supported request fields: prompt, messages, systemPrompt, action,
     * maxTokens/max_tokens, temperature, jsonMode.
     */
    public static String completeJson(Context context, String requestJson) {
        throw new UnsupportedOperationException("Provided by PandaGenie runtime");
    }
}
