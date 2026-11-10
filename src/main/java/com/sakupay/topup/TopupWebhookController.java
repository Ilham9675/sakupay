package com.sakupay.topup;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called by the partner bank, not by users: no JWT, authenticated by the HMAC signature instead.
 * The body is taken as the raw string so the signature is checked over exactly the bytes that were sent.
 */
@RestController
@Tag(name = "Webhooks", description = "Called by the partner bank (HMAC-signed, no JWT)")
@SecurityRequirements
public class TopupWebhookController {

    private final TopupService topupService;

    public TopupWebhookController(TopupService topupService) {
        this.topupService = topupService;
    }

    @PostMapping(value = "/api/v1/webhooks/topups", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "A payment into a virtual account. Headers: X-Timestamp (unix seconds), "
            + "X-Signature (sha256=<hex HMAC of \"timestamp.body\">). Safe to deliver more than once.")
    public TopupService.Result receive(@RequestHeader(name = "X-Timestamp", required = false) String timestamp,
                                       @RequestHeader(name = "X-Signature", required = false) String signature,
                                       @RequestBody String rawBody) {
        return topupService.receive(timestamp, signature, rawBody);
    }
}
