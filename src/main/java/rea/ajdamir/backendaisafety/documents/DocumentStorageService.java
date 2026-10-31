package rea.ajdamir.backendaisafety.documents;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class DocumentStorageService {

    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private final JdbcClient jdbcClient;
    private final Path storageDirectory;

    public DocumentStorageService(
        JdbcClient jdbcClient,
        @Value("${app.documents.storage-dir:./data/documents}")
        String storageDirectory
    ) {
        this.jdbcClient = jdbcClient;
        this.storageDirectory =
            Path.of(storageDirectory).toAbsolutePath().normalize();
    }

    public DocumentUploadResponse store(
        MultipartFile file,
        String ownerUsername
    ) {
        if (ownerUsername == null || ownerUsername.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Authenticated user is required"
            );
        }

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "File must not be empty"
            );
        }

        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "File exceeds the 20 MB limit"
            );
        }

        String originalFilename =
            safeBaseFilename(file.getOriginalFilename());

        String extension = extensionOf(originalFilename);

        if (!extension.equals(".pdf") && !extension.equals(".txt")) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Only PDF and TXT files are supported at this stage"
            );
        }

        final byte[] bytes;

        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Could not read uploaded file"
            );
        }

        if (extension.equals(".pdf") && !startsWithPdfHeader(bytes)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "The uploaded file does not appear to be a valid PDF"
            );
        }

        if (extension.equals(".txt") && containsNulByte(bytes)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "The TXT file appears to contain binary data"
            );
        }

        UUID id = UUID.randomUUID();
        String storageKey = id + extension;
        String contentType = extension.equals(".pdf")
            ? "application/pdf"
            : "text/plain";

        String sha256 = sha256(bytes);
        Path target = storageDirectory.resolve(storageKey).normalize();

        if (!target.startsWith(storageDirectory)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid file path"
            );
        }

        boolean fileWritten = false;

        try {
            Files.createDirectories(storageDirectory);
            Files.write(target, bytes);
            fileWritten = true;

            jdbcClient.sql("""
                INSERT INTO documents (
                    id,
                    owner_username,
                    original_filename,
                    content_type,
                    size_bytes,
                    sha256,
                    storage_key,
                    status,
                    created_at,
                    updated_at
                ) VALUES (
                    :id,
                    :ownerUsername,
                    :filename,
                    :contentType,
                    :sizeBytes,
                    :sha256,
                    :storageKey,
                    'UPLOADED',
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP
                )
                """)
                .param("id", id)
                .param("ownerUsername", ownerUsername)
                .param("filename", originalFilename)
                .param("contentType", contentType)
                .param("sizeBytes", bytes.length)
                .param("sha256", sha256)
                .param("storageKey", storageKey)
                .update();

            return new DocumentUploadResponse(
                id,
                originalFilename,
                contentType,
                bytes.length,
                "UPLOADED"
            );

        } catch (IOException e) {
            deleteQuietly(target);

            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Could not store uploaded file"
            );

        } catch (RuntimeException e) {
            if (fileWritten) {
                deleteQuietly(target);
            }
            throw e;
        }
    }

    private static String safeBaseFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Filename is required"
            );
        }

        String normalized = originalFilename.replace('\\', '/');

        String baseName = normalized
            .substring(normalized.lastIndexOf('/') + 1)
            .trim();

        if (baseName.isBlank()
            || baseName.equals(".")
            || baseName.equals("..")) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid filename"
            );
        }

        return baseName;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');

        if (dot < 0) {
            return "";
        }

        return filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static boolean startsWithPdfHeader(byte[] bytes) {
        byte[] header =
            "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        if (bytes.length < header.length) {
            return false;
        }

        for (int i = 0; i < header.length; i++) {
            if (bytes[i] != header[i]) {
                return false;
            }
        }

        return true;
    }

    private static boolean containsNulByte(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }

        return false;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                "SHA-256 is not available",
                e
            );
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Preserve the original exception.
        }
    }
}