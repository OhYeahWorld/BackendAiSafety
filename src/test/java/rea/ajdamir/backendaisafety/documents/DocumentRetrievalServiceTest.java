package rea.ajdamir.backendaisafety.documents;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
class DocumentRetrievalServiceTest {

    private static final String OWNER = "alice";

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private SecretRedactor secretRedactor;

    private DocumentRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new DocumentRetrievalService(
            jdbcTemplate,
            secretRedactor
        );
    }

    @Test
    void nullOrBlankQueryReturnsEmptyListWithoutDatabaseCall() {
        assertTrue(service.search(null, 3, OWNER).isEmpty());
        assertTrue(service.search("", 3, OWNER).isEmpty());
        assertTrue(service.search("   \n\t  ", 3, OWNER).isEmpty());

        verifyNoInteractions(jdbcTemplate, secretRedactor);
    }

    @Test
    void blankOwnerReturnsEmptyListWithoutDatabaseCall() {
        assertTrue(service.search("архив", 3, null).isEmpty());
        assertTrue(service.search("архив", 3, " ").isEmpty());

        verifyNoInteractions(jdbcTemplate, secretRedactor);
    }

    @Test
    void queryContainingOnlyStopWordsReturnsEmptyList() {
        String query =
            "согласно найденным документам сколько когда где";

        assertTrue(service.search(query, 3, OWNER).isEmpty());

        verifyNoInteractions(jdbcTemplate, secretRedactor);
    }

    @Test
    void buildsKeywordQueryAndLimitsMaximumResults() {
        service.search(
            "Согласно найденным документам, сколько хранится архив?",
            100,
            OWNER
        );

        QueryCall call = captureQuery();

        assertArrayEquals(
            new Object[] {
                "хранится OR архив",
                4_000,
                "хранится OR архив",
                OWNER,
                "хранится OR архив",
                3
            },
            call.args()
        );

        assertTrue(call.sql().contains("owner_username = ?"));
        assertTrue(call.sql().contains("status = 'READY'"));
        assertTrue(call.sql().contains("extracted_text IS NOT NULL"));
        assertTrue(call.sql().contains("ts_headline"));
        assertTrue(call.sql().contains("LIMIT ?"));
    }

    @Test
    void removesDuplicateTermsAndKeepsAtMostEightTerms() {
        service.search(
            "Alpha alpha BETA gamma delta epsilon zeta eta theta iota",
            2,
            OWNER
        );

        QueryCall call = captureQuery();

        assertEquals(
            "alpha OR beta OR gamma OR delta OR epsilon OR zeta OR eta OR theta",
            call.args()[0]
        );

        assertEquals(2, call.args()[5]);
    }

    @Test
    void truncatesLongQueryToMaximumLength() {
        String longQuery = "alpha ".repeat(33) + "omega";

        service.search(longQuery, 2, OWNER);

        QueryCall call = captureQuery();

        assertEquals("alpha", call.args()[0]);
    }

    @Test
    void clampsMinimumResultLimitToOne() {
        service.search("архив", 0, OWNER);

        QueryCall call = captureQuery();

        assertEquals(1, call.args()[5]);
    }

    @Test
    void redactsExcerptWhenMappingDatabaseRow() throws Exception {
        service.search("архив", 1, OWNER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<RowMapper<RetrievedDocument>> mapperCaptor =
            (ArgumentCaptor<RowMapper<RetrievedDocument>>)
                (ArgumentCaptor<?>) ArgumentCaptor.forClass(RowMapper.class);

        verify(jdbcTemplate).query(
            anyString(),
            mapperCaptor.capture(),
            any(Object[].class)
        );

        UUID documentId = UUID.randomUUID();
        ResultSet resultSet =
            org.mockito.Mockito.mock(ResultSet.class);

        when(resultSet.getObject("id", UUID.class))
            .thenReturn(documentId);

        when(resultSet.getString("original_filename"))
            .thenReturn("test-document.pdf");

        when(resultSet.getDouble("score"))
            .thenReturn(0.75);

        when(resultSet.getString("excerpt"))
            .thenReturn("raw secret value");

        when(secretRedactor.redact("raw secret value"))
            .thenReturn("[REDACTED]");

        RetrievedDocument document =
            mapperCaptor.getValue().mapRow(resultSet, 0);

        assertNotNull(document);
        verify(secretRedactor).redact("raw secret value");
    }

    private QueryCall captureQuery() {
        ArgumentCaptor<String> sqlCaptor =
            ArgumentCaptor.forClass(String.class);

        ArgumentCaptor<Object[]> argsCaptor =
            ArgumentCaptor.forClass(Object[].class);

        verify(jdbcTemplate).query(
            sqlCaptor.capture(),
            ArgumentMatchers.<RowMapper<RetrievedDocument>>any(),
            argsCaptor.capture()
        );

        return new QueryCall(
            sqlCaptor.getValue(),
            argsCaptor.getValue()
        );
    }

    private record QueryCall(String sql, Object[] args) {
    }
}