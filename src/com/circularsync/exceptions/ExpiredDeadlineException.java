package com.circularsync.exceptions;

import java.time.LocalDate;

/**
 * Thrown when a structurally valid notice carries a deadline that has already
 * passed relative to the processing date. Such notices are audited and
 * archived rather than indexed, so students never see stale actions.
 */
public class ExpiredDeadlineException extends CircularSyncException {

    private static final long serialVersionUID = 1L;

    private final LocalDate deadline;
    private final LocalDate referenceDate;

    public ExpiredDeadlineException(String sourceName, LocalDate deadline, LocalDate referenceDate) {
        super(sourceName, "Notice '" + sourceName + "' expired on " + deadline
                + " (processing date " + referenceDate + ")");
        this.deadline = deadline;
        this.referenceDate = referenceDate;
    }

    public LocalDate getDeadline() {
        return deadline;
    }

    public LocalDate getReferenceDate() {
        return referenceDate;
    }
}
