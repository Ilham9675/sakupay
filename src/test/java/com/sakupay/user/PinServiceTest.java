package com.sakupay.user;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PinServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {"000000", "111111", "123456", "234567", "987654", "654321", "012345"})
    void repeatedAndSequentialPinsAreTrivial(String pin) {
        assertThat(PinService.isTrivial(pin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"246810", "135790", "102938", "112233", "120456"})
    void otherPinsAreAccepted(String pin) {
        assertThat(PinService.isTrivial(pin)).isFalse();
    }
}
