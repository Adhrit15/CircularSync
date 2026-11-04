package com.circularsync.service;

import com.circularsync.model.NoticeCategory;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a free-form circular (typically extracted from a PDF or Word file)
 * into the structured header format understood by
 * {@link com.circularsync.model.Notice}.
 *
 * <p>Real circulars are letters, not forms: "Sub: Registration for
 * re-examination ... on or before 15/11/2026 ... Controller of Examinations".
 * The structurer applies transparent heuristics:</p>
 * <ul>
 *   <li><b>category</b> from weighted vocabulary (examination, placement, club terms),
 *       falling back to GENERAL;</li>
 *   <li><b>title</b> from a "Subject:" line, otherwise the first substantive line;</li>
 *   <li><b>dates</b> in ISO, day-first numeric (15/11/2026, 15.11.26) and written forms
 *       (15th November 2026, Nov 15, 2026); the issue date from a "Date:" line, the
 *       deadline from the date nearest a phrase such as "last date", "on or before",
 *       "register by";</li>
 *   <li><b>keywords</b> from the same vocabulary the scoring tables use;</li>
 *   <li><b>type-specific fields</b> (course code, venue, company, package, club name, seats)
 *       from labelled lines and common patterns.</li>
 * </ul>
 * <p>Every value that had to be guessed or defaulted is listed in an
 * {@code INFERRED_FIELDS} header, and the original format in
 * {@code EXTRACTED_FROM}, so the portal can show readers what was automatic.
 * The original text becomes the notice body unchanged.</p>
 *
 * Stateless and thread-safe.
 */
public final class CircularStructurer {

    private static final String MONTHS =
            "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?";

    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b");
    private static final Pattern NUMERIC_DATE = Pattern.compile("\\b(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4}|\\d{2})\\b");
    private static final Pattern DAY_MONTH_YEAR = Pattern.compile(
            "\\b(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+of)?[\\s,.-]+(" + MONTHS + ")\\.?[\\s,.-]+(\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTH_DAY_YEAR = Pattern.compile(
            "\\b(" + MONTHS + ")\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DEADLINE_CUE = Pattern.compile(
            "last\\s+date|deadline|on\\s+or\\s+before|not\\s+later\\s+than|latest\\s+by|before|"
                    + "(?:register|apply|submit|pay|collect|report)(?:ed|ing)?\\s+(?:\\w+\\s+){0,3}?by|due|closes?|until|till",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_LINE = Pattern.compile("^\\s*(?:date|dated|dt)\\b\\s*[:.-]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBJECT_LINE = Pattern.compile("^\\s*(?:sub(?:ject)?|re)\\s*[:.-]\\s*(.{4,})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENCE_LINE = Pattern.compile(
            "^\\s*(?:ref|no|circular\\s+no|notice\\s+no|letter\\s+no|to|from|cc|copy\\s+to)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSTITUTION_LINE = Pattern.compile(
            "college|university|institute|school|academy|campus|affiliated|accredited|autonomous|www\\.|@|phone|tel\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING_WORD = Pattern.compile(
            "^(?:notice|circular|office\\s+order|announcement|memorandum|urgent|important)[\\s:.!-]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISSUER_TITLE = Pattern.compile(
            "registrar|principal|dean|controller|co-?ordinator|head\\s+of|hod\\b|officer|director|secretary|convener|convenor|"
                    + "president|in-?charge|chairman|warden|manager|faculty\\s+advisor|placement\\s+cell|examination\\s+cell",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COURSE_CODE = Pattern.compile("\\b([A-Z]{2,4})\\s?-?(\\d{3,4}[A-Z]?)\\b");
    private static final Pattern VENUE_LABEL = Pattern.compile("(?:venue|location|place)\\s*[:-]\\s*(.{3,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern VENUE_GUESS = Pattern.compile(
            "\\b((?:[A-Z][\\w.]*\\s){0,4}(?:Hall|Auditorium|Block|Lab|Laboratory|Room|Seminar Hall|Library|Ground|Amphitheatre)(?:\\s[\\w-]{1,10})?)\\b");
    private static final Pattern COMPANY_LABEL = Pattern.compile("(?:company(?:\\s+name)?|organi[sz]ation|employer|recruiter)\\s*[:-]\\s*(.{2,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPANY_MS = Pattern.compile("\\bM/s\\.?\\s+([A-Z][\\w&.,' -]{2,60}?)(?=[,.;\\n(]|\\s+(?:is|will|has|for|are)\\b)");
    private static final Pattern COMPANY_SUFFIX = Pattern.compile(
            "\\b((?:[A-Z][\\w&.-]*\\s){1,5}(?:Pvt\\.?\\s*Ltd\\.?|Private\\s+Limited|Limited|Ltd\\.?|Technologies|Solutions|Systems|Software|Labs|Inc\\.?|LLP|Corporation|Consultancy\\s+Services))");
    private static final Pattern ROLE_LABEL = Pattern.compile("(?:role|position|designation|job\\s+profile|profile|post)\\s*[:-]\\s*(.{2,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern ELIGIBILITY_LABEL = Pattern.compile("eligib(?:ility|le)[^:\\n]{0,40}[:-]\\s*(.{3,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern PACKAGE = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,2})?)\\s*(?:lpa|lakhs?(?:\\s+per\\s+annum)?|l\\.p\\.a)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CGPA = Pattern.compile("(?:cgpa|cpi|gpa)[^\\d\\n]{0,20}(\\d{1,2}(?:\\.\\d{1,2})?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLUB_NAME = Pattern.compile(
            "\\b((?:[A-Z][\\w&'-]*\\s){1,5}(?:Club|Society|Chapter|Committee|Cell|Forum|Association|Council))\\b");
    private static final Pattern SEATS = Pattern.compile("(\\d{1,4})\\s+(?:seats|participants|slots)", Pattern.CASE_INSENSITIVE);

    private static final Map<NoticeCategory, Map<String, Integer>> CATEGORY_VOCABULARY = new EnumMap<>(NoticeCategory.class);

    static {
        CATEGORY_VOCABULARY.put(NoticeCategory.EXAM, vocabulary(
                "examination", 3, "exam", 3, "hall ticket", 4, "admit card", 4, "semester", 2, "mid-term", 3,
                "end-term", 3, "re-exam", 4, "reexam", 4, "supplementary", 3, "revaluation", 4, "timetable", 2,
                "time table", 2, "invigilat", 3, "question paper", 3, "answer script", 3, "backlog", 3, "internal assessment", 3));
        CATEGORY_VOCABULARY.put(NoticeCategory.PLACEMENT, vocabulary(
                "placement", 4, "recruitment", 4, "campus drive", 4, "hiring", 3, "internship", 3, "ctc", 3, "lpa", 4,
                "package", 2, "job", 2, "interview", 2, "aptitude", 2, "offer letter", 3, "training and placement", 4,
                "pool campus", 4, "pre-placement", 4, "off-campus", 3, "resume", 2));
        CATEGORY_VOCABULARY.put(NoticeCategory.CLUB, vocabulary(
                "club", 4, "society", 3, "workshop", 3, "hackathon", 4, "fest", 3, "competition", 3, "event", 2,
                "webinar", 2, "cultural", 3, "volunteer", 2, "quiz", 2, "chapter", 2, "symposium", 3, "talent", 2));
    }

    /** Priority keyword to the phrases that signal it. */
    private static final Map<String, String[]> KEYWORD_SIGNALS = new LinkedHashMap<>();

    static {
        KEYWORD_SIGNALS.put("mandatory", new String[]{"mandatory", "compulsory", "must ", "strictly", "without fail"});
        KEYWORD_SIGNALS.put("hall-ticket", new String[]{"hall ticket", "hall-ticket", "admit card"});
        KEYWORD_SIGNALS.put("end-semester", new String[]{"end semester", "end-semester", "end sem", "end-term", "end term"});
        KEYWORD_SIGNALS.put("mid-semester", new String[]{"mid semester", "mid-semester", "mid sem", "mid-term", "mid term"});
        KEYWORD_SIGNALS.put("re-exam", new String[]{"re-exam", "reexam", "re-examination", "supplementary"});
        KEYWORD_SIGNALS.put("backlog", new String[]{"backlog", "arrear"});
        KEYWORD_SIGNALS.put("revaluation", new String[]{"revaluation", "re-evaluation", "photocopy of answer"});
        KEYWORD_SIGNALS.put("practical", new String[]{"practical", "viva"});
        KEYWORD_SIGNALS.put("registration", new String[]{"register", "registration", "enrol", "sign up", "apply"});
        KEYWORD_SIGNALS.put("final-year", new String[]{"final year", "final-year", "passing out", "2027 batch", "2026 batch"});
        KEYWORD_SIGNALS.put("dream", new String[]{"dream company", "dream offer", "super dream"});
        KEYWORD_SIGNALS.put("internship", new String[]{"internship", "intern "});
        KEYWORD_SIGNALS.put("ppo", new String[]{"ppo", "pre-placement offer"});
        KEYWORD_SIGNALS.put("pre-placement-talk", new String[]{"pre-placement talk", "pre placement talk", "ppt "});
        KEYWORD_SIGNALS.put("off-campus", new String[]{"off-campus", "off campus"});
        KEYWORD_SIGNALS.put("hackathon", new String[]{"hackathon"});
        KEYWORD_SIGNALS.put("competition", new String[]{"competition", "contest"});
        KEYWORD_SIGNALS.put("workshop", new String[]{"workshop", "hands-on"});
        KEYWORD_SIGNALS.put("limited-seats", new String[]{"limited seats", "seats are limited", "first come first"});
        KEYWORD_SIGNALS.put("certificate", new String[]{"certificate"});
        KEYWORD_SIGNALS.put("free", new String[]{"free of cost", "no registration fee", "free entry", "free for"});
        KEYWORD_SIGNALS.put("volunteers", new String[]{"volunteer"});
        KEYWORD_SIGNALS.put("fee", new String[]{"fee", "fees", "payment", "dues"});
        KEYWORD_SIGNALS.put("scholarship", new String[]{"scholarship", "stipend", "freeship"});
        KEYWORD_SIGNALS.put("document-submission", new String[]{"submit the", "submission of", "documents", "bonafide"});
        KEYWORD_SIGNALS.put("attendance", new String[]{"attendance", "detained", "condonation"});
        KEYWORD_SIGNALS.put("hostel", new String[]{"hostel", "mess"});
        KEYWORD_SIGNALS.put("holiday", new String[]{"holiday", "vacation", "closed on"});
    }

    /** A date found in the text, with the text just before it for cue matching. */
    private static final class DateMention {
        final LocalDate date;
        final int position;
        final String before;

        DateMention(LocalDate date, int position, String before) {
            this.date = date;
            this.position = position;
            this.before = before;
        }
    }

    /**
     * @return true if the text is written in (or attempts) the CircularSync header format: its first
     *         substantive line is an upper-case {@code KEY: value} header and either that header is
     *         {@code TYPE} or a {@code ---} terminator follows. Such files are validated strictly instead
     *         of being inferred, so a malformed structured notice is rejected with a precise reason
     *         rather than silently guessed. Letters that merely open with "DATE: ..." are not matched.
     */
    public static boolean isStructuredAttempt(String text) {
        boolean seenFirst = false;
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.equals("---")) {
                return seenFirst;
            }
            if (!seenFirst) {
                String cleaned = line.replaceFirst("^[-*]\\s+", "").replace("**", "");
                if (!cleaned.matches("^[A-Z][A-Z0-9_]*\\s*:.*")) {
                    return false;
                }
                if (cleaned.matches("^TYPE\\s*:.*")) {
                    return true;
                }
                seenFirst = true;
            }
        }
        return false;
    }

    /**
     * Builds header-format text from a free-form circular.
     *
     * @param sourceName  file name, used as a last-resort title
     * @param text        extracted plain text
     * @param formatLabel the original format, e.g. "PDF", recorded in EXTRACTED_FROM
     * @param today       reference date, used when the circular states no issue date
     */
    public String structure(String sourceName, String text, String formatLabel, LocalDate today) {
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.replace('\t', ' ').replaceAll("\\s{2,}", " ").trim();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        String flat = String.join("\n", lines);
        String lower = flat.toLowerCase(Locale.ROOT);
        Set<String> inferred = new LinkedHashSet<>();
        Map<String, String> headers = new LinkedHashMap<>();

        NoticeCategory category = detectCategory(lower);
        inferred.add("type");

        String title = findTitle(lines, sourceName, inferred);

        List<DateMention> dates = findDates(flat);
        LocalDate issueDate = findIssueDate(lines, dates, today, inferred);
        LocalDate deadline = findDeadline(dates, issueDate, inferred);

        headers.put("TYPE", category.name());
        headers.put("TITLE", title);
        headers.put("DATE", issueDate.toString());
        headers.put("DEADLINE", deadline.toString());
        String issuer = findIssuer(lines);
        if (issuer != null) {
            headers.put("ISSUER", issuer);
        } else {
            inferred.add("issuer");
        }
        headers.put("PRIORITY_KEYS", String.join(", ", findKeywords(lower, category)));

        switch (category) {
            case EXAM:
                structureExam(flat, lower, dates, issueDate, deadline, headers, inferred);
                break;
            case PLACEMENT:
                structurePlacement(flat, lines, dates, issueDate, headers, inferred);
                break;
            case CLUB:
                structureClub(flat, lines, dates, issueDate, deadline, issuer, headers, inferred);
                break;
            default:
                break;
        }

        headers.put("EXTRACTED_FROM", formatLabel);
        headers.put("INFERRED_FIELDS", String.join(", ", inferred));

        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            out.append(header.getKey()).append(": ").append(singleLine(header.getValue())).append('\n');
        }
        out.append("---\n").append(text.strip()).append('\n');
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Common fields
    // ------------------------------------------------------------------

    static NoticeCategory detectCategory(String lower) {
        NoticeCategory best = NoticeCategory.GENERAL;
        int bestScore = 0;
        for (Map.Entry<NoticeCategory, Map<String, Integer>> entry : CATEGORY_VOCABULARY.entrySet()) {
            int score = 0;
            for (Map.Entry<String, Integer> term : entry.getValue().entrySet()) {
                score += Math.min(3, occurrences(lower, term.getKey())) * term.getValue();
            }
            if (score > bestScore) {
                bestScore = score;
                best = entry.getKey();
            }
        }
        return bestScore >= 6 ? best : NoticeCategory.GENERAL;
    }

    private static String findTitle(List<String> lines, String sourceName, Set<String> inferred) {
        for (String line : lines) {
            Matcher subject = SUBJECT_LINE.matcher(line);
            if (subject.matches()) {
                return truncate(subject.group(1).replaceAll("[.\\s]+$", ""), 140);
            }
        }
        inferred.add("title");
        int limit = Math.min(lines.size(), 15);
        for (int i = 0; i < limit; i++) {
            String line = lines.get(i);
            if (line.split("\\s+").length < 3 || HEADING_WORD.matcher(line).matches()
                    || INSTITUTION_LINE.matcher(line).find() || REFERENCE_LINE.matcher(line).matches()
                    || DATE_LINE.matcher(line).find() || line.matches("^[\\d\\W]+$")) {
                continue;
            }
            return truncate(line.replaceAll("[.\\s]+$", ""), 140);
        }
        String base = sourceName.replaceFirst("\\.[^.]+$", "").replaceAll("[_-]+", " ").trim();
        return base.isEmpty() ? "Untitled circular" : Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }

    static List<DateMention> findDates(String text) {
        List<DateMention> found = new ArrayList<>();
        Matcher m = ISO_DATE.matcher(text);
        while (m.find()) {
            add(found, text, m.start(), safeDate(m.group(1), m.group(2), m.group(3)));
        }
        m = NUMERIC_DATE.matcher(text);
        while (m.find()) {
            String year = m.group(3).length() == 2 ? "20" + m.group(3) : m.group(3);
            add(found, text, m.start(), safeDate(year, m.group(2), m.group(1))); // day-first, as used in India
        }
        m = DAY_MONTH_YEAR.matcher(text);
        while (m.find()) {
            add(found, text, m.start(), safeDate(m.group(3), monthNumber(m.group(2)), m.group(1)));
        }
        m = MONTH_DAY_YEAR.matcher(text);
        while (m.find()) {
            add(found, text, m.start(), safeDate(m.group(3), monthNumber(m.group(1)), m.group(2)));
        }
        found.sort((a, b) -> Integer.compare(a.position, b.position));
        return found;
    }

    private static void add(List<DateMention> found, String text, int position, LocalDate date) {
        if (date == null || date.getYear() < 2000 || date.getYear() > 2100) {
            return;
        }
        for (DateMention existing : found) {
            if (Math.abs(existing.position - position) < 4) {
                return; // the same mention matched by two patterns
            }
        }
        found.add(new DateMention(date, position, text.substring(Math.max(0, position - 70), position)));
    }

    private static LocalDate findIssueDate(List<String> lines, List<DateMention> dates, LocalDate today, Set<String> inferred) {
        for (String line : lines) {
            if (DATE_LINE.matcher(line).find()) {
                List<DateMention> inLine = findDates(line);
                if (!inLine.isEmpty()) {
                    return inLine.get(0).date;
                }
            }
        }
        inferred.add("issue date");
        // First date near the top of the letter, if it is not obviously an action date.
        for (DateMention mention : dates) {
            if (mention.position > 600) {
                break;
            }
            if (!DEADLINE_CUE.matcher(mention.before).find()) {
                return mention.date.isAfter(today) ? today : mention.date;
            }
        }
        return today;
    }

    private static LocalDate findDeadline(List<DateMention> dates, LocalDate issueDate, Set<String> inferred) {
        LocalDate cued = null;
        for (DateMention mention : dates) {
            String window = mention.before.length() > 45 ? mention.before.substring(mention.before.length() - 45) : mention.before;
            if (DEADLINE_CUE.matcher(window).find() && !mention.date.isBefore(issueDate)) {
                if (cued == null || mention.date.isBefore(cued)) {
                    cued = mention.date;
                }
            }
        }
        if (cued != null) {
            return cued;
        }
        inferred.add("deadline");
        LocalDate earliestFuture = null;
        for (DateMention mention : dates) {
            if (mention.date.isAfter(issueDate) && (earliestFuture == null || mention.date.isBefore(earliestFuture))) {
                earliestFuture = mention.date;
            }
        }
        return earliestFuture != null ? earliestFuture : issueDate.plusDays(14);
    }

    private static String findIssuer(List<String> lines) {
        int start = Math.max(0, lines.size() - 8);
        for (int i = lines.size() - 1; i >= start; i--) {
            String line = lines.get(i);
            if (line.length() <= 80 && ISSUER_TITLE.matcher(line).find() && !line.toLowerCase(Locale.ROOT).startsWith("copy")) {
                return line.replaceAll("^(?:sd/-?|sd\\.?|\\(|signed)\\s*", "").replaceAll("[)]$", "").trim();
            }
        }
        return null;
    }

    private static Set<String> findKeywords(String lower, NoticeCategory category) {
        Set<String> keys = new LinkedHashSet<>();
        for (Map.Entry<String, String[]> signal : KEYWORD_SIGNALS.entrySet()) {
            for (String phrase : signal.getValue()) {
                if (lower.contains(phrase)) {
                    keys.add(signal.getKey());
                    break;
                }
            }
        }
        keys.add(category.name().toLowerCase(Locale.ROOT));
        return keys;
    }

    // ------------------------------------------------------------------
    // Category-specific fields
    // ------------------------------------------------------------------

    private static void structureExam(String flat, String lower, List<DateMention> dates, LocalDate issueDate,
                                      LocalDate deadline, Map<String, String> headers, Set<String> inferred) {
        Matcher code = COURSE_CODE.matcher(flat);
        String courseCode = null;
        while (code.find()) {
            String prefix = code.group(1);
            if (!prefix.equals("NO") && !prefix.equals("REF") && !prefix.equals("SEM")) {
                courseCode = prefix + code.group(2);
                break;
            }
        }
        if (courseCode == null) {
            courseCode = "ALL";
            inferred.add("course code");
        }
        headers.put("COURSE_CODE", courseCode);

        String examType = "Examination";
        if (lower.contains("re-exam") || lower.contains("reexam") || lower.contains("re-examination") || lower.contains("supplementary")) {
            examType = "Re-Examination";
        } else if (lower.contains("end semester") || lower.contains("end-semester") || lower.contains("end term")) {
            examType = "End Semester";
        } else if (lower.contains("mid semester") || lower.contains("mid-semester") || lower.contains("mid term")) {
            examType = "Mid Semester";
        } else if (lower.contains("practical") || lower.contains("viva")) {
            examType = "Practical";
        }
        headers.put("EXAM_TYPE", examType);

        LocalDate examDate = dateNear(dates, "exam|examination|test|held|commence|conducted|scheduled|viva", issueDate, deadline);
        if (examDate == null) {
            examDate = deadline;
            inferred.add("exam date");
        }
        headers.put("EXAM_DATE", examDate.toString());
        headers.put("VENUE", findVenue(flat, "As per seating arrangement", inferred));
    }

    private static void structurePlacement(String flat, List<String> lines, List<DateMention> dates, LocalDate issueDate,
                                           Map<String, String> headers, Set<String> inferred) {
        String company = firstGroup(COMPANY_LABEL, flat);
        if (company == null) {
            company = firstGroup(COMPANY_MS, flat);
        }
        if (company == null) {
            company = firstGroup(COMPANY_SUFFIX, flat);
        }
        if (company == null) {
            company = "See notice";
            inferred.add("company");
        }
        headers.put("COMPANY", truncate(company, 80));

        String role = firstGroup(ROLE_LABEL, flat);
        if (role == null) {
            role = "See notice";
            inferred.add("role");
        }
        headers.put("ROLE", truncate(role, 80));

        String eligibility = firstGroup(ELIGIBILITY_LABEL, flat);
        if (eligibility == null) {
            eligibility = "As per notice";
            inferred.add("eligibility");
        }
        headers.put("ELIGIBILITY", truncate(eligibility, 160));

        String pkg = firstGroup(PACKAGE, flat);
        if (pkg != null) {
            headers.put("PACKAGE_LPA", pkg);
        }
        String cgpa = firstGroup(CGPA, flat);
        if (cgpa != null && Double.parseDouble(cgpa) <= 10.0) {
            headers.put("MIN_CGPA", cgpa);
        }
        LocalDate drive = dateNear(dates, "drive|interview|visit|test|assessment|selection", issueDate, null);
        if (drive != null) {
            headers.put("DRIVE_DATE", drive.toString());
        }
    }

    private static void structureClub(String flat, List<String> lines, List<DateMention> dates, LocalDate issueDate,
                                      LocalDate deadline, String issuer, Map<String, String> headers, Set<String> inferred) {
        String club = firstGroup(CLUB_NAME, flat);
        if (club == null) {
            club = issuer != null ? issuer : "Student Activities";
            inferred.add("club name");
        }
        headers.put("CLUB_NAME", truncate(club, 80));
        LocalDate eventDate = dateNear(dates, "event|held|on|workshop|hackathon|fest|competition|session|organi[sz]", issueDate, deadline);
        if (eventDate == null) {
            eventDate = deadline;
            inferred.add("event date");
        }
        headers.put("EVENT_DATE", eventDate.toString());
        headers.put("EVENT_VENUE", findVenue(flat, "To be announced", inferred));
        String seats = firstGroup(SEATS, flat);
        if (seats != null) {
            headers.put("SEATS", seats);
        }
    }

    /**
     * The first date on or after {@code notBefore} whose preceding text matches the
     * cue, preferring one that differs from {@code avoid} (usually the deadline).
     */
    private static LocalDate dateNear(List<DateMention> dates, String cueRegex, LocalDate notBefore, LocalDate avoid) {
        Pattern cue = Pattern.compile("\\b(?:" + cueRegex + ")\\b", Pattern.CASE_INSENSITIVE);
        LocalDate fallback = null;
        for (DateMention mention : dates) {
            String window = mention.before.length() > 50 ? mention.before.substring(mention.before.length() - 50) : mention.before;
            if (mention.date.isBefore(notBefore) || !cue.matcher(window).find()) {
                continue;
            }
            if (avoid == null || !mention.date.equals(avoid)) {
                return mention.date;
            }
            fallback = mention.date;
        }
        return fallback;
    }

    private static String findVenue(String flat, String defaultValue, Set<String> inferred) {
        String venue = firstGroup(VENUE_LABEL, flat);
        if (venue == null) {
            venue = firstGroup(VENUE_GUESS, flat);
        }
        if (venue == null) {
            inferred.add("venue");
            return defaultValue;
        }
        return truncate(venue.replaceAll("[.,;\\s]+$", ""), 80);
    }

    // ------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------

    private static Map<String, Integer> vocabulary(Object... pairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }

    private static int occurrences(String text, String term) {
        int count = 0;
        int index = text.indexOf(term);
        while (index >= 0) {
            count++;
            index = text.indexOf(term, index + term.length());
        }
        return count;
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return null;
        }
        String value = m.group(1).split("\\n")[0].trim();
        return value.isEmpty() ? null : value;
    }

    private static LocalDate safeDate(String year, String month, String day) {
        try {
            return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
        } catch (NumberFormatException | DateTimeException e) {
            return null;
        }
    }

    private static String monthNumber(String name) {
        String prefix = name.toLowerCase(Locale.ROOT).substring(0, 3);
        for (Month month : Month.values()) {
            if (month.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return String.valueOf(month.getValue());
            }
        }
        return "0";
    }

    private static String singleLine(String value) {
        return value.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static String truncate(String value, int max) {
        String single = singleLine(value);
        return single.length() <= max ? single : single.substring(0, max - 3).trim() + "...";
    }
}
