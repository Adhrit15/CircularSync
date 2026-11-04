package com.circularsync.model;

import com.circularsync.exceptions.InvalidNoticeFormatException;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Student club or society announcement: workshops, hackathons, cultural
 * events and recruitment of volunteers.
 *
 * <p>Extra headers: {@code CLUB_NAME}, {@code EVENT_DATE}, {@code EVENT_VENUE}
 * (required) and {@code SEATS} (optional).</p>
 */
public class ClubNotice extends Notice {

    private static final Set<String> REQUIRED = Set.of("CLUB_NAME", "EVENT_DATE", "EVENT_VENUE");

    private static final Map<String, Integer> KEYWORD_WEIGHTS = Map.of(
            "hackathon", 6,
            "registration", 5,
            "competition", 5,
            "workshop", 4,
            "limited-seats", 4,
            "certificate", 3,
            "free", 2,
            "volunteers", 2);

    /** Optional activities start lowest so they never bury mandatory notices. */
    private static final int CATEGORY_BASE = 10;

    private String clubName;
    private LocalDate eventDate;
    private String eventVenue;
    private int seats;

    public ClubNotice(String sourceName, String rawContent, LocalDate referenceDate) {
        super(sourceName, rawContent, referenceDate);
    }

    @Override
    public NoticeCategory getCategory() {
        return NoticeCategory.CLUB;
    }

    @Override
    protected Set<String> getTypeSpecificRequiredHeaders() {
        return REQUIRED;
    }

    @Override
    protected void parseTypeSpecificMetadata(Map<String, String> headerValues) throws InvalidNoticeFormatException {
        clubName = headerValues.get("CLUB_NAME");
        eventVenue = headerValues.get("EVENT_VENUE");
        eventDate = parseDate("EVENT_DATE");
        String seatsValue = headerValues.get("SEATS");
        if (seatsValue == null) {
            seats = 0;
        } else {
            try {
                seats = Integer.parseInt(seatsValue.trim());
            } catch (NumberFormatException e) {
                throw new InvalidNoticeFormatException(getSourceName(), "SEATS value '" + seatsValue + "' is not an integer", e);
            }
            if (seats < 0) {
                throw new InvalidNoticeFormatException(getSourceName(), "SEATS must not be negative");
            }
        }
    }

    /**
     * Score = low category base + three quarters of the shared urgency
     * + weighted keywords + a scarcity bonus when seats are limited.
     */
    @Override
    public int calculatePriorityScore() {
        int scarcity = seats > 0 && seats <= 60 ? 4 : 0;
        return CATEGORY_BASE + (urgencyPoints() * 3) / 4 + keywordPoints(KEYWORD_WEIGHTS) + scarcity;
    }

    @Override
    public String formatSummary() {
        String seatInfo = seats > 0 ? seats + " seats" : "open entry";
        return String.format("[CLUB %s] %s | %s event on %s at %s, %s | register by %s (%d day%s left) | score %d",
                getId(), getTitle(), clubName, eventDate, eventVenue, seatInfo,
                getDeadline(), getDaysUntilDeadline(), getDaysUntilDeadline() == 1 ? "" : "s",
                getPriorityScore());
    }

    public String getClubName() {
        return clubName;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public String getEventVenue() {
        return eventVenue;
    }

    /** @return the seat limit, or 0 for open entry */
    public int getSeats() {
        return seats;
    }
}
