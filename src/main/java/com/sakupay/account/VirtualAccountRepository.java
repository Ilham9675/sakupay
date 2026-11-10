package com.sakupay.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VirtualAccountRepository extends JpaRepository<VirtualAccount, Long> {

    Optional<VirtualAccount> findByVaNumber(String vaNumber);
}
