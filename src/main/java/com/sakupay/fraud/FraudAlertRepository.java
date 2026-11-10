package com.sakupay.fraud;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface FraudAlertRepository extends JpaRepository<FraudAlert, Long> {

    Optional<FraudAlert> findByTransactionId(Long transactionId);

    Page<FraudAlert> findByStatusOrderById(FraudAlert.Status status, Pageable pageable);

    Page<FraudAlert> findAllByOrderByIdDesc(Pageable pageable);

    /** Locked, so two admins deciding the same alert at once cannot both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from FraudAlert a where a.id = :id")
    Optional<FraudAlert> findByIdForUpdate(Long id);
}
