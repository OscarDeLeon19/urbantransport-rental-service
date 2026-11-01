package com.urbantransport.rental.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Locale;
import java.util.regex.Pattern;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NitUtils {

    public static final String GENERIC_NIT = "CF";

    private static final Pattern VALID_NIT = Pattern.compile("^[0-9]+[0-9K]$");

    public static String normalize(String raw) {
        return raw == null ? null : raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    public static boolean isValid(String nit) {
        return nit != null && (GENERIC_NIT.equals(nit) || VALID_NIT.matcher(nit).matches());
    }
}
