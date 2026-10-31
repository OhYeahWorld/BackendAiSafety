package rea.ajdamir.backendaisafety.documents;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/documents")
public class DocumentTextController {

    private final DocumentTextExtractionService extractionService;
    private final DocumentAccessService documentAccessService;

    public DocumentTextController(
        DocumentTextExtractionService extractionService,
        DocumentAccessService documentAccessService
    ) {
        this.extractionService = extractionService;
        this.documentAccessService = documentAccessService;
    }

    @PostMapping(
        value = "/{id}/extract",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<DocumentTextResponse> extract(
        @PathVariable UUID id,
        Authentication authentication
    ) {
        if (!documentAccessService.canAccess(id, authentication.getName())) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Document not found"
            );
        }

        return ResponseEntity.ok(extractionService.extract(id));
    }
}