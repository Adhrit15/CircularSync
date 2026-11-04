package com.circularsync.web;

import com.circularsync.daemon.DirectoryWatcherDaemon;
import com.circularsync.model.ClubNotice;
import com.circularsync.model.ExamNotice;
import com.circularsync.model.Notice;
import com.circularsync.model.NoticeCategory;
import com.circularsync.model.PlacementNotice;
import com.circularsync.service.AuditLogger;
import com.circularsync.service.NoticeParserService;
import com.circularsync.service.NoticeRegistry;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Web portal for CircularSync, built on the JDK's {@link HttpServer}.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code GET  /}                            the single-page portal ({@code web/index.html})</li>
 *   <li>{@code GET  /api/health}                  liveness probe for hosting platforms</li>
 *   <li>{@code GET  /api/state?q=&category=&due=} stats, tracked files, filtered board and activity</li>
 *   <li>{@code GET  /api/notice?id=}              one notice including its body</li>
 *   <li>{@code GET  /api/file?name=}              the original uploaded file (PDF, DOCX, TXT, MD)</li>
 *   <li>{@code POST /api/upload?name=}            raw file body, written atomically into the incoming directory [admin]</li>
 *   <li>{@code DELETE /api/files?name=}           deletes a file from the incoming directory [admin]</li>
 *   <li>{@code GET/POST /api/demo[?name=]}        list or drop a demo notice [POST: admin]</li>
 * </ul>
 *
 * <p>Security for public deployment: when an admin key is configured, every
 * state-changing request must present it in the {@code X-Admin-Key} header
 * (compared in constant time). Clients that send wrong keys are locked out
 * after {@value #MAX_FAILED_LOGINS} attempts per window, uploads are rate
 * limited per client, file names are whitelisted, bodies are size-capped
 * by type, and every response carries defensive security headers.</p>
 *
 * <p>Uploads never touch the registry directly: they land in the watched
 * directory and travel through exactly the same WatchService, queue and
 * worker pool as a file dropped in by hand.</p>
 */
public final class WebPortalServer implements AutoCloseable {

    public static final String ADMIN_HEADER = "X-Admin-Key";
    static final int MAX_FAILED_LOGINS = 10;
    private static final int MAX_WRITES_PER_WINDOW = 60;
    private static final long LIMIT_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(10);

    private static final Pattern SAFE_FILE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 ._()-]{0,119}$");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self' 'unsafe-inline'; "
            + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; "
            + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    private final PortalSettings settings;
    private final Path webRoot;
    private final Path incomingDirectory;
    private final Path demoDirectory;
    private final NoticeRegistry registry;
    private final DirectoryWatcherDaemon daemon;
    private final FileActivityTracker tracker;
    private final AuditLogger audit;
    private final Supplier<LocalDate> referenceDate;
    private final byte[] adminKeyBytes;
    private final RateLimiter failedLogins = new RateLimiter(MAX_FAILED_LOGINS, LIMIT_WINDOW_MILLIS);
    private final RateLimiter writes = new RateLimiter(MAX_WRITES_PER_WINDOW, LIMIT_WINDOW_MILLIS);

    private HttpServer server;
    private ExecutorService httpPool;

    public WebPortalServer(PortalSettings settings, NoticeRegistry registry, DirectoryWatcherDaemon daemon,
                           FileActivityTracker tracker, AuditLogger audit, Supplier<LocalDate> referenceDate) {
        this.settings = settings;
        this.webRoot = settings.getWebRoot().toAbsolutePath().normalize();
        this.incomingDirectory = daemon.getWatchDirectory();
        this.demoDirectory = settings.getDemoDirectory().toAbsolutePath().normalize();
        this.registry = registry;
        this.daemon = daemon;
        this.tracker = tracker;
        this.audit = audit;
        this.referenceDate = referenceDate;
        this.adminKeyBytes = settings.isAdminProtected()
                ? settings.getAdminKey().getBytes(StandardCharsets.UTF_8) : null;
    }

    public void start() throws IOException {
        if (!Files.isRegularFile(webRoot.resolve("index.html"))) {
            throw new NoSuchFileException(webRoot.resolve("index.html").toString(), null,
                    "portal page not found; run from the project folder or pass --webroot");
        }
        server = HttpServer.create(new InetSocketAddress(settings.getHost(), settings.getPort()), 64);
        AtomicInteger sequence = new AtomicInteger(1);
        httpPool = Executors.newFixedThreadPool(8, r -> {
            Thread thread = new Thread(r, "circularsync-http-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(httpPool);
        server.createContext("/", guarded(this::handleStatic));
        server.createContext("/api/health", guarded(this::handleHealth));
        server.createContext("/api/state", guarded(this::handleState));
        server.createContext("/api/notice", guarded(this::handleNotice));
        server.createContext("/api/file", guarded(this::handleFileDownload));
        server.createContext("/api/upload", guarded(this::handleUpload));
        server.createContext("/api/files", guarded(this::handleFiles));
        server.createContext("/api/demo", guarded(this::handleDemo));
        server.start();
        audit.info("PORTAL_STARTED", "Web portal listening on " + settings.getHost() + ":" + getPort()
                + (settings.isAdminProtected() ? " (admin key required for changes)" : " (open, no admin key)"));
    }

    /** URL for opening the portal on this computer. */
    public String getUrl() {
        return "http://localhost:" + getPort() + "/";
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            httpPool.shutdown();
            try {
                httpPool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            audit.info("PORTAL_STOPPED", "Web portal stopped");
            server = null;
        }
    }

    // ------------------------------------------------------------------
    // Handler plumbing
    // ------------------------------------------------------------------

    /** Thrown inside handlers to produce a JSON error response. */
    private static final class ApiException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private interface ApiHandler {
        void handle(HttpExchange exchange) throws IOException, ApiException;
    }

    private HttpHandler guarded(ApiHandler handler) {
        return exchange -> {
            try {
                applySecurityHeaders(exchange.getResponseHeaders());
                handler.handle(exchange);
            } catch (ApiException e) {
                trySendError(exchange, e.status, e.getMessage());
            } catch (IOException | RuntimeException e) {
                audit.error("PORTAL_ERROR", exchange.getRequestURI().getPath() + ": " + e);
                trySendError(exchange, 500, "Internal error");
            } finally {
                exchange.close();
            }
        };
    }

    private static void applySecurityHeaders(Headers headers) {
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    private void trySendError(HttpExchange exchange, int status, String message) {
        try {
            sendJson(exchange, status, new JsonWriter().beginObject().field("error", message).endObject());
        } catch (IOException ignored) {
            // client has gone away or headers were already sent
        }
    }

    // ------------------------------------------------------------------
    // Access control
    // ------------------------------------------------------------------

    private boolean isAdmin(HttpExchange exchange) {
        if (adminKeyBytes == null) {
            return true;
        }
        String presented = exchange.getRequestHeaders().getFirst(ADMIN_HEADER);
        if (presented == null) {
            return false;
        }
        // HTTP headers carry only ASCII, so the portal percent-encodes the password (spaces,
        // symbols and non-English letters survive); plain ASCII values decode to themselves.
        String decoded;
        try {
            decoded = URLDecoder.decode(presented.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            decoded = presented;
        }
        return MessageDigest.isEqual(adminKeyBytes, decoded.getBytes(StandardCharsets.UTF_8));
    }

    /** Admin check with brute-force lockout, plus a per-client write rate limit. */
    private void requireAdminWrite(HttpExchange exchange) throws ApiException {
        String client = clientAddress(exchange);
        if (adminKeyBytes != null) {
            if (failedLogins.isExhausted(client)) {
                throw new ApiException(429, "Too many wrong passwords; try again in a few minutes");
            }
            if (!isAdmin(exchange)) {
                failedLogins.tryAcquire(client);
                audit.warn("PORTAL_DENIED", "Rejected " + exchange.getRequestMethod() + " "
                        + exchange.getRequestURI().getPath() + " from " + client + " (missing or wrong admin password)");
                throw new ApiException(401, "Admin password required");
            }
        }
        if (!writes.tryAcquire(client)) {
            throw new ApiException(429, "Too many uploads; slow down");
        }
    }

    private String clientAddress(HttpExchange exchange) {
        if (settings.isTrustProxy()) {
            String cloudflare = exchange.getRequestHeaders().getFirst("CF-Connecting-IP");
            if (cloudflare != null && !cloudflare.isBlank()) {
                return cloudflare.trim();
            }
            String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private void handleStatic(HttpExchange exchange) throws IOException, ApiException {
        requireMethod(exchange, "GET");
        String path = exchange.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/index.html")) {
            throw new ApiException(404, "Not found");
        }
        byte[] page = Files.readAllBytes(webRoot.resolve("index.html"));
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        exchange.sendResponseHeaders(200, page.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(page);
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException, ApiException {
        requireMethod(exchange, "GET");
        sendJson(exchange, 200, new JsonWriter().beginObject()
                .field("status", "ok").field("activeNotices", registry.size()).endObject());
    }

    private void handleState(HttpExchange exchange) throws IOException, ApiException {
        requireMethod(exchange, "GET");
        Map<String, String> query = parseQuery(exchange);
        List<Notice> board = filteredBoard(query);

        JsonWriter json = new JsonWriter().beginObject();
        json.field("referenceDate", referenceDate.get().toString());
        json.field("watchDirectory", incomingDirectory.getFileName().toString());
        json.field("workers", daemon.getWorkerCount());
        json.field("adminRequired", settings.isAdminProtected());
        json.field("isAdmin", isAdmin(exchange));
        json.field("acceptedTypes", new ArrayList<>(new TreeSet<>(NoticeParserService.SUPPORTED_EXTENSIONS)));

        DirectoryWatcherDaemon.Statistics stats = daemon.getStatistics();
        Map<NoticeCategory, Integer> counts = registry.countsByCategory();
        json.name("stats").beginObject()
                .field("active", registry.size())
                .field("exam", counts.get(NoticeCategory.EXAM))
                .field("placement", counts.get(NoticeCategory.PLACEMENT))
                .field("club", counts.get(NoticeCategory.CLUB))
                .field("general", counts.get(NoticeCategory.GENERAL))
                .field("indexed", stats.indexed.get())
                .field("rejectedFormat", stats.rejectedInvalid.get())
                .field("rejectedExpired", stats.rejectedExpired.get())
                .field("removed", stats.removed.get())
                .field("duplicates", stats.duplicateContent.get())
                .field("queueDepth", daemon.getQueueDepth())
                .field("terms", registry.indexedTermCount())
                .endObject();

        json.name("files").beginArray();
        for (FileActivityTracker.FileView file : tracker.files()) {
            json.beginObject()
                    .field("id", file.id)
                    .field("name", file.fileName)
                    .field("source", file.source.name())
                    .field("size", file.size)
                    .field("receivedAt", file.receivedAt.format(ISO))
                    .field("updatedAt", file.updatedAt.format(ISO))
                    .field("status", file.status.name())
                    .field("noticeId", file.noticeId)
                    .field("category", file.category)
                    .field("score", file.score)
                    .field("message", file.message)
                    .endObject();
        }
        json.endArray();

        json.name("board").beginArray();
        int rank = 1;
        for (Notice notice : board) {
            writeNotice(json, notice, rank++, false);
        }
        json.endArray();

        json.name("activity").beginArray();
        for (FileActivityTracker.ActivityEntry entry : tracker.activity()) {
            json.beginObject()
                    .field("time", entry.time.format(ISO))
                    .field("level", entry.level)
                    .field("outcome", entry.outcome)
                    .field("file", entry.fileName)
                    .field("message", entry.message)
                    .endObject();
        }
        json.endArray();

        sendJson(exchange, 200, json.endObject());
    }

    private void handleNotice(HttpExchange exchange) throws IOException, ApiException {
        requireMethod(exchange, "GET");
        String id = parseQuery(exchange).getOrDefault("id", "");
        Notice notice = registry.find(id).orElseThrow(() -> new ApiException(404, "No notice with that id"));
        JsonWriter json = new JsonWriter();
        writeNotice(json, notice, 0, true);
        sendJson(exchange, 200, json);
    }

    /** Serves an original file from the incoming folder so readers can open the real circular. */
    private void handleFileDownload(HttpExchange exchange) throws IOException, ApiException {
        requireMethod(exchange, "GET");
        String name = validFileName(parseQuery(exchange).get("name"));
        Path file = resolveIncoming(name);
        if (!Files.isRegularFile(file)) {
            throw new ApiException(404, "File not found");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        String type;
        boolean inline = true;
        if (lower.endsWith(".pdf")) {
            type = "application/pdf";
        } else if (lower.endsWith(".docx")) {
            type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            inline = false;
        } else {
            type = "text/plain; charset=utf-8";
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type);
        headers.set("Content-Disposition", (inline ? "inline" : "attachment") + "; filename=\"" + name + "\"");
        headers.set("Content-Security-Policy", "sandbox");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, Files.size(file));
        try (OutputStream body = exchange.getResponseBody()) {
            Files.copy(file, body);
        }
    }

    private void handleUpload(HttpExchange exchange) throws IOException, ApiException {
        requireMethod(exchange, "POST");
        requireAdminWrite(exchange);
        String name = validFileName(parseQuery(exchange).get("name"));
        long limit = NoticeParserService.maxBytesFor(name);
        byte[] content;
        try (InputStream body = exchange.getRequestBody()) {
            content = body.readNBytes((int) limit + 1);
        }
        if (content.length > limit) {
            throw new ApiException(413, "File is larger than " + (limit >= 1024 * 1024 ? limit / (1024 * 1024) + " MB" : limit / 1024 + " KB"));
        }
        if (content.length == 0) {
            throw new ApiException(400, "File is empty");
        }
        checkMagic(name, content);
        deliver(name, content, FileActivityTracker.Source.UPLOAD, clientAddress(exchange));
        sendJson(exchange, 202, new JsonWriter().beginObject().field("accepted", name).field("size", content.length).endObject());
    }

    private void handleFiles(HttpExchange exchange) throws IOException, ApiException {
        requireMethod(exchange, "DELETE");
        requireAdminWrite(exchange);
        String name = validFileName(parseQuery(exchange).get("name"));
        if (!Files.deleteIfExists(resolveIncoming(name))) {
            throw new ApiException(404, name + " is not in the incoming folder");
        }
        audit.info("PORTAL_DELETE", name + " deleted through the portal by " + clientAddress(exchange));
        sendJson(exchange, 200, new JsonWriter().beginObject().field("deleted", name).endObject());
    }

    private void handleDemo(HttpExchange exchange) throws IOException, ApiException {
        String method = exchange.getRequestMethod();
        if (method.equals("GET")) {
            Set<String> present = new HashSet<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(incomingDirectory)) {
                for (Path p : stream) {
                    present.add(p.getFileName().toString());
                }
            }
            JsonWriter json = new JsonWriter().beginArray();
            for (String name : listDemoFiles()) {
                json.beginObject().field("name", name).field("inFolder", present.contains(name)).endObject();
            }
            sendJson(exchange, 200, json.endArray());
        } else if (method.equals("POST")) {
            requireAdminWrite(exchange);
            String name = validFileName(parseQuery(exchange).get("name"));
            if (!listDemoFiles().contains(name)) {
                throw new ApiException(404, "No demo file named " + name);
            }
            byte[] content = Files.readAllBytes(demoDirectory.resolve(name));
            deliver(name, content, FileActivityTracker.Source.DEMO, clientAddress(exchange));
            sendJson(exchange, 202, new JsonWriter().beginObject().field("accepted", name).endObject());
        } else {
            throw new ApiException(405, "Method not allowed");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Writes the file to a hidden temporary name and atomically renames it,
     * so the WatchService only ever sees a complete file.
     */
    private void deliver(String name, byte[] content, FileActivityTracker.Source source, String client) throws IOException {
        Path target = resolveIncomingUnchecked(name);
        Path temp = Files.createTempFile(incomingDirectory, ".upload-", ".tmp");
        try {
            Files.write(temp, content);
            tracker.recordArrival(name, content.length, source);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            audit.info(source == FileActivityTracker.Source.UPLOAD ? "PORTAL_UPLOAD" : "PORTAL_DEMO",
                    name + " (" + content.length + " bytes) delivered to " + incomingDirectory.getFileName() + " by " + client);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Rejects files whose content does not match their extension (e.g. an .exe renamed to .pdf). */
    private static void checkMagic(String name, byte[] content) throws ApiException {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf") && !startsWith(content, "%PDF-")) {
            throw new ApiException(415, name + " is not a real PDF file");
        }
        if (lower.endsWith(".docx") && !startsWith(content, "PK\u0003\u0004")) {
            throw new ApiException(415, name + " is not a real .docx file (old .doc files must be saved as .docx)");
        }
    }

    private static boolean startsWith(byte[] content, String prefix) {
        byte[] expected = prefix.getBytes(StandardCharsets.ISO_8859_1);
        return content.length >= expected.length
                && Arrays.equals(Arrays.copyOf(content, expected.length), expected);
    }

    private Path resolveIncoming(String name) throws ApiException {
        Path target = resolveIncomingUnchecked(name);
        if (!incomingDirectory.equals(target.getParent())) {
            throw new ApiException(400, "Invalid file name");
        }
        return target;
    }

    private Path resolveIncomingUnchecked(String name) {
        return incomingDirectory.resolve(name).normalize();
    }

    private List<Notice> filteredBoard(Map<String, String> query) throws ApiException {
        List<Notice> notices;
        String due = query.get("due");
        if (due != null && !due.isBlank()) {
            try {
                notices = registry.dueWithin(Long.parseLong(due.trim()));
            } catch (NumberFormatException e) {
                throw new ApiException(400, "due must be a whole number of days");
            }
        } else {
            notices = registry.ranked();
        }
        String q = query.get("q");
        if (q != null && !q.isBlank()) {
            Set<String> matches = new HashSet<>();
            for (Notice notice : registry.search(q.length() > 200 ? q.substring(0, 200) : q)) {
                matches.add(notice.getId());
            }
            notices.removeIf(notice -> !matches.contains(notice.getId()));
        }
        Optional<NoticeCategory> category = NoticeCategory.fromLabel(query.get("category"));
        category.ifPresent(c -> notices.removeIf(notice -> notice.getCategory() != c));
        return notices;
    }

    private void writeNotice(JsonWriter json, Notice notice, int rank, boolean includeBody) {
        Map<String, String> headers = notice.getHeaders();
        json.beginObject()
                .field("rank", rank)
                .field("id", notice.getId())
                .field("category", notice.getCategory().name())
                .field("categoryLabel", notice.getCategory().getDisplayName())
                .field("title", notice.getTitle())
                .field("issuer", notice.getIssuer())
                .field("issueDate", notice.getIssueDate().toString())
                .field("deadline", notice.getDeadline().toString())
                .field("daysLeft", notice.getDaysUntilDeadline())
                .field("score", notice.getPriorityScore())
                .field("source", notice.getSourceName())
                .field("sourceAvailable", Files.isRegularFile(resolveIncomingUnchecked(notice.getSourceName())))
                .field("extractedFrom", headers.get("EXTRACTED_FROM"))
                .field("keywords", new ArrayList<>(new TreeSet<>(notice.getPriorityKeys())));
        List<String> inferred = new ArrayList<>();
        String inferredHeader = headers.get("INFERRED_FIELDS");
        if (inferredHeader != null) {
            for (String field : inferredHeader.split(",")) {
                if (!field.isBlank()) {
                    inferred.add(field.trim());
                }
            }
        }
        json.field("inferredFields", inferred);
        json.field("summary", notice.formatSummary());
        json.name("facts").beginArray();
        for (Map.Entry<String, String> fact : facts(notice).entrySet()) {
            json.beginObject().field("label", fact.getKey()).field("value", fact.getValue()).endObject();
        }
        json.endArray();
        if (includeBody) {
            json.field("body", notice.getBody());
        }
        json.endObject();
    }

    /** Category-specific key facts for the notice cards. */
    private static Map<String, String> facts(Notice notice) {
        Map<String, String> facts = new LinkedHashMap<>();
        if (notice instanceof ExamNotice) {
            ExamNotice exam = (ExamNotice) notice;
            facts.put("Course", exam.getCourseCode() + " - " + exam.getExamType());
            facts.put("Exam date", exam.getExamDate().toString());
            facts.put("Venue", exam.getVenue());
        } else if (notice instanceof PlacementNotice) {
            PlacementNotice placement = (PlacementNotice) notice;
            facts.put("Company", placement.getCompany());
            facts.put("Role", placement.getRole());
            if (placement.getPackageLpa() > 0) {
                facts.put("Package", String.format(Locale.ROOT, "%.1f LPA", placement.getPackageLpa()));
            }
            if (placement.getDriveDate() != null) {
                facts.put("Drive date", placement.getDriveDate().toString());
            }
            facts.put("Eligibility", placement.getEligibility());
        } else if (notice instanceof ClubNotice) {
            ClubNotice club = (ClubNotice) notice;
            facts.put("Club", club.getClubName());
            facts.put("Event date", club.getEventDate().toString());
            facts.put("Venue", club.getEventVenue());
            facts.put("Seats", club.getSeats() > 0 ? String.valueOf(club.getSeats()) : "Open entry");
        } else {
            facts.put("Issued by", notice.getIssuer());
        }
        return facts;
    }

    private List<String> listDemoFiles() throws IOException {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(demoDirectory)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(demoDirectory)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path) && NoticeParserService.isSupported(path)) {
                    names.add(path.getFileName().toString());
                }
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    private static String validFileName(String name) throws ApiException {
        if (name == null || !SAFE_FILE_NAME.matcher(name).matches() || name.contains("..")) {
            throw new ApiException(400, "File name must use letters, digits, spaces, dots, dashes or underscores");
        }
        if (!NoticeParserService.isSupported(Path.of(name))) {
            throw new ApiException(415, "Only " + new TreeSet<>(NoticeParserService.SUPPORTED_EXTENSIONS) + " files are accepted");
        }
        return name;
    }

    private static void requireMethod(HttpExchange exchange, String method) throws ApiException {
        if (!exchange.getRequestMethod().equalsIgnoreCase(method)) {
            throw new ApiException(405, "Method not allowed");
        }
    }

    private static Map<String, String> parseQuery(HttpExchange exchange) throws ApiException {
        Map<String, String> params = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        try {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                params.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "Malformed query string");
        }
        return params;
    }

    private static void sendJson(HttpExchange exchange, int status, JsonWriter json) throws IOException {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }
}
