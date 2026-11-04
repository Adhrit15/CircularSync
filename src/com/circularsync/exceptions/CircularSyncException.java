package com.circularsync.exceptions;

/**
 * Root of the CircularSync checked-exception hierarchy. Every failure that a
 * caller is expected to recover from (a malformed notice, an expired
 * deadline) derives from this type, so the processing pipeline can handle
 * domain failures uniformly while still distinguishing them by subtype.
 */
public class CircularSyncException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String sourceName;

    public CircularSyncException(String sourceName, String message) {
        super(message);
        this.sourceName = sourceName;
    }

    public CircularSyncException(String sourceName, String message, Throwable cause) {
        super(message, cause);
        this.sourceName = sourceName;
    }

    /** Name of the notice file (or other source) that triggered the failure. */
    public String getSourceName() {
        return sourceName;
    }
}
