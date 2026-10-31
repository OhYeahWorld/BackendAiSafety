package rea.ajdamir.backendaisafety;

import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfTokenController {

    @GetMapping(value = "/csrf", produces = MediaType.TEXT_PLAIN_VALUE)
    public String csrf(CsrfToken csrfToken) {
        return csrfToken.getToken();
    }
}