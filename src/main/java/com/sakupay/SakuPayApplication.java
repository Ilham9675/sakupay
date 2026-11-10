package com.sakupay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * SakuPay: a digital wallet / mini core banking backend.
 * Code is organised by feature (auth, account, ledger, transfer, ...); shared pieces live in {@code common}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SakuPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SakuPayApplication.class, args);
    }
}
