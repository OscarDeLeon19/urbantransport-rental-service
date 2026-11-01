package com.urbantransport.rental.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NitUtilsTest {

    @ParameterizedTest
    @CsvSource({
            "1234567-8, 12345678",
            "' 1234567 k ', 1234567K",
            "cf, CF",
            "12-34-56, 123456"
    })
    void normalize_removesSpacesAndHyphensAndUppercases(String raw, String expected) {
        assertEquals(expected, NitUtils.normalize(raw));
    }

    @Test
    void normalize_null_returnsNull() {
        assertNull(NitUtils.normalize(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678", "1234567K", "10", "CF"})
    void isValid_canonicalNitOrGeneric_returnsTrue(String nit) {
        assertTrue(NitUtils.isValid(nit));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1", "K123", "12345X", "cf", "1234567k", "1234-5"})
    void isValid_otherValues_returnsFalse(String nit) {
        assertFalse(NitUtils.isValid(nit));
    }
}
