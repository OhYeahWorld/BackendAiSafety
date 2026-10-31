package rea.ajdamir.backendaisafety.documents;

import java.util.UUID;

public record DocumentTextResponse(
        UUID id,
        String filename,
        String status,
        int extractedCharacters) {
}
