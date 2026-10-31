package rea.ajdamir.backendaisafety.documents;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DocumentAccessService {

    private final JdbcTemplate jdbcTemplate;

    public DocumentAccessService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean canAccess(UUID documentId, String username) {
        if (documentId == null || username == null || username.isBlank()) {
            return false;
        }

        Boolean exists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM documents
                WHERE id = ?
                  AND owner_username = ?
            )
            """,
            Boolean.class,
            documentId,
            username
        );

        return Boolean.TRUE.equals(exists);
    }
}