package com.sakupay.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByOwnerIdOrderById(Long ownerId);

    /**
     * The owner is part of the query, so another user's account is simply "not found":
     * there is no code path that loads an account first and checks ownership afterwards (IDOR-safe by design).
     */
    Optional<Account> findByAccountNumberAndOwnerId(String accountNumber, Long ownerId);

    long countByOwnerId(Long ownerId);

    boolean existsByAccountNumber(String accountNumber);
}
