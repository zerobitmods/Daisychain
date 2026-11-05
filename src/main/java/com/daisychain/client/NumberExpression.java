package com.daisychain.client;

import java.util.OptionalLong;

/**
 * Evaluates what a player types into a number box: whole numbers with + - * / and parentheses,
 * e.g. "64*7", "64+1", "(1000-36)/2". Division rounds down. Anything else (letters, empty, divide by zero,
 * absurdly long input, results beyond +-1 trillion) gives an empty result. Client only; the server still checks ranges.
 */
final class NumberExpression {
    private static final int MAX_LENGTH = 64;
    private static final long LIMIT = 1_000_000_000_000L;

    private final String text;
    private int pos;

    private NumberExpression(String text) {
        this.text = text;
    }

    static OptionalLong evaluate(String input) {
        if (input == null) {
            return OptionalLong.empty();
        }
        String text = input.replace(" ", "").replace(",", "").replace("_", "");
        if (text.isEmpty() || text.length() > MAX_LENGTH) {
            return OptionalLong.empty();
        }
        try {
            NumberExpression parser = new NumberExpression(text);
            long value = parser.sum();
            if (parser.pos != text.length()) {
                return OptionalLong.empty(); // leftover characters
            }
            return OptionalLong.of(value);
        } catch (ArithmeticException | IllegalArgumentException e) {
            return OptionalLong.empty();
        }
    }

    private long sum() {
        long value = product();
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '+') {
                pos++;
                value = check(value + product());
            } else if (c == '-') {
                pos++;
                value = check(value - product());
            } else {
                break;
            }
        }
        return value;
    }

    private long product() {
        long value = factor();
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '*' || c == 'x' || c == 'X') {
                pos++;
                value = check(Math.multiplyExact(value, factor()));
            } else if (c == '/') {
                pos++;
                long divisor = factor();
                if (divisor == 0) {
                    throw new ArithmeticException("divide by zero");
                }
                value = Math.floorDiv(value, divisor);
            } else {
                break;
            }
        }
        return value;
    }

    private long factor() {
        if (pos >= text.length()) {
            throw new IllegalArgumentException("missing number");
        }
        char c = text.charAt(pos);
        if (c == '-') {
            pos++;
            return -factor();
        }
        if (c == '(') {
            pos++;
            long value = sum();
            if (pos >= text.length() || text.charAt(pos) != ')') {
                throw new IllegalArgumentException("missing )");
            }
            pos++;
            return value;
        }
        int start = pos;
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
            pos++;
        }
        if (start == pos) {
            throw new IllegalArgumentException("expected a number");
        }
        if (pos - start > 13) {
            throw new IllegalArgumentException("number too long");
        }
        return check(Long.parseLong(text.substring(start, pos)));
    }

    private static long check(long value) {
        if (value > LIMIT || value < -LIMIT) {
            throw new ArithmeticException("too big");
        }
        return value;
    }
}
