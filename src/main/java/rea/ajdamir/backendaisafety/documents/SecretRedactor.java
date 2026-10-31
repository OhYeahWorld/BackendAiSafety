package rea.ajdamir.backendaisafety.documents;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort masking for common credential assignments in returned snippets.
 * This is a safety layer, not a complete DLP/secret scanner.
 */
@Component
public class SecretRedactor {

    private static final Pattern LABELED_SECRET = Pattern.compile(
        "(?i)((?:password|passwd|pwd|api[_-]?key|secret|access[_-]?token|auth[_-]?token)[^\\r\\n:=]{0,24}(?:\\]\\])?\\s*[:=]\\s*)([^\\s,;]+)"
    );

    private static final Pattern BEARER_TOKEN = Pattern.compile(
        "(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/=-]{8,}"
    );

    public String redact(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }

        Matcher labeled = LABELED_SECRET.matcher(text);
        String withoutLabeledSecrets = labeled.replaceAll("$1[REDACTED]");
        Matcher bearer = BEARER_TOKEN.matcher(withoutLabeledSecrets);
        return bearer.replaceAll("$1[REDACTED]");
    }
}
