package com.sakupay.user;

import com.sakupay.common.error.ApiException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Indonesian mobile numbers, normalised to E.164 so "0812-3456-7890" and "+6281234567890" are the same user. */
public final class PhoneNumbers {

    private static final Pattern MOBILE = Pattern.compile("^(?:\\+62|62|0)(8[1-9]\\d{6,10})$");

    private PhoneNumbers() {
    }

    public static String normalize(String raw) {
        String compact = raw == null ? "" : raw.replaceAll("[\\s\\-().]", "");
        Matcher m = MOBILE.matcher(compact);
        if (!m.matches()) {
            throw ApiException.badRequest("INVALID_PHONE", "Use an Indonesian mobile number, e.g. 081234567890.");
        }
        return "+62" + m.group(1);
    }
}
