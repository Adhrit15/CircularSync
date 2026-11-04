package com.circularsync.model;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Administrative circular that is not an examination, placement or club
 * notice: fee payment, scholarships, holidays, hostel and library rules.
 * Requires only the common headers.
 */
public class GeneralNotice extends Notice {

    private static final Map<String, Integer> KEYWORD_WEIGHTS = Map.of(
            "mandatory", 10,
            "fee", 8,
            "scholarship", 6,
            "registration", 5,
            "document-submission", 5,
            "attendance", 4,
            "hostel", 3,
            "holiday", 2);

    private static final int CATEGORY_BASE = 15;

    public GeneralNotice(String sourceName, String rawContent, LocalDate referenceDate) {
        super(sourceName, rawContent, referenceDate);
    }

    @Override
    public NoticeCategory getCategory() {
        return NoticeCategory.GENERAL;
    }

    @Override
    protected Set<String> getTypeSpecificRequiredHeaders() {
        return Set.of();
    }

    @Override
    protected void parseTypeSpecificMetadata(Map<String, String> headerValues) {
        // No category-specific headers.
    }

    /** Score = category base + shared urgency + weighted keywords. */
    @Override
    public int calculatePriorityScore() {
        return CATEGORY_BASE + urgencyPoints() + keywordPoints(KEYWORD_WEIGHTS);
    }

    @Override
    public String formatSummary() {
        return String.format("[GENERAL %s] %s | issued by %s | act by %s (%d day%s left) | score %d",
                getId(), getTitle(), getIssuer(), getDeadline(), getDaysUntilDeadline(),
                getDaysUntilDeadline() == 1 ? "" : "s", getPriorityScore());
    }
}
