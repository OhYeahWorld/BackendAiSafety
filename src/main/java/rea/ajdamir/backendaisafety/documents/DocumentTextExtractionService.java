package rea.ajdamir.backendaisafety.documents;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

@Service
public class DocumentTextExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextExtractionService.class);
    private static final String PDF_EXTENSION = ".pdf";
    private static final String TXT_EXTENSION = ".txt";

    private final JdbcClient jdbcClient;
    private final Path storageDirectory;
    private final int maxExtractedCharacters;

    public DocumentTextExtractionService(
            JdbcClient jdbcClient,
            @Value("${app.documents.storage-dir:./data/documents}") String storageDirectory,
            @Value("${app.documents.max-extracted-characters:2000000}") int maxExtractedCharacters) {
        this.jdbcClient = jdbcClient;
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
        if (maxExtractedCharacters < 1) {
            throw new IllegalArgumentException("app.documents.max-extracted-characters must be positive");
        }
        this.maxExtractedCharacters = maxExtractedCharacters;
    }

    public DocumentTextResponse extract(UUID id) {
        DocumentRecord document = findDocument(id);

        Path file = storageDirectory.resolve(document.storageKey()).normalize();
        if (!file.startsWith(storageDirectory)) {
            markFailed(id, "The stored file path is invalid.");
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "The stored file path is invalid");
        }
        if (!Files.isRegularFile(file)) {
            markFailed(id, "The original file is missing from storage.");
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "The original file is missing from storage");
        }

        String extension = extensionOf(document.storageKey());
        if (!extension.equals(PDF_EXTENSION) && !extension.equals(TXT_EXTENSION)) {
            markFailed(id, "This document format is not supported for text extraction.");
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Only PDF and TXT documents can be processed");
        }

        jdbcClient.sql("""
                UPDATE documents
                SET status = 'PROCESSING', error_message = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """)
                .param("id", id)
                .update();

        final String extractedText;
        try {
            extractedText = normalizeText(extension.equals(PDF_EXTENSION)
                    ? extractPdf(file)
                    : extractTxt(file));
        } catch (CharacterCodingException e) {
            log.warn("TXT text decoding failed for document {}", id);
            markFailed(id, "The TXT file is not valid UTF-8 text.");
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The TXT file is not valid UTF-8 text");
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Text extraction failed for document {}", id, e);
            String publicMessage = extension.equals(PDF_EXTENSION)
                    ? "The PDF could not be read. It may be invalid or damaged."
                    : "The TXT file could not be read.";
            markFailed(id, publicMessage);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, publicMessage);
        }

        if (extractedText.isBlank()) {
            String message = "No text was found. A scanned PDF may require OCR, which is not included yet.";
            markFailed(id, message);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
        }

        if (extractedText.length() > maxExtractedCharacters) {
            String message = "Extracted text exceeds the configured character limit. Increase the limit or split the document.";
            markFailed(id, message);
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, message);
        }

        jdbcClient.sql("""
                UPDATE documents
                SET extracted_text = :text,
                    status = 'READY',
                    error_message = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """)
                .param("text", extractedText)
                .param("id", id)
                .update();

        log.info("Text extraction completed for document {}; characters={}", id, extractedText.length());
        return new DocumentTextResponse(id, document.originalFilename(), "READY", extractedText.length());
    }

    private DocumentRecord findDocument(UUID id) {
        return jdbcClient.sql("""
                SELECT original_filename, storage_key
                FROM documents
                WHERE id = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> new DocumentRecord(
                        rs.getString("original_filename"),
                        rs.getString("storage_key")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Document not found"));
    }

    private static String extractPdf(Path file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String extractTxt(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        String text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    private static String normalizeText(String text) {
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\u0000", "")
                .strip();
    }

    private void markFailed(UUID id, String safeMessage) {
        jdbcClient.sql("""
                UPDATE documents
                SET status = 'FAILED', error_message = :message, updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """)
                .param("message", safeMessage)
                .param("id", id)
                .update();
    }

    private static String extensionOf(String key) {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? "" : key.substring(dot).toLowerCase(Locale.ROOT);
    }

    private record DocumentRecord(String originalFilename, String storageKey) {
    }
}
