package com.sakupay.account;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

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

    Optional<Account> findByAccountNumber(String accountNumber);

    /** Only the id, so no entity is loaded (and cached) before it is locked. */
    @Query("select a.id from Account a where a.accountNumber = :accountNumber and a.ownerId = :ownerId")
    Optional<Long> findIdByAccountNumberAndOwnerId(String accountNumber, Long ownerId);

    @Query("select a.id from Account a where a.accountNumber = :accountNumber and a.type = :type")
    Optional<Long> findIdByAccountNumberAndType(String accountNumber, AccountType type);

    /** {@code SELECT ... FOR UPDATE}: the balance read here cannot change until this transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(Long id);
}
