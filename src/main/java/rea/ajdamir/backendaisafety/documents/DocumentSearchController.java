package rea.ajdamir.backendaisafety.documents;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/documents")
public class DocumentSearchController {

    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_RESULTS = 10;

    private final JdbcTemplate jdbcTemplate;
    private final SecretRedactor secretRedactor;

    public DocumentSearchController(
        JdbcTemplate jdbcTemplate,
        SecretRedactor secretRedactor
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.secretRedactor = secretRedactor;
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
        @RequestParam(name = "q", required = false) String query,
        @RequestParam(name = "limit", defaultValue = "5") int limit,
        Authentication authentication
    ) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of(
                    "error",
                    "Query parameter 'q' must not be empty"
                ));
        }

        if (query.length() > MAX_QUERY_LENGTH) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Search query is too long"));
        }

        int safeLimit = Math.max(1, Math.min(limit, MAX_RESULTS));
        String username = authentication.getName();

        String sql = """
            SELECT id,
                   original_filename,
                   ts_rank_cd(
                       to_tsvector('russian', COALESCE(extracted_text, '')),
                       plainto_tsquery('russian', ?)
                   ) AS score,
                   ts_headline(
                       'russian',
                       extracted_text,
                       plainto_tsquery('russian', ?),
                       'MaxFragments=2, MinWords=8, MaxWords=35, StartSel=[[, StopSel=]]'
                   ) AS excerpt
            FROM documents
            WHERE owner_username = ?
              AND status = 'READY'
              AND extracted_text IS NOT NULL
              AND btrim(extracted_text) <> ''
              AND to_tsvector('russian', COALESCE(extracted_text, ''))
                  @@ plainto_tsquery('russian', ?)
            ORDER BY score DESC, created_at DESC
            LIMIT ?
            """;

        List<DocumentSearchResult> results = jdbcTemplate.query(
            sql,
            (rs, rowNum) -> new DocumentSearchResult(
                rs.getObject("id", UUID.class),
                rs.getString("original_filename"),
                rs.getDouble("score"),
                secretRedactor.redact(rs.getString("excerpt"))
            ),
            query,
            query,
            username,
            query,
            safeLimit
        );

        return ResponseEntity.ok(results);
    }
}