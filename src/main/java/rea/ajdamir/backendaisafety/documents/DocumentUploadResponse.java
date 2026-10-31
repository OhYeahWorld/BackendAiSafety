package rea.ajdamir.backendaisafety.documents;

import java.util.UUID;

public record DocumentUploadResponse(
        UUID id,
        String filename,
        String contentType,
        long sizeBytes,
        String status) {
}
