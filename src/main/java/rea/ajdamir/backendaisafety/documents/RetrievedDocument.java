package rea.ajdamir.backendaisafety.documents;

import java.util.UUID;

public record RetrievedDocument(
    UUID id,
    String filename,
    double score,
    String excerpt
) {}
