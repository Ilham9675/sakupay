package com.sakupay.ledger;

import com.sakupay.ledger.ReconciliationService.Report;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Audit", description = "Read-only audit trail and reconciliation (AUDITOR role)")
public class ReconciliationController {

    private final ReconciliationService reconciliation;

    public ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @GetMapping("/api/v1/reconciliation")
    @Operation(summary = "Check that balances match the ledger, every transaction balances and all money sums to zero")
    public Report reconcile() {
        return reconciliation.run();
    }
}
