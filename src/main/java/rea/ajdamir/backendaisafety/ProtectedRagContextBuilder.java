package rea.ajdamir.backendaisafety;

import org.springframework.stereotype.Component;

import rea.ajdamir.backendaisafety.documents.RetrievedDocument;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds bounded, structured context for the PROTECTED RAG mode.
 * Suspicious segments are removed by the heuristic sanitizer. If a document
 * also contains usable facts, those facts can still be passed to the model.
 * If all retrieved content is removed as instruction-like, the request fails closed.
 */
@Component
public class ProtectedRagContextBuilder {

    private static final int MAX_EXCERPT_CHARS = 4_000;
    private static final int MAX_DOCUMENT_CONTEXT_CHARS = 10_000;
    private static final int MAX_USER_CONTENT_CHARS = 12_000;

    private final ObjectMapper objectMapper;
    private final PromptInjectionSanitizer sanitizer;

    public ProtectedRagContextBuilder(
        ObjectMapper objectMapper,
        PromptInjectionSanitizer sanitizer
    ) {
        this.objectMapper = objectMapper;
        this.sanitizer = sanitizer;
    }

    public Preparation prepare(String prompt, List<RetrievedDocument> documents)
        throws JacksonException {

        List<SafeDocument> retained = new ArrayList<>();
        int totalOriginalChars = 0;
        int filteredDocuments = 0;

        for (RetrievedDocument document : documents) {
            String originalExcerpt = document.excerpt() == null ? "" : document.excerpt();

            if (originalExcerpt.length() > MAX_EXCERPT_CHARS) {
                return Preparation.refused("EXCERPT_TOO_LARGE", filteredDocuments);
            }

            totalOriginalChars += originalExcerpt.length();
            if (totalOriginalChars > MAX_DOCUMENT_CONTEXT_CHARS) {
                return Preparation.refused("DOCUMENT_CONTEXT_TOO_LARGE", filteredDocuments);
            }

            PromptInjectionSanitizer.SanitizationResult result =
                sanitizer.sanitizeWithReport(originalExcerpt);

            if (result.removedSegments() > 0) {
                filteredDocuments++;
            }

            String safeExcerpt = result.sanitizedText();
            if (safeExcerpt.isBlank() && result.removedSegments() > 0) {
                // Do not send an all-instruction document to the model.
                // Other documents with retained facts may still answer the question.
                continue;
            }

            retained.add(new SafeDocument(document, safeExcerpt));
        }

        if (!documents.isEmpty() && retained.isEmpty() && filteredDocuments > 0) {
            return Preparation.refused("INSTRUCTION_LIKE_CONTENT", filteredDocuments);
        }

        ObjectNode context = objectMapper.createObjectNode();
        context.put("question", prompt);
        context.put("retrievalStatus", retained.isEmpty() ? "no_matches" : "matches_found");

        ArrayNode documentArray = context.putArray("documents");
        for (SafeDocument safeDocument : retained) {
            RetrievedDocument original = safeDocument.document();
            ObjectNode entry = documentArray.addObject();
            entry.put("source", original.filename() == null ? "" : original.filename());
            entry.put("id", original.id() == null ? "" : original.id().toString());
            entry.put("excerpt", safeDocument.excerpt());
        }

        String userContent = objectMapper.writeValueAsString(context);
        if (userContent.length() > MAX_USER_CONTENT_CHARS) {
            return Preparation.refused("PROTECTED_USER_CONTENT_TOO_LARGE", filteredDocuments);
        }

        return Preparation.accepted(userContent, filteredDocuments);
    }

    private record SafeDocument(RetrievedDocument document, String excerpt) {}

    public record Preparation(
        String userContent,
        String refusalReason,
        int filteredDocuments
    ) {
        public boolean refused() {
            return refusalReason != null;
        }

        static Preparation accepted(String userContent, int filteredDocuments) {
            return new Preparation(userContent, null, filteredDocuments);
        }

        static Preparation refused(String reason, int filteredDocuments) {
            return new Preparation(null, reason, filteredDocuments);
        }
    }
}
