package rea.ajdamir.backendaisafety;

import org.springframework.stereotype.Component;
import rea.ajdamir.backendaisafety.documents.SecretRedactor;

import java.util.regex.Pattern;

/**
 * Deterministic post-generation gate for the PROTECTED RAG mode.
 * This is a defence-in-depth check, not a semantic proof that an answer is true.
 */
@Component
public class ProtectedResponseValidator {

    private static final int MAX_ANSWER_CHARS = 8_000;
    private static final Pattern KNOWN_TEST_PAYLOAD = Pattern.compile(
        "(?iu)ПРОВЕРКА\\s+ПРОЙДЕНА"
    );

    private final PromptInjectionSanitizer promptInjectionSanitizer;
    private final SecretRedactor secretRedactor;

    public ProtectedResponseValidator(
        PromptInjectionSanitizer promptInjectionSanitizer,
        SecretRedactor secretRedactor
    ) {
        this.promptInjectionSanitizer = promptInjectionSanitizer;
        this.secretRedactor = secretRedactor;
    }

    public Validation validate(String answer) {
        if (answer == null || answer.isBlank()) {
            return Validation.reject("EMPTY_OUTPUT");
        }
        if (answer.length() > MAX_ANSWER_CHARS) {
            return Validation.reject("OUTPUT_TOO_LARGE");
        }
        // The marker is the explicit attack payload used by this project's benchmark.
        if (KNOWN_TEST_PAYLOAD.matcher(answer).find()) {
            return Validation.reject("KNOWN_ATTACK_PAYLOAD");
        }
        if (promptInjectionSanitizer.sanitizeWithReport(answer).removedSegments() > 0) {
            return Validation.reject("INSTRUCTION_LIKE_OUTPUT");
        }
        if (!secretRedactor.redact(answer).equals(answer)) {
            return Validation.reject("POSSIBLE_SECRET_IN_OUTPUT");
        }
        return Validation.accept();
    }

    public record Validation(boolean accepted, String reason) {
        static Validation accept() {
            return new Validation(true, "ACCEPTED");
        }

        static Validation reject(String reason) {
            return new Validation(false, reason);
        }
    }
}
