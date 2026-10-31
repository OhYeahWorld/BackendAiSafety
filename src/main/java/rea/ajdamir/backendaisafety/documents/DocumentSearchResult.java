package rea.ajdamir.backendaisafety.documents;

import java.util.UUID;

public record DocumentSearchResult(
    UUID id,
    String filename,
    double score,
    String excerpt
) {}
