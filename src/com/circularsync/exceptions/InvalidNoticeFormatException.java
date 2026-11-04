package com.circularsync.exceptions;

/**
 * Thrown when a notice file cannot be interpreted: a mandatory header is
 * missing, a value has the wrong shape (for example an unparseable date),
 * the notice type is unknown, or the file is empty or oversized.
 */
public class InvalidNoticeFormatException extends CircularSyncException {

    private static final long serialVersionUID = 1L;

    /** 1-based line number of the offending line, or -1 when not line-specific. */
    private final int lineNumber;

    public InvalidNoticeFormatException(String sourceName, String message) {
        this(sourceName, message, -1);
    }

    public InvalidNoticeFormatException(String sourceName, String message, int lineNumber) {
        super(sourceName, buildMessage(sourceName, message, lineNumber));
        this.lineNumber = lineNumber;
    }

    public InvalidNoticeFormatException(String sourceName, String message, Throwable cause) {
        super(sourceName, buildMessage(sourceName, message, -1), cause);
        this.lineNumber = -1;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    private static String buildMessage(String sourceName, String message, int lineNumber) {
        StringBuilder sb = new StringBuilder("Invalid notice format in '").append(sourceName).append('\'');
        if (lineNumber > 0) {
            sb.append(" at line ").append(lineNumber);
        }
        return sb.append(": ").append(message).toString();
    }
}
