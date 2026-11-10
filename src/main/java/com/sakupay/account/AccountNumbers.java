package com.sakupay.account;

import java.util.random.RandomGenerator;

/**
 * 10-digit account numbers: prefix {@code 88}, 7 random digits and a Luhn check digit.
 * The check digit catches typos (one wrong digit, two swapped neighbours) before a transfer is even attempted.
 */
public final class AccountNumbers {

    static final String PREFIX = "88";
    private static final int RANDOM_DIGITS = 7;

    private AccountNumbers() {
    }

    public static String generate(RandomGenerator random) {
        StringBuilder body = new StringBuilder(PREFIX);
        for (int i = 0; i < RANDOM_DIGITS; i++) {
            body.append(random.nextInt(10));
        }
        return body.append(checkDigit(body)).toString();
    }

    public static boolean isValid(String number) {
        if (number == null || !number.matches("\\d{10}")) {
            return false;
        }
        return checkDigit(number.substring(0, 9)) == number.charAt(9) - '0';
    }

    /** Luhn: double every second digit from the right, sum the digits, round up to a multiple of 10. */
    static int checkDigit(CharSequence body) {
        int sum = 0;
        boolean dbl = true;
        for (int i = body.length() - 1; i >= 0; i--) {
            int d = body.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return (10 - sum % 10) % 10;
    }
}
