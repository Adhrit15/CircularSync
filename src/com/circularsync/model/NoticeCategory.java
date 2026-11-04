package com.circularsync.model;

import java.util.Locale;
import java.util.Optional;

/**
 * The closed set of notice categories CircularSync understands. The value of
 * a notice file's TYPE header must match one of these constants
 * (case-insensitively).
 */
public enum NoticeCategory {

    EXAM("EXM", "Examination"),
    PLACEMENT("PLC", "Placement / Internship"),
    CLUB("CLB", "Club / Student Activity");

    private final String idPrefix;
    private final String displayName;

    NoticeCategory(String idPrefix, String displayName) {
        this.idPrefix = idPrefix;
        this.displayName = displayName;
    }

    public String getIdPrefix() {
        return idPrefix;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** Lenient lookup used by the parser and the CLI. */
    public static Optional<NoticeCategory> fromLabel(String label) {
        if (label == null) {
            return Optional.empty();
        }
        String normalized = label.trim().toUpperCase(Locale.ROOT);
        for (NoticeCategory category : values()) {
            if (category.name().equals(normalized)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
