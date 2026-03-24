package com.hhh.url.shorter_url.util;

public class Base62Code {
    private static final String CHARACTER_SET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int CHARACTER_LENGTH = CHARACTER_SET.length();

    public static String encode(Long id) {
        if (id == 0.0) {
            return "0";
        }
        StringBuilder builder = new StringBuilder();
        while (id > 0){
            builder.append(CHARACTER_SET.charAt((int) (id % CHARACTER_LENGTH)));
            id = id / CHARACTER_LENGTH;
        }
        return builder.toString();
    }

    public static Long decode(String shortCode) {
        long id = 0;
        long multiplier = 1;

        for (int i = 0; i < shortCode.length(); i++) {
            int value = CHARACTER_SET.indexOf(shortCode.charAt(i));
            if (value == -1) {
                throw new IllegalArgumentException("Invalid character in short code: " + shortCode.charAt(i));
            }
            id += value * multiplier;
            multiplier *= CHARACTER_LENGTH;
        }
        return id;
    }

}
