
package rea.ajdamir.backendaisafety;

import java.util.Locale;

public enum RagMode {
    UNSAFE,
    BASELINE,
    PROTECTED;

    public static RagMode fromProperty(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                "app.rag.mode must be unsafe, baseline or protected"
            );
        }

        try {
            return RagMode.valueOf(
                value.trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "app.rag.mode must be unsafe, baseline or protected",
                e
            );
        }
    }
}
