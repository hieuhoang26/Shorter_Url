package com.hhh.url.shorter_url.util;

public final class CacheKeyConstant {

    public static final String SHORT_URL_PREFIX = "short:url:";
    public static final String NULL_SENTINEL = "NULL";
    public static final long NULL_TTL_MINUTES = 5;
    public static final long DEFAULT_TTL_HOURS = 24;

    private CacheKeyConstant() {}

    public static String shortUrlKey(String shortCode) {
        return SHORT_URL_PREFIX + shortCode;
    }
}
