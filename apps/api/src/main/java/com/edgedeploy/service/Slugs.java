package com.edgedeploy.service;

import java.text.Normalizer;
import java.util.Locale;

/** Turns display names into DNS-label-safe slugs ({@code [a-z0-9-]}, no leading/trailing hyphen). */
public final class Slugs {

    /**
     * Leaves room for "-{8-char deployment id}" (and a uniqueness suffix) inside the 63-char DNS label limit.
     */
    static final int MAX_LENGTH = 40;
    private static final String FALLBACK = "app";

    private Slugs() {
    }

    public static String slugify(String input) {
        String ascii = Normalizer.normalize(input, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_LENGTH) {
            slug = slug.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? FALLBACK : slug;
    }
}
