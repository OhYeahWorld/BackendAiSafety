package rea.ajdamir.backendaisafety.documents;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecretRedactorTest {

    private SecretRedactor secretRedactor;

    @BeforeEach
    void setUp() {
        secretRedactor = new SecretRedactor();
    }

    @Test
    void nullInputReturnsEmptyString() {
        assertEquals("", secretRedactor.redact(null));
    }

    @Test
    void blankInputRemainsUnchanged() {
        assertEquals("", secretRedactor.redact(""));
        assertEquals("   ", secretRedactor.redact("   "));
        assertEquals("\n\t", secretRedactor.redact("\n\t"));
    }

    @Test
    void redactsPasswordAssignment() {
        assertEquals(
            "password: [REDACTED]",
            secretRedactor.redact("password: hunter2")
        );
    }

    @Test
    void redactsDifferentCredentialLabels() {
        String input =
            "API_KEY=abc123xyz; access-token: token987; auth_token=secret456";

        String expected =
            "API_KEY=[REDACTED]; access-token: [REDACTED]; "
                + "auth_token=[REDACTED]";

        assertEquals(expected, secretRedactor.redact(input));
    }

    @Test
    void redactsBearerTokensCaseInsensitively() {
        String input =
            "Authorization: Bearer abcdef123456 and bearer XyZ_12345";

        String expected =
            "Authorization: Bearer [REDACTED] and bearer [REDACTED]";

        assertEquals(expected, secretRedactor.redact(input));
    }

    @Test
    void redactsMultipleSecretsInOneText() {
        String input =
            "password=alpha123, secret: beta456; pwd=gamma789";

        String expected =
            "password=[REDACTED], secret: [REDACTED]; pwd=[REDACTED]";

        assertEquals(expected, secretRedactor.redact(input));
    }

    @Test
    void leavesOrdinaryTextUnchanged() {
        String input =
            "The document describes password policies and API key rotation.";

        assertEquals(input, secretRedactor.redact(input));
    }

    @Test
    void preservesTextAroundRedactedValue() {
        String input =
            "Configuration: password=mySecret123, environment=development";

        String expected =
            "Configuration: password=[REDACTED], environment=development";

        assertEquals(expected, secretRedactor.redact(input));
    }
}