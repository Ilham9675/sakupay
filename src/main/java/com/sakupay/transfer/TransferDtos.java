package com.sakupay.transfer;

import com.sakupay.ledger.TransactionStatus;
import com.sakupay.ledger.TransactionType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class TransferDtos {

    /** Rp1.000.000.000: a sanity cap per request; the daily limit is usually lower. */
    static final long MAX_AMOUNT = 1_000_000_000L;

    private TransferDtos() {
    }

    public record TransferRequest(
            @NotBlank String sourceAccount,
            @NotBlank String destinationAccount,
            @NotNull @Positive @Max(MAX_AMOUNT) Long amount,
            @Size(max = 140) String description,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "must be exactly 6 digits") String pin) {

        @Override
        public String toString() {
            return "TransferRequest[" + sourceAccount + " -> " + destinationAccount + ", amount=" + amount + ", pin=****]";
        }
    }

    public record DepositRequest(
            @NotNull @Positive @Max(MAX_AMOUNT) Long amount,
            @Size(max = 140) String description) {
    }

    /**
     * The result of a money movement. {@code balanceAfter} is the balance of the account the caller acted on
     * (the source of a transfer, the destination of a deposit) right after this transaction.
     */
    public record TransactionResponse(String reference, TransactionType type, TransactionStatus status, long amount,
                                      String sourceAccount, String destinationAccount, String description,
                                      long balanceAfter, Instant createdAt) {
    }
}
