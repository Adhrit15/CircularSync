package com.circularsync.model;

import com.circularsync.exceptions.InvalidNoticeFormatException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

/**
 * Examination circular: timetables, hall-ticket collection, re-examination
 * registration and similar academic-assessment announcements.
 *
 * <p>Extra headers: {@code COURSE_CODE}, {@code EXAM_DATE}, {@code VENUE}
 * (all required) and {@code EXAM_TYPE} (optional).</p>
 */
public class ExamNotice extends Notice {

    private static final Set<String> REQUIRED = Set.of("COURSE_CODE", "EXAM_DATE", "VENUE");

    /** Keyword weights reflecting the academic consequence of missing the notice. */
    private static final Map<String, Integer> KEYWORD_WEIGHTS = Map.of(
            "mandatory", 10,
            "hall-ticket", 8,
            "end-semester", 8,
            "re-exam", 6,
            "backlog", 6,
            "mid-semester", 5,
            "revaluation", 4,
            "practical", 3);

    /** Every examination outranks optional activities by this baseline. */
    private static final int CATEGORY_BASE = 30;

    private String courseCode;
    private LocalDate examDate;
    private String venue;
    private String examType;

    public ExamNotice(String sourceName, String rawContent, LocalDate referenceDate) {
        super(sourceName, rawContent, referenceDate);
    }

    @Override
    public NoticeCategory getCategory() {
        return NoticeCategory.EXAM;
    }

    @Override
    protected Set<String> getTypeSpecificRequiredHeaders() {
        return REQUIRED;
    }

    @Override
    protected void parseTypeSpecificMetadata(Map<String, String> headerValues) throws InvalidNoticeFormatException {
        courseCode = headerValues.get("COURSE_CODE").toUpperCase(java.util.Locale.ROOT);
        venue = headerValues.get("VENUE");
        examType = headerValues.getOrDefault("EXAM_TYPE", "Examination");
        examDate = parseDate("EXAM_DATE");
        if (examDate.isBefore(getIssueDate())) {
            throw new InvalidNoticeFormatException(getSourceName(),
                    "EXAM_DATE " + examDate + " precedes issue DATE " + getIssueDate());
        }
    }

    /**
     * Score = category base + shared urgency of the action deadline
     * + proximity of the examination itself + weighted keywords.
     */
    @Override
    public int calculatePriorityScore() {
        long daysToExam = ChronoUnit.DAYS.between(getReferenceDate(), examDate);
        int examProximity;
        if (daysToExam <= 3) {
            examProximity = 18;
        } else if (daysToExam <= 7) {
            examProximity = 12;
        } else if (daysToExam <= 14) {
            examProximity = 6;
        } else {
            examProximity = 0;
        }
        return CATEGORY_BASE + urgencyPoints() + examProximity + keywordPoints(KEYWORD_WEIGHTS);
    }

    @Override
    public String formatSummary() {
        return String.format("[EXAM %s] %s | %s on %s at %s | act by %s (%d day%s left) | score %d",
                getId(), getTitle(), courseCode + " " + examType, examDate, venue,
                getDeadline(), getDaysUntilDeadline(), getDaysUntilDeadline() == 1 ? "" : "s",
                getPriorityScore());
    }

    public String getCourseCode() {
        return courseCode;
    }

    public LocalDate getExamDate() {
        return examDate;
    }

    public String getVenue() {
        return venue;
    }

    public String getExamType() {
        return examType;
    }
}
