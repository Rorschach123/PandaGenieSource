package ai.rorsch.pandagenie.module.runtime;

import android.content.Context;

/**
 * Runtime-provided file deletion review bridge for PandaGenie modules.
 *
 * Modules must never delete user files directly. Submit candidate paths through
 * this API so the host app can export a review table and ask the user to confirm
 * the exact files before deletion.
 */
public final class ModuleFileDelete {
    private ModuleFileDelete() {
    }

    /**
     * Creates a pending deletion review request.
     *
     * @return JSON string: {"success":true,"requestId":"...","tablePath":"...","candidateCount":1}
     */
    public static String prepare(Context context, String requestJson) {
        throw new UnsupportedOperationException("Provided by PandaGenie runtime");
    }

    /**
     * Shows the host confirmation dialog for an existing request and deletes
     * only the user-selected files.
     */
    public static String confirmAndDelete(Context context, String requestId) {
        throw new UnsupportedOperationException("Provided by PandaGenie runtime");
    }

    /**
     * Convenience method for one-shot tools: create the review request, show the
     * confirmation dialog, then delete selected files after user confirmation.
     */
    public static String prepareAndConfirm(Context context, String requestJson) {
        throw new UnsupportedOperationException("Provided by PandaGenie runtime");
    }
}
