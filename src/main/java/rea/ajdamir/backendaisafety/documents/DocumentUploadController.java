package rea.ajdamir.backendaisafety.documents;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/documents")
public class DocumentUploadController {

    private final DocumentStorageService documentStorageService;

    public DocumentUploadController(
        DocumentStorageService documentStorageService
    ) {
        this.documentStorageService = documentStorageService;
    }

    @PostMapping(
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<DocumentUploadResponse> upload(
        @RequestParam("file") MultipartFile file,
        Authentication authentication
    ) {
        return ResponseEntity.ok(
            documentStorageService.store(file, authentication.getName())
        );
    }
}