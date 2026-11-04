package com.circularsync.model;

import com.circularsync.exceptions.ExpiredDeadlineException;
import com.circularsync.exceptions.InvalidNoticeFormatException;
import com.circularsync.interfaces.Processable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Abstract base of every campus notice.
 *
 * <p>A notice file is plain text (or Markdown) made of a header block and a
 * body, separated by a line containing only {@code ---}:</p>
 *
 * <pre>
 * TYPE: EXAM
 * TITLE: End Semester Examination - CS301
 * DATE: 2026-11-02
 * DEADLINE: 2026-11-20
 * PRIORITY_KEYS: mandatory, hall-ticket
 * ...type specific headers...
 * ---
 * Free-form body text.
 * </pre>
 *
 * <p>This class implements the {@link Processable} contract for the shared
 * headers and delegates type-specific work to subclasses through the
 * template hooks {@link #getTypeSpecificRequiredHeaders()} and
 * {@link #parseTypeSpecificMetadata(Map)}. Ranking and presentation are
 * expressed as the abstract methods {@link #calculatePriorityScore()} and
 * {@link #formatSummary()}, which each subclass overrides.</p>
 *
 * <p>Instances are effectively immutable once {@link #isProcessed()} returns
 * true: all fields are written by the single worker thread that parses the
 * notice, and the {@code volatile} write of {@code processed} publishes them
 * safely to every other thread.</p>
 */
public abstract class Notice implements Processable, Comparable<Notice> {

    /** Headers every notice must carry regardless of category. */
    public static final Set<String> COMMON_REQUIRED_HEADERS =
            Collections.unmodifiableSet(new LinkedHashSet<>(
                    java.util.List.of("TYPE", "TITLE", "DATE", "DEADLINE", "PRIORITY_KEYS")));

    public static final String HEADER_TERMINATOR = "---";
    public static final int MAX_SCORE = 100;

    private static final Pattern HEADER_PATTERN = Pattern.compile("^([A-Z][A-Z0-9_]*)\\s*:\\s*(.*)$");

    private final String sourceName;
    private final String rawContent;
    private final LocalDate referenceDate;

    private final Map<String, String> headers = new LinkedHashMap<>();
    private String body = "";

    private String id;
    private String title;
    private String issuer;
    private LocalDate issueDate;
    private LocalDate deadline;
    private Set<String> priorityKeys = Collections.emptySet();
    private int priorityScore;
    private boolean validated;
    private volatile boolean processed;

    /**
     * @param sourceName    file name the notice was read from (used in messages)
     * @param rawContent    complete file content
     * @param referenceDate the "today" against which urgency and expiry are judged
     */
    protected Notice(String sourceName, String rawContent, LocalDate referenceDate) {
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
        this.rawContent = Objects.requireNonNull(rawContent, "rawContent");
        this.referenceDate = Objects.requireNonNull(referenceDate, "referenceDate");
    }

    // ------------------------------------------------------------------
    // Abstract behaviour overridden by every concrete notice type
    // ------------------------------------------------------------------

    /** @return the category this subclass represents. */
    public abstract NoticeCategory getCategory();

    /**
     * Computes a priority score in the range 0..{@value #MAX_SCORE}. Higher
     * means the notice should be shown earlier. Implementations combine the
     * shared urgency curve with category-specific signals.
     */
    public abstract int calculatePriorityScore();

    /** @return a one-line, human readable digest of the notice. */
    public abstract String formatSummary();

    /** @return header names that are mandatory for this category only. */
    protected abstract Set<String> getTypeSpecificRequiredHeaders();

    /**
     * Converts category-specific header values into typed fields.
     *
     * @param headerValues all headers of the notice, keys upper-cased
     * @throws InvalidNoticeFormatException if a value cannot be converted
     */
    protected abstract void parseTypeSpecificMetadata(Map<String, String> headerValues)
            throws InvalidNoticeFormatException;

    // ------------------------------------------------------------------
    // Processable implementation
    // ------------------------------------------------------------------

    @Override
    public final void validateFormat() throws InvalidNoticeFormatException {
        headers.clear();
        String[] lines = rawContent.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

        int terminatorLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = normalizeHeaderLine(lines[i]);
            if (line.isEmpty() || line.startsWith("#")) {
                // Blank lines, comments and Markdown headings are permitted inside the header block.
                continue;
            }
            if (line.equals(HEADER_TERMINATOR)) {
                terminatorLine = i;
                break;
            }
            Matcher matcher = HEADER_PATTERN.matcher(line);
            if (!matcher.matches()) {
                throw new InvalidNoticeFormatException(sourceName,
                        "expected 'KEY: value' header but found \"" + abbreviate(lines[i].trim()) + "\"", i + 1);
            }
            String key = matcher.group(1);
            String value = matcher.group(2).trim();
            if (headers.containsKey(key)) {
                throw new InvalidNoticeFormatException(sourceName, "duplicate header " + key, i + 1);
            }
            if (value.isEmpty()) {
                throw new InvalidNoticeFormatException(sourceName, "header " + key + " has an empty value", i + 1);
            }
            headers.put(key, value);
        }

        if (terminatorLine < 0) {
            throw new InvalidNoticeFormatException(sourceName,
                    "header block is not terminated by a '" + HEADER_TERMINATOR + "' line");
        }

        Set<String> required = new LinkedHashSet<>(COMMON_REQUIRED_HEADERS);
        required.addAll(getTypeSpecificRequiredHeaders());
        Set<String> missing = new TreeSet<>();
        for (String key : required) {
            if (!headers.containsKey(key)) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw new InvalidNoticeFormatException(sourceName, "missing required header(s) " + missing);
        }

        String declaredType = headers.get("TYPE");
        if (NoticeCategory.fromLabel(declaredType).orElse(null) != getCategory()) {
            throw new InvalidNoticeFormatException(sourceName,
                    "TYPE '" + declaredType + "' does not match " + getCategory());
        }

        StringBuilder bodyBuilder = new StringBuilder();
        for (int i = terminatorLine + 1; i < lines.length; i++) {
            bodyBuilder.append(lines[i]).append('\n');
        }
        body = bodyBuilder.toString().strip();
        if (body.isEmpty()) {
            throw new InvalidNoticeFormatException(sourceName, "notice body is empty");
        }
        validated = true;
    }

    @Override
    public final void parseMetadata() throws InvalidNoticeFormatException {
        if (!validated) {
            throw new IllegalStateException("validateFormat() must succeed before parseMetadata() on " + sourceName);
        }
        title = headers.get("TITLE");
        issuer = headers.getOrDefault("ISSUER", "Unspecified");
        issueDate = parseDate("DATE");
        deadline = parseDate("DEADLINE");
        if (deadline.isBefore(issueDate)) {
            throw new InvalidNoticeFormatException(sourceName,
                    "DEADLINE " + deadline + " precedes issue DATE " + issueDate);
        }
        priorityKeys = parseKeywordList(headers.get("PRIORITY_KEYS"));
        if (priorityKeys.isEmpty()) {
            throw new InvalidNoticeFormatException(sourceName, "PRIORITY_KEYS contains no usable keywords");
        }

        parseTypeSpecificMetadata(Collections.unmodifiableMap(headers));

        id = getCategory().getIdPrefix() + "-" + contentFingerprint(rawContent);
        priorityScore = clampScore(calculatePriorityScore());
        processed = true;
    }

    @Override
    public final boolean isProcessed() {
        return processed;
    }

    /**
     * Rejects notices whose action window has already closed.
     *
     * @throws ExpiredDeadlineException if the deadline lies before the reference date
     */
    public final void checkDeadline() throws ExpiredDeadlineException {
        requireProcessed();
        if (deadline.isBefore(referenceDate)) {
            throw new ExpiredDeadlineException(sourceName, deadline, referenceDate);
        }
    }

    // ------------------------------------------------------------------
    // Helpers shared by subclasses
    // ------------------------------------------------------------------

    /**
     * Shared urgency curve: the closer the deadline, the more points. The
     * curve is stepped rather than continuous so that scores stay explainable
     * to students and administrators.
     */
    protected final int urgencyPoints() {
        long days = getDaysUntilDeadline();
        if (days <= 1) {
            return 40;
        } else if (days <= 3) {
            return 32;
        } else if (days <= 7) {
            return 24;
        } else if (days <= 14) {
            return 14;
        } else if (days <= 30) {
            return 6;
        }
        return 2;
    }

    /** Sums the weights of every priority keyword this notice carries. */
    protected final int keywordPoints(Map<String, Integer> weights) {
        int total = 0;
        for (String key : priorityKeys) {
            total += weights.getOrDefault(key, 0);
        }
        return total;
    }

    protected final LocalDate parseDate(String header) throws InvalidNoticeFormatException {
        String value = headers.get(header);
        if (value == null) {
            throw new InvalidNoticeFormatException(sourceName, "missing date header " + header);
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidNoticeFormatException(sourceName,
                    header + " value '" + value + "' is not an ISO date (yyyy-MM-dd)", e);
        }
    }

    protected final double parseDecimal(String header, String value) throws InvalidNoticeFormatException {
        try {
            double parsed = Double.parseDouble(value.trim());
            if (Double.isNaN(parsed) || Double.isInfinite(parsed) || parsed < 0) {
                throw new NumberFormatException("out of range");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new InvalidNoticeFormatException(sourceName,
                    header + " value '" + value + "' is not a non-negative number", e);
        }
    }

    protected final String abbreviate(String text) {
        return text.length() <= 60 ? text : text.substring(0, 57) + "...";
    }

    private void requireProcessed() {
        if (!processed) {
            throw new IllegalStateException("Notice " + sourceName + " has not been processed");
        }
    }

    private static int clampScore(int score) {
        return Math.max(0, Math.min(MAX_SCORE, score));
    }

    /**
     * Accepts plain "KEY: value" lines and the common Markdown decorations
     * "- **KEY:** value" / "**KEY**: value" so that .md circulars parse too.
     */
    private static String normalizeHeaderLine(String line) {
        String trimmed = line.trim();
        if (trimmed.equals(HEADER_TERMINATOR)) {
            return trimmed;
        }
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            trimmed = trimmed.substring(2).trim();
        }
        return trimmed.replace("**", "");
    }

    private static Set<String> parseKeywordList(String raw) {
        Set<String> keys = new TreeSet<>();
        for (String token : raw.split("[,;]")) {
            String key = token.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    private static String contentFingerprint(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.strip().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public String getId() {
        requireProcessed();
        return id;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getTitle() {
        return title;
    }

    public String getIssuer() {
        return issuer;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public LocalDate getDeadline() {
        return deadline;
    }

    public LocalDate getReferenceDate() {
        return referenceDate;
    }

    public Set<String> getPriorityKeys() {
        return priorityKeys;
    }

    public String getBody() {
        return body;
    }

    /** Header values as read from the file, with upper-case keys. */
    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(headers);
    }

    /** Score cached at parse time; stable for the lifetime of the notice. */
    public int getPriorityScore() {
        requireProcessed();
        return priorityScore;
    }

    public long getDaysUntilDeadline() {
        return ChronoUnit.DAYS.between(referenceDate, deadline);
    }

    // ------------------------------------------------------------------
    // Ordering and identity
    // ------------------------------------------------------------------

    /**
     * Natural ordering: highest priority score first, then earliest deadline,
     * then title and finally id, which makes the order total and consistent
     * with {@link #equals(Object)}.
     */
    @Override
    public int compareTo(Notice other) {
        int byScore = Integer.compare(other.getPriorityScore(), getPriorityScore());
        if (byScore != 0) {
            return byScore;
        }
        int byDeadline = getDeadline().compareTo(other.getDeadline());
        if (byDeadline != 0) {
            return byDeadline;
        }
        int byTitle = getTitle().compareToIgnoreCase(other.getTitle());
        if (byTitle != 0) {
            return byTitle;
        }
        return getId().compareTo(other.getId());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Notice)) {
            return false;
        }
        Notice other = (Notice) o;
        return processed && other.processed && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return processed ? id.hashCode() : System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return processed ? formatSummary() : getClass().getSimpleName() + "[unprocessed " + sourceName + "]";
    }
}
