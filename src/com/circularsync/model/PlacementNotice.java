package com.circularsync.model;

import com.circularsync.exceptions.InvalidNoticeFormatException;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Training and placement circular: recruitment drives, internship offers and
 * pre-placement talks.
 *
 * <p>Extra headers: {@code COMPANY}, {@code ROLE}, {@code ELIGIBILITY}
 * (required) and {@code PACKAGE_LPA}, {@code MIN_CGPA}, {@code DRIVE_DATE}
 * (optional).</p>
 */
public class PlacementNotice extends Notice {

    private static final Set<String> REQUIRED = Set.of("COMPANY", "ROLE", "ELIGIBILITY");

    private static final Map<String, Integer> KEYWORD_WEIGHTS = Map.of(
            "mandatory", 8,
            "dream", 8,
            "registration", 6,
            "final-year", 4,
            "internship", 4,
            "ppo", 5,
            "pre-placement-talk", 3,
            "off-campus", 2);

    private static final int CATEGORY_BASE = 25;

    private String company;
    private String role;
    private String eligibility;
    private double packageLpa;
    private double minCgpa;
    private LocalDate driveDate;

    public PlacementNotice(String sourceName, String rawContent, LocalDate referenceDate) {
        super(sourceName, rawContent, referenceDate);
    }

    @Override
    public NoticeCategory getCategory() {
        return NoticeCategory.PLACEMENT;
    }

    @Override
    protected Set<String> getTypeSpecificRequiredHeaders() {
        return REQUIRED;
    }

    @Override
    protected void parseTypeSpecificMetadata(Map<String, String> headerValues) throws InvalidNoticeFormatException {
        company = headerValues.get("COMPANY");
        role = headerValues.get("ROLE");
        eligibility = headerValues.get("ELIGIBILITY");
        String pkg = headerValues.get("PACKAGE_LPA");
        packageLpa = pkg == null ? 0.0 : parseDecimal("PACKAGE_LPA", pkg);
        String cgpa = headerValues.get("MIN_CGPA");
        minCgpa = cgpa == null ? 0.0 : parseDecimal("MIN_CGPA", cgpa);
        if (minCgpa > 10.0) {
            throw new InvalidNoticeFormatException(getSourceName(), "MIN_CGPA " + minCgpa + " exceeds the 10-point scale");
        }
        driveDate = headerValues.containsKey("DRIVE_DATE") ? parseDate("DRIVE_DATE") : null;
    }

    /**
     * Score = category base + shared urgency of the registration deadline
     * + a compensation band bonus + weighted keywords.
     */
    @Override
    public int calculatePriorityScore() {
        int packageBand;
        if (packageLpa >= 20) {
            packageBand = 15;
        } else if (packageLpa >= 10) {
            packageBand = 10;
        } else if (packageLpa >= 5) {
            packageBand = 5;
        } else {
            packageBand = 0;
        }
        return CATEGORY_BASE + urgencyPoints() + packageBand + keywordPoints(KEYWORD_WEIGHTS);
    }

    @Override
    public String formatSummary() {
        String pkg = packageLpa > 0 ? String.format("%.1f LPA", packageLpa) : "package n/a";
        String drive = driveDate == null ? "drive date TBA" : "drive on " + driveDate;
        return String.format("[PLACEMENT %s] %s | %s - %s, %s, %s | register by %s (%d day%s left) | score %d",
                getId(), getTitle(), company, role, pkg, drive,
                getDeadline(), getDaysUntilDeadline(), getDaysUntilDeadline() == 1 ? "" : "s",
                getPriorityScore());
    }

    public String getCompany() {
        return company;
    }

    public String getRole() {
        return role;
    }

    public String getEligibility() {
        return eligibility;
    }

    public double getPackageLpa() {
        return packageLpa;
    }

    public double getMinCgpa() {
        return minCgpa;
    }

    /** @return the drive date, or null when the circular does not announce one */
    public LocalDate getDriveDate() {
        return driveDate;
    }
}
