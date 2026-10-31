package rea.ajdamir.backendaisafety.documents;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DocumentRetrievalService {

    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_RESULTS = 3;
    private static final int MAX_SEARCH_TERMS = 8;
    private static final int FULL_TEXT_CONTEXT_LIMIT = 4_000;

    private static final Pattern TOKEN_PATTERN =
        Pattern.compile("[\\p{L}\\p{N}]{3,}");

    private static final Set<String> QUESTION_STOP_WORDS = Set.of(
        "согласно", "найденным", "найденных", "найденные",
        "документам", "документах", "документ", "документы", "документа",
        "как", "часто", "и", "или", "во", "в", "на", "по", "из", "для",
        "про", "что", "сколько", "когда", "где", "какой", "какая", "какие",
        "какое", "укажи", "укажите", "назови", "имя", "файл", "файла",
        "файлом", "файле", "источник", "источника", "источником",
        "пожалуйста", "скажи", "расскажи", "подскажи", "найди", "найти",
        "мне", "меня", "этот", "эта", "это", "есть", "ли", "бы", "об",
        "and", "or"
    );

    private final JdbcTemplate jdbcTemplate;
    private final SecretRedactor secretRedactor;

    public DocumentRetrievalService(
        JdbcTemplate jdbcTemplate,
        SecretRedactor secretRedactor
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.secretRedactor = secretRedactor;
    }

    public List<RetrievedDocument> search(
        String query,
        int limit,
        String ownerUsername
    ) {
        if (query == null || query.isBlank()
            || ownerUsername == null || ownerUsername.isBlank()) {
            return List.of();
        }

        String safeQuery = query.strip();

        if (safeQuery.length() > MAX_QUERY_LENGTH) {
            safeQuery = safeQuery.substring(0, MAX_QUERY_LENGTH);
        }

        String keywordQuery = buildKeywordOrQuery(safeQuery);

        if (keywordQuery.isBlank()) {
            return List.of();
        }

        int safeLimit = Math.max(1, Math.min(limit, MAX_RESULTS));

        String sql = """
            SELECT id,
                   original_filename,
                   ts_rank_cd(
                       to_tsvector('russian', COALESCE(extracted_text, '')),
                       websearch_to_tsquery('russian', ?)
                   ) AS score,
                   CASE
                       WHEN length(extracted_text) <= ? THEN extracted_text
                       ELSE ts_headline(
                           'russian',
                           extracted_text,
                           websearch_to_tsquery('russian', ?),
                           'MaxFragments=3, MinWords=20, MaxWords=100, StartSel=[[, StopSel=]]'
                       )
                   END AS excerpt
            FROM documents
            WHERE status = 'READY'
              AND owner_username = ?
              AND extracted_text IS NOT NULL
              AND btrim(extracted_text) <> ''
              AND to_tsvector('russian', COALESCE(extracted_text, ''))
                  @@ websearch_to_tsquery('russian', ?)
            ORDER BY score DESC, created_at DESC
            LIMIT ?
            """;

        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> new RetrievedDocument(
                rs.getObject("id", UUID.class),
                rs.getString("original_filename"),
                rs.getDouble("score"),
                secretRedactor.redact(rs.getString("excerpt"))
            ),
            keywordQuery,
            FULL_TEXT_CONTEXT_LIMIT,
            keywordQuery,
            ownerUsername,
            keywordQuery,
            safeLimit
        );
    }

    private static String buildKeywordOrQuery(String query) {
        Matcher matcher =
            TOKEN_PATTERN.matcher(query.toLowerCase(Locale.ROOT));

        LinkedHashSet<String> terms = new LinkedHashSet<>();

        while (matcher.find() && terms.size() < MAX_SEARCH_TERMS) {
            String term = matcher.group();

            if (!QUESTION_STOP_WORDS.contains(term)) {
                terms.add(term);
            }
        }

        return String.join(" OR ", terms);
    }
}