package com.sakupay.statement;

import com.sakupay.auth.CurrentUser;
import com.sakupay.statement.StatementService.Period;
import com.sakupay.statement.StatementService.Statement;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Statements of the caller's own accounts; any other account is "not found". */
@RestController
@RequestMapping("/api/v1/accounts/{accountNumber}")
@Tag(name = "Statements", description = "Account statements (mutasi) as JSON, CSV or PDF")
public class StatementController {

    private final StatementService statements;

    public StatementController(StatementService statements) {
        this.statements = statements;
    }

    @GetMapping("/statement")
    @Operation(summary = "Statement for a period (default: last 30 days), with opening and closing balance")
    public Statement statement(@AuthenticationPrincipal Jwt jwt, @PathVariable String accountNumber,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "50") int size) {
        return statements.statement(CurrentUser.id(jwt), accountNumber, statements.period(from, to),
                Math.max(page, 0), Math.min(Math.max(size, 1), 200));
    }

    @GetMapping("/statement.csv")
    @Operation(summary = "Download the statement as CSV (opens in Excel)")
    public ResponseEntity<byte[]> csv(@AuthenticationPrincipal Jwt jwt, @PathVariable String accountNumber,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Statement s = statements.export(CurrentUser.id(jwt), accountNumber, statements.period(from, to));
        return download(StatementExports.csv(s), new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8),
                filename(s, "csv"));
    }

    @GetMapping("/statement.pdf")
    @Operation(summary = "Download the statement as PDF")
    public ResponseEntity<byte[]> pdf(@AuthenticationPrincipal Jwt jwt, @PathVariable String accountNumber,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Statement s = statements.export(CurrentUser.id(jwt), accountNumber, statements.period(from, to));
        return download(StatementExports.pdf(s), MediaType.APPLICATION_PDF, filename(s, "pdf"));
    }

    private static String filename(Statement s, String extension) {
        return "sakupay-" + s.accountNumber() + "-" + s.from() + "-to-" + s.to() + "." + extension;
    }

    private static ResponseEntity<byte[]> download(byte[] body, MediaType type, String filename) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                // Bank statements must not linger in shared caches or proxies
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
