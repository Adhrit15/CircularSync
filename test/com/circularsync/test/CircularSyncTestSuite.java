package com.circularsync.test;

import com.circularsync.daemon.DirectoryWatcherDaemon;
import com.circularsync.exceptions.ExpiredDeadlineException;
import com.circularsync.exceptions.InvalidNoticeFormatException;
import com.circularsync.model.ClubNotice;
import com.circularsync.model.ExamNotice;
import com.circularsync.model.GeneralNotice;
import com.circularsync.model.Notice;
import com.circularsync.model.NoticeCategory;
import com.circularsync.model.PlacementNotice;
import com.circularsync.service.AuditLogger;
import com.circularsync.service.NoticeParserService;
import com.circularsync.service.NoticeRegistry;
import com.circularsync.service.NoticeRegistry.RegistrationResult;
import com.circularsync.web.FileActivityTracker;
import com.circularsync.web.PortalSettings;
import com.circularsync.web.WebPortalServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Dependency-free test suite for CircularSync. Run with
 * {@code java -cp "out:lib/*" com.circularsync.test.CircularSyncTestSuite}; the exit
 * code is the number of failed tests.
 */
public final class CircularSyncTestSuite {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    private final NoticeParserService parser = new NoticeParserService(() -> TODAY);
    private int passed;
    private final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        CircularSyncTestSuite suite = new CircularSyncTestSuite();
        suite.runAll();
        System.exit(suite.failures.size());
    }

    private void runAll() {
        test("parses a valid exam notice", this::parsesExamNotice);
        test("parses a valid placement notice", this::parsesPlacementNotice);
        test("parses a valid club notice", this::parsesClubNotice);
        test("parses Markdown-decorated headers", this::parsesMarkdownNotice);
        test("rejects a missing required header", this::rejectsMissingHeader);
        test("rejects an unparseable date", this::rejectsBadDate);
        test("rejects an unterminated header block", this::rejectsUnterminatedHeader);
        test("rejects an unknown TYPE", this::rejectsUnknownType);
        test("rejects an empty body", this::rejectsEmptyBody);
        test("rejects a deadline before the issue date", this::rejectsDeadlineBeforeIssue);
        test("rejects an expired deadline", this::rejectsExpiredDeadline);
        test("ranks urgent exams above distant club events", this::ranksByPriority);
        test("detects duplicate content and replaces edited files", this::duplicatesAndReplacement);
        test("search uses AND semantics across title, keys and body", this::searchSemantics);
        test("dueWithin orders by deadline", this::dueWithinOrdering);
        test("registry survives concurrent registration", this::concurrentRegistration);
        test("daemon processes existing files through the worker pool", this::daemonInitialScan);
        test("daemon reacts to WatchService create and delete events", this::daemonWatchEvents);
        test("infers an exam notice from a free-form circular", this::infersExamFromProse);
        test("infers a placement notice from a Word document", this::infersPlacementFromDocx);
        test("infers a club notice from a PDF", this::infersClubFromPdf);
        test("falls back to GENERAL for administrative circulars", this::fallsBackToGeneral);
        test("rejects a PDF without selectable text", this::rejectsTextlessPdf);
        test("rejects a corrupt Word document", this::rejectsCorruptDocx);
        test("web portal uploads, tracks and serves the board", this::webPortalEndToEnd);
        test("web portal requires the admin password for changes", this::webPortalAdminProtection);

        System.out.println();
        System.out.println(passed + " passed, " + failures.size() + " failed");
        for (String failure : failures) {
            System.out.println("  FAILED: " + failure);
        }
    }

    private interface TestBody {
        void run() throws Exception;
    }

    private void test(String name, TestBody body) {
        try {
            body.run();
            passed++;
            System.out.println("[PASS] " + name);
        } catch (Throwable t) {
            failures.add(name + " -> " + t);
            System.out.println("[FAIL] " + name + " -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    static String exam(String title, String deadline, String keys) {
        return "TYPE: EXAM\nTITLE: " + title + "\nDATE: 2026-11-01\nDEADLINE: " + deadline
                + "\nISSUER: Controller of Examinations\nPRIORITY_KEYS: " + keys
                + "\nCOURSE_CODE: cs301\nEXAM_DATE: 2026-11-24\nVENUE: Main Block Hall A\nEXAM_TYPE: End Semester"
                + "\n---\nCollect hall tickets from the department office.\n";
    }

    static String placement(String deadline) {
        return "TYPE: PLACEMENT\nTITLE: Campus Drive - Software Engineer\nDATE: 2026-11-02\nDEADLINE: " + deadline
                + "\nPRIORITY_KEYS: registration, final-year, dream\nCOMPANY: Northwind Systems\nROLE: Software Engineer"
                + "\nELIGIBILITY: B.Tech CSE/IT, CGPA 7.0 and above\nPACKAGE_LPA: 18.5\nMIN_CGPA: 7.0\nDRIVE_DATE: 2026-11-28"
                + "\n---\nRegister on the placement portal.\n";
    }

    static String club(String deadline) {
        return "TYPE: CLUB\nTITLE: Robotics Workshop\nDATE: 2026-11-03\nDEADLINE: " + deadline
                + "\nPRIORITY_KEYS: workshop, certificate\nCLUB_NAME: Robotics Club\nEVENT_DATE: 2026-12-12"
                + "\nEVENT_VENUE: Innovation Lab\nSEATS: 40\n---\nHands-on session on line-following robots.\n";
    }

    // ------------------------------------------------------------------
    // Parsing tests
    // ------------------------------------------------------------------

    private void parsesExamNotice() throws Exception {
        Notice notice = parser.parse("exam.txt", exam("End Semester Exams", "2026-11-20", "mandatory, hall-ticket"));
        check(notice instanceof ExamNotice, "expected ExamNotice");
        ExamNotice exam = (ExamNotice) notice;
        check(exam.getCourseCode().equals("CS301"), "course code upper-cased");
        check(exam.getExamDate().equals(LocalDate.of(2026, 11, 24)), "exam date");
        check(exam.getDaysUntilDeadline() == 16, "days until deadline");
        // base 30 + urgency(16 days)=6 + exam proximity(20 days)=0 + mandatory 10 + hall-ticket 8
        checkEquals(54, exam.getPriorityScore(), "exam score");
        check(exam.getId().startsWith("EXM-"), "id prefix");
    }

    private void parsesPlacementNotice() throws Exception {
        PlacementNotice notice = (PlacementNotice) parser.parse("p.txt", placement("2026-11-10"));
        checkEquals(18.5, notice.getPackageLpa(), "package");
        // base 25 + urgency(6 days)=24 + package band 10 + registration 6 + final-year 4 + dream 8
        checkEquals(77, notice.getPriorityScore(), "placement score");
        check(notice.getDriveDate().equals(LocalDate.of(2026, 11, 28)), "drive date");
    }

    private void parsesClubNotice() throws Exception {
        ClubNotice notice = (ClubNotice) parser.parse("c.txt", club("2026-12-01"));
        checkEquals(40, notice.getSeats(), "seats");
        // base 10 + urgency(27 days)=6*3/4=4 + workshop 4 + certificate 3 + scarcity 4
        checkEquals(25, notice.getPriorityScore(), "club score");
    }

    private void parsesMarkdownNotice() throws Exception {
        String md = "# Hackathon Announcement\n\n- **TYPE:** CLUB\n- **TITLE:** 24h Hackathon\n- **DATE:** 2026-11-04"
                + "\n- **DEADLINE:** 2026-11-15\n- **PRIORITY_KEYS:** hackathon, registration\n- **CLUB_NAME:** Coding Club"
                + "\n- **EVENT_DATE:** 2026-11-21\n- **EVENT_VENUE:** Central Library\n\n---\n\nTeams of up to four.\n";
        Notice notice = parser.parse("hack.md", md);
        check(notice.getCategory() == NoticeCategory.CLUB, "category from markdown");
        check(notice.getTitle().equals("24h Hackathon"), "title from markdown");
    }

    private void rejectsMissingHeader() {
        String content = exam("x", "2026-11-20", "mandatory").replace("VENUE: Main Block Hall A\n", "");
        InvalidNoticeFormatException e = expect(InvalidNoticeFormatException.class, () -> parser.parse("m.txt", content));
        check(e.getMessage().contains("VENUE"), "message names the missing header: " + e.getMessage());
    }

    private void rejectsBadDate() {
        String content = exam("x", "20-11-2026", "mandatory");
        InvalidNoticeFormatException e = expect(InvalidNoticeFormatException.class, () -> parser.parse("d.txt", content));
        check(e.getMessage().contains("DEADLINE"), "message names the bad header");
    }

    private void rejectsUnterminatedHeader() {
        String content = exam("x", "2026-11-20", "mandatory").replace("---\n", "");
        expect(InvalidNoticeFormatException.class, () -> parser.parse("u.txt", content));
    }

    private void rejectsUnknownType() {
        String content = exam("x", "2026-11-20", "mandatory").replace("TYPE: EXAM", "TYPE: SPORTS");
        InvalidNoticeFormatException e = expect(InvalidNoticeFormatException.class, () -> parser.parse("t.txt", content));
        check(e.getMessage().contains("SPORTS"), "message names the unknown type");
    }

    private void rejectsEmptyBody() {
        String content = exam("x", "2026-11-20", "mandatory").replace("Collect hall tickets from the department office.\n", "  \n");
        expect(InvalidNoticeFormatException.class, () -> parser.parse("b.txt", content));
    }

    private void rejectsDeadlineBeforeIssue() {
        String content = exam("x", "2026-10-20", "mandatory");
        expect(InvalidNoticeFormatException.class, () -> parser.parse("o.txt", content));
    }

    private void rejectsExpiredDeadline() {
        String content = exam("x", "2026-11-03", "mandatory");
        ExpiredDeadlineException e = expect(ExpiredDeadlineException.class, () -> parser.parse("e.txt", content));
        check(e.getDeadline().equals(LocalDate.of(2026, 11, 3)), "deadline carried by exception");
    }

    // ------------------------------------------------------------------
    // Free-form circulars (PDF, DOCX, plain prose)
    // ------------------------------------------------------------------

    private void infersExamFromProse() throws Exception {
        String letter = String.join("\n",
                "ABC INSTITUTE OF TECHNOLOGY",
                "Office of the Controller of Examinations",
                "Ref: COE/2026/114",
                "Date: 03/11/2026",
                "NOTICE",
                "Sub: Registration for Supplementary Examinations - MA201 Engineering Mathematics III",
                "All students with a backlog in MA201 are informed that registration for the supplementary",
                "examination is mandatory. Students must register at the examination section on or before",
                "12th November 2026. The examination will be held on 20 November 2026 in Seminar Hall 2.",
                "Controller of Examinations");
        ExamNotice exam = (ExamNotice) parser.parse("supplementary.txt", letter);
        check(exam.getTitle().startsWith("Registration for Supplementary Examinations"), "title from Sub: line, got " + exam.getTitle());
        checkEquals(LocalDate.of(2026, 11, 3), exam.getIssueDate(), "issue date from Date: line");
        checkEquals(LocalDate.of(2026, 11, 12), exam.getDeadline(), "deadline from 'on or before'");
        checkEquals(LocalDate.of(2026, 11, 20), exam.getExamDate(), "exam date from 'held on'");
        checkEquals("MA201", exam.getCourseCode(), "course code");
        checkEquals("Re-Examination", exam.getExamType(), "exam type");
        check(exam.getVenue().contains("Seminar Hall"), "venue, got " + exam.getVenue());
        checkEquals("Controller of Examinations", exam.getIssuer(), "issuer from signature");
        check(exam.getPriorityKeys().containsAll(List.of("mandatory", "re-exam", "backlog", "registration")),
                "keywords, got " + exam.getPriorityKeys());
        checkEquals("TEXT", exam.getHeaders().get("EXTRACTED_FROM"), "format recorded");
        check(exam.getBody().contains("Seminar Hall 2"), "original text kept as body");
    }

    private void infersPlacementFromDocx() throws Exception {
        Path dir = Files.createTempDirectory("circularsync-docx");
        try {
            Path docx = dir.resolve("drive.docx");
            writeDocx(docx,
                    "Training and Placement Cell",
                    "Date: 04-11-2026",
                    "Subject: Campus Recruitment Drive by Contoso Analytics Pvt Ltd",
                    "Company: Contoso Analytics Pvt Ltd",
                    "Role: Data Analyst",
                    "Eligibility: B.Tech all branches, CGPA 6.5 and above",
                    "Package: 8.5 LPA",
                    "Interested final year students must register on the portal latest by 09.11.2026. "
                            + "The drive will be conducted on 15/11/2026.",
                    "Placement Officer");
            PlacementNotice notice = (PlacementNotice) parser.parse(docx);
            checkEquals("Contoso Analytics Pvt Ltd", notice.getCompany(), "company");
            checkEquals("Data Analyst", notice.getRole(), "role");
            checkEquals(8.5, notice.getPackageLpa(), "package");
            checkEquals(6.5, notice.getMinCgpa(), "minimum CGPA");
            checkEquals(LocalDate.of(2026, 11, 9), notice.getDeadline(), "deadline from 'latest by'");
            checkEquals(LocalDate.of(2026, 11, 15), notice.getDriveDate(), "drive date");
            checkEquals("DOCX", notice.getHeaders().get("EXTRACTED_FROM"), "format recorded");
        } finally {
            deleteTree(dir);
        }
    }

    private void infersClubFromPdf() throws Exception {
        Path dir = Files.createTempDirectory("circularsync-pdf");
        try {
            Path pdf = dir.resolve("hackathon.pdf");
            writePdf(pdf,
                    "Coding Club",
                    "Date: 2026-11-04",
                    "Subject: Inter-college Hackathon 2026",
                    "The Coding Club is organising a 24-hour hackathon on 21 November 2026",
                    "at the Central Library. Registrations close on 15/11/2026.",
                    "Limited seats: 120 participants. Certificates for all.",
                    "Faculty Coordinator");
            ClubNotice notice = (ClubNotice) parser.parse(pdf);
            checkEquals("Inter-college Hackathon 2026", notice.getTitle(), "title");
            checkEquals("Coding Club", notice.getClubName(), "club name");
            checkEquals(LocalDate.of(2026, 11, 15), notice.getDeadline(), "deadline from 'close on'");
            checkEquals(LocalDate.of(2026, 11, 21), notice.getEventDate(), "event date");
            checkEquals(120, notice.getSeats(), "seats");
            check(notice.getPriorityKeys().contains("hackathon"), "hackathon keyword");
            checkEquals("PDF", notice.getHeaders().get("EXTRACTED_FROM"), "format recorded");
        } finally {
            deleteTree(dir);
        }
    }

    private void fallsBackToGeneral() throws Exception {
        String letter = String.join("\n",
                "Accounts Section",
                "Date: 01/11/2026",
                "Sub: Payment of tuition fees for the even term",
                "Students are informed that the last date for payment of fees without fine is 30/11/2026.",
                "Accounts Officer");
        Notice notice = parser.parse("fees.txt", letter);
        check(notice instanceof GeneralNotice, "general notice, got " + notice.getCategory());
        checkEquals(LocalDate.of(2026, 11, 30), notice.getDeadline(), "deadline from 'last date'");
        check(notice.getPriorityKeys().contains("fee"), "fee keyword");
    }

    private void rejectsTextlessPdf() throws Exception {
        Path dir = Files.createTempDirectory("circularsync-pdf");
        try {
            Path pdf = dir.resolve("scan.pdf");
            writePdf(pdf);
            InvalidNoticeFormatException e = expect(InvalidNoticeFormatException.class, () -> parser.parse(pdf));
            check(e.getMessage().contains("scanned"), "explains scanned PDFs: " + e.getMessage());
        } finally {
            deleteTree(dir);
        }
    }

    private void rejectsCorruptDocx() throws Exception {
        Path dir = Files.createTempDirectory("circularsync-docx");
        try {
            Path docx = dir.resolve("broken.docx");
            write(docx, "this is not a zip archive");
            expect(InvalidNoticeFormatException.class, () -> parser.parse(docx));
        } finally {
            deleteTree(dir);
        }
    }


    // ------------------------------------------------------------------
    // Registry tests
    // ------------------------------------------------------------------

    private void ranksByPriority() throws Exception {
        NoticeRegistry registry = new NoticeRegistry();
        registry.register(parser.parse("club.txt", club("2026-12-01")));
        registry.register(parser.parse("exam.txt", exam("Urgent exam", "2026-11-05", "mandatory, hall-ticket")));
        registry.register(parser.parse("placement.txt", placement("2026-11-10")));
        List<Notice> board = registry.ranked();
        checkEquals(3, board.size(), "board size");
        check(board.get(0).getCategory() == NoticeCategory.EXAM, "exam first, got " + board.get(0).getCategory());
        check(board.get(2).getCategory() == NoticeCategory.CLUB, "club last");
        checkEquals(1, registry.top(1).size(), "top(1)");
    }

    private void duplicatesAndReplacement() throws Exception {
        NoticeRegistry registry = new NoticeRegistry();
        String content = exam("Original", "2026-11-20", "mandatory");
        checkEquals(RegistrationResult.ADDED, registry.register(parser.parse("a.txt", content)), "first add");
        checkEquals(RegistrationResult.DUPLICATE, registry.register(parser.parse("a.txt", content)), "duplicate");
        String edited = exam("Revised", "2026-11-20", "mandatory");
        checkEquals(RegistrationResult.REPLACED, registry.register(parser.parse("a.txt", edited)), "replacement");
        checkEquals(1, registry.size(), "still one notice");
        check(registry.ranked().get(0).getTitle().equals("Revised"), "revised version kept");
        check(registry.search("original").isEmpty(), "old terms removed from index");
        check(registry.removeBySource("a.txt").isPresent(), "remove by source");
        checkEquals(0, registry.size(), "empty after removal");
    }

    private void searchSemantics() throws Exception {
        NoticeRegistry registry = new NoticeRegistry();
        registry.register(parser.parse("exam.txt", exam("End Semester Exams", "2026-11-20", "mandatory, hall-ticket")));
        registry.register(parser.parse("club.txt", club("2026-12-01")));
        checkEquals(1, registry.search("hall-ticket").size(), "keyword match");
        checkEquals(1, registry.search("robots").size(), "body match");
        checkEquals(1, registry.search("CS301").size(), "header value match, case-insensitive");
        checkEquals(0, registry.search("robots hall-ticket").size(), "AND semantics");
        checkEquals(1, registry.byCategory(NoticeCategory.CLUB).size(), "category view");
    }

    private void dueWithinOrdering() throws Exception {
        NoticeRegistry registry = new NoticeRegistry();
        registry.register(parser.parse("exam.txt", exam("Exam", "2026-11-09", "mandatory")));
        registry.register(parser.parse("p.txt", placement("2026-11-06")));
        registry.register(parser.parse("club.txt", club("2026-12-01")));
        List<Notice> due = registry.dueWithin(7);
        checkEquals(2, due.size(), "two deadlines within a week");
        check(due.get(0).getCategory() == NoticeCategory.PLACEMENT, "soonest deadline first");
    }

    private void concurrentRegistration() throws Exception {
        NoticeRegistry registry = new NoticeRegistry();
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int threadIndex = t;
            Callable<Integer> job = () -> {
                startGun.await();
                int added = 0;
                for (int i = 0; i < perThread; i++) {
                    String title = "Notice " + threadIndex + "-" + i;
                    Notice notice = parser.parse("t" + threadIndex + "_" + i + ".txt",
                            exam(title, String.format("2026-11-%02d", 5 + (i % 20)), "mandatory"));
                    if (registry.register(notice) == RegistrationResult.ADDED) {
                        added++;
                    }
                    registry.ranked(); // concurrent readers interleaved with writers
                    registry.search("notice");
                }
                return added;
            };
            futures.add(pool.submit(job));
        }
        startGun.countDown();
        int total = 0;
        for (Future<Integer> future : futures) {
            total += future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        checkEquals(threads * perThread, total, "every registration accepted");
        checkEquals(threads * perThread, registry.size(), "registry size");
        List<Notice> board = registry.ranked();
        checkEquals(threads * perThread, board.size(), "board size");
        for (int i = 1; i < board.size(); i++) {
            check(board.get(i - 1).compareTo(board.get(i)) < 0, "board strictly ordered at " + i);
        }
        checkEquals(threads * perThread, registry.search("notice").size(), "keyword index complete");
    }

    // ------------------------------------------------------------------
    // Daemon tests (real file system, real WatchService)
    // ------------------------------------------------------------------

    private void daemonInitialScan() throws Exception {
        Path dir = Files.createTempDirectory("circularsync-scan");
        Path logs = Files.createTempDirectory("circularsync-logs");
        try {
            write(dir.resolve("exam.txt"), exam("Exam", "2026-11-20", "mandatory"));
            write(dir.resolve("placement.txt"), placement("2026-11-10"));
            write(dir.resolve("club.md"), club("2026-12-01"));
            write(dir.resolve("broken.txt"), "TITLE: no type here\n---\nbody\n");
            write(dir.resolve("expired.txt"), exam("Old", "2026-11-02", "mandatory"));
            write(dir.resolve("ignored.doc"), "legacy Word files are not supported");

            NoticeRegistry registry = new NoticeRegistry();
            DirectoryWatcherDaemon.Statistics stats;
            try (AuditLogger audit = new AuditLogger(logs);
                 DirectoryWatcherDaemon daemon = new DirectoryWatcherDaemon(dir, parser, registry, audit, 3)) {
                daemon.startWorkers();
                checkEquals(5, daemon.enqueueExistingFiles(), "supported files queued");
                check(daemon.awaitIdle(Duration.ofSeconds(20)), "pipeline drained");
                stats = daemon.getStatistics();
            }
            checkEquals(3, registry.size(), "valid notices indexed");
            checkEquals(1L, stats.rejectedInvalid.get(), "one format rejection");
            checkEquals(1L, stats.rejectedExpired.get(), "one expiry rejection");
            String log = new String(Files.readAllBytes(logs.resolve(AuditLogger.AUDIT_FILE_NAME)), StandardCharsets.UTF_8);
            check(log.contains("INDEXED") && log.contains("REJECTED_FORMAT") && log.contains("REJECTED_EXPIRED"),
                    "audit log records every outcome");
        } finally {
            deleteTree(dir);
            deleteTree(logs);
        }
    }

    private void daemonWatchEvents() throws Exception {
        Path dir = Files.createTempDirectory("circularsync-watch");
        Path logs = Files.createTempDirectory("circularsync-logs");
        try {
            NoticeRegistry registry = new NoticeRegistry();
            try (AuditLogger audit = new AuditLogger(logs);
                 DirectoryWatcherDaemon daemon = new DirectoryWatcherDaemon(dir, parser, registry, audit, 2)) {
                daemon.start();
                write(dir.resolve("live_exam.txt"), exam("Live drop", "2026-11-20", "mandatory"));
                write(dir.resolve("live_club.txt"), club("2026-12-01"));
                waitUntil(() -> registry.size() == 2, Duration.ofSeconds(20), "two dropped files indexed");
                Files.delete(dir.resolve("live_club.txt"));
                waitUntil(() -> registry.size() == 1, Duration.ofSeconds(20), "deleted file withdrawn");
                write(dir.resolve("live_exam.txt"), exam("Live drop revised", "2026-11-20", "mandatory"));
                waitUntil(() -> !registry.search("revised").isEmpty(), Duration.ofSeconds(20), "modified file re-indexed");
                checkEquals(1, registry.size(), "replacement, not addition");
            }
        } finally {
            deleteTree(dir);
            deleteTree(logs);
        }
    }

    private void webPortalEndToEnd() throws Exception {
        Path dir = Files.createTempDirectory("circularsync-web");
        Path logs = Files.createTempDirectory("circularsync-logs");
        Path demo = Files.createTempDirectory("circularsync-demo");
        HttpClient http = HttpClient.newHttpClient();
        try {
            write(demo.resolve("demo_club.txt"), club("2026-12-01"));
            NoticeRegistry registry = new NoticeRegistry();
            FileActivityTracker tracker = new FileActivityTracker();
            try (AuditLogger audit = new AuditLogger(logs);
                 DirectoryWatcherDaemon daemon = new DirectoryWatcherDaemon(dir, parser, registry, audit, 2);
                 WebPortalServer portal = new WebPortalServer(
                         new PortalSettings("127.0.0.1", 0, Paths.get("web"), demo, null, false),
                         registry, daemon, tracker, audit, () -> TODAY)) {
                daemon.addProcessingListener(tracker);
                portal.start();
                daemon.start();
                String base = portal.getUrl();

                HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create(base)).build(),
                        HttpResponse.BodyHandlers.ofString());
                checkEquals(200, page.statusCode(), "portal page");
                check(page.body().contains("CircularSync"), "portal page content");

                checkEquals(202, post(http, base + "api/upload?name=web_exam.txt", exam("Web upload", "2026-11-20", "mandatory")), "upload accepted");
                checkEquals(202, post(http, base + "api/upload?name=web_broken.txt", "TYPE: EXAM\n---\nbody\n"), "broken upload accepted");
                checkEquals(400, post(http, base + "api/upload?name=..%2Fescape.txt", "x"), "path traversal refused");
                checkEquals(415, post(http, base + "api/upload?name=report.exe", "x"), "unsupported type refused");
                checkEquals(415, post(http, base + "api/upload?name=fake.pdf", "MZ not really a pdf"), "fake PDF refused");
                checkEquals(202, post(http, base + "api/demo?name=demo_club.txt", ""), "demo drop accepted");

                waitUntil(() -> registry.size() == 2, Duration.ofSeconds(20), "uploaded and demo notices indexed");
                String state = http.send(HttpRequest.newBuilder(URI.create(base + "api/state")).build(),
                        HttpResponse.BodyHandlers.ofString()).body();
                waitUntil(() -> get(http, base + "api/state").contains("REJECTED_FORMAT"),
                        Duration.ofSeconds(20), "broken upload tracked as rejected");
                check(state.contains("\"source\":\"UPLOAD\"") && state.contains("\"source\":\"DEMO\""), "sources tracked");
                check(get(http, base + "api/state?category=CLUB").contains("Robotics Workshop"), "category filter");
                check(!get(http, base + "api/state?q=robotics").contains("Web upload"), "search filter");

                HttpResponse<String> deleted = http.send(HttpRequest.newBuilder(URI.create(base + "api/files?name=web_exam.txt"))
                        .DELETE().build(), HttpResponse.BodyHandlers.ofString());
                checkEquals(200, deleted.statusCode(), "delete through portal");
                waitUntil(() -> registry.size() == 1, Duration.ofSeconds(20), "deleted upload withdrawn");
            }
        } finally {
            deleteTree(dir);
            deleteTree(logs);
            deleteTree(demo);
        }
    }

    private void webPortalAdminProtection() throws Exception {
        Path dir = Files.createTempDirectory("circularsync-web");
        Path logs = Files.createTempDirectory("circularsync-logs");
        HttpClient http = HttpClient.newHttpClient();
        String key = "Campus Pass+1 é";
        try {
            NoticeRegistry registry = new NoticeRegistry();
            FileActivityTracker tracker = new FileActivityTracker();
            try (AuditLogger audit = new AuditLogger(logs);
                 DirectoryWatcherDaemon daemon = new DirectoryWatcherDaemon(dir, parser, registry, audit, 2);
                 WebPortalServer portal = new WebPortalServer(
                         new PortalSettings("127.0.0.1", 0, Paths.get("web"), dir, key, false),
                         registry, daemon, tracker, audit, () -> TODAY)) {
                daemon.addProcessingListener(tracker);
                portal.start();
                daemon.start();
                String base = portal.getUrl();
                String notice = exam("Protected", "2026-11-20", "mandatory");

                checkEquals(401, post(http, base + "api/upload?name=a.txt", notice), "upload without key refused");
                checkEquals(401, post(http, base + "api/upload?name=a.txt", notice, "wrong-key"), "wrong key refused");
                check(get(http, base + "api/state").contains("\"isAdmin\":false"), "anonymous viewer is not admin");
                checkEquals(200, http.send(HttpRequest.newBuilder(URI.create(base + "api/state")).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode(), "board stays public");

                checkEquals(202, post(http, base + "api/upload?name=a.txt", notice, key), "upload with key accepted");
                waitUntil(() -> registry.size() == 1, Duration.ofSeconds(20), "admin upload indexed");

                for (int i = 0; i < 10; i++) {
                    post(http, base + "api/upload?name=a.txt", notice, "guess-" + i);
                }
                checkEquals(429, post(http, base + "api/upload?name=a.txt", notice, key), "locked out after repeated wrong keys");
            }
        } finally {
            deleteTree(dir);
            deleteTree(logs);
        }
    }

    private static int post(HttpClient http, String url, String body) throws IOException, InterruptedException {
        return post(http, url, body, null);
    }

    private static int post(HttpClient http, String url, String body, String adminKey) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body));
        if (adminKey != null) {
            // Same encoding as the portal page (encodeURIComponent): spaces as %20, "+" as %2B.
            request.header(WebPortalServer.ADMIN_HEADER,
                    java.net.URLEncoder.encode(adminKey, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static String get(HttpClient http, String url) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Assertion helpers
    // ------------------------------------------------------------------

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void checkEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + " but was " + actual);
        }
    }

    private static <T extends Throwable> T expect(Class<T> type, TestBody body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t, t);
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout, String message) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for: " + message);
            }
            Thread.sleep(50);
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Writes a minimal but valid .docx containing one paragraph per argument. */
    private static void writeDocx(Path file, String... paragraphs) throws IOException {
        StringBuilder body = new StringBuilder();
        for (String paragraph : paragraphs) {
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                    .append(paragraph.replace("&", "&amp;").replace("<", "&lt;"))
                    .append("</w:t></w:r></w:p>");
        }
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/></Types>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:body>" + body + "</w:body></w:document>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    /** Writes a one-page PDF with one line of Helvetica text per argument (no arguments: a blank page). */
    private static void writePdf(Path file, String... lines) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            if (lines.length > 0) {
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    content.newLineAtOffset(50, 740);
                    for (String line : lines) {
                        content.showText(line);
                        content.newLineAtOffset(0, -16);
                    }
                    content.endText();
                }
            }
            document.save(file.toFile());
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    deleteTree(child);
                } else {
                    Files.deleteIfExists(child);
                }
            }
        }
        Files.deleteIfExists(root);
    }
}
