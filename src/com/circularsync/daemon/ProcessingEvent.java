package com.circularsync.daemon;

import com.circularsync.model.Notice;

import java.time.LocalDateTime;

/**
 * Immutable record of what happened to one notice file in the pipeline. The
 * daemon publishes one event per outcome to every registered
 * {@link ProcessingListener}.
 */
public final class ProcessingEvent {

    /** Every terminal outcome a file can reach. */
    public enum Outcome {
        INDEXED, REINDEXED, DUPLICATE, REJECTED_FORMAT, REJECTED_EXPIRED, REMOVED, IO_ERROR
    }

    private final String fileName;
    private final Outcome outcome;
    private final Notice notice;
    private final String message;
    private final long fileSize;
    private final LocalDateTime timestamp;

    ProcessingEvent(String fileName, Outcome outcome, Notice notice, String message, long fileSize) {
        this.fileName = fileName;
        this.outcome = outcome;
        this.notice = notice;
        this.message = message;
        this.fileSize = fileSize;
        this.timestamp = LocalDateTime.now();
    }

    public String getFileName() {
        return fileName;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    /** @return the parsed notice, or null for rejections, errors and removals of unindexed files */
    public Notice getNotice() {
        return notice;
    }

    public String getMessage() {
        return message;
    }

    /** @return the file size in bytes, or -1 when the file no longer exists */
    public long getFileSize() {
        return fileSize;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }
}
