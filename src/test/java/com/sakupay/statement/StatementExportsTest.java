package com.sakupay.statement;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class StatementExportsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Makan siang|\"Makan siang\"",
            "=1+1|\"'=1+1\"",
            "+62812|\"'+62812\"",
            "-cmd|\"'-cmd\"",
            "@SUM(A1)|\"'@SUM(A1)\"",
            "-50000|\"-50000\"",
            "say \"hi\"|\"say \"\"hi\"\"\""
    })
    void cellsAreQuotedAndFormulasDefused(String value, String expected) {
        assertThat(StatementExports.csvCell(value)).isEqualTo(expected);
    }
}
