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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
 * Local web portal for CircularSync, built on the JDK's
 * {@link HttpServer}. Bound to the loopback interface only.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code GET  /}                         the single-page portal ({@code web/index.html})</li>
 *   <li>{@code GET  /api/state?q=&category=&due=} stats, tracked files, filtered board and activity</li>
 *   <li>{@code GET  /api/notice?id=}           one notice including its body</li>
 *   <li>{@code POST /api/upload?name=}         raw file body; written atomically into the incoming directory</li>
 *   <li>{@code DELETE /api/files?name=}        deletes a file from the incoming directory</li>
 *   <li>{@code GET  /api/demo}                 demo notice files available to drop</li>
 *   <li>{@code POST /api/demo?name=}           copies one demo file into the incoming directory</li>
 * </ul>
 *
 * <p>Uploads never touch the registry directly: they land in the watched
 * directory and travel through exactly the same WatchService, queue and
 * worker pool as a file dropped in by hand.</p>
 */
public final class WebPortalServer implements AutoCloseable {

    private static final Pattern SAFE_FILE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 ._()-]{0,119}$");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final int requestedPort;
    private final Path webRoot;
    private final Path incomingDirectory;
    private final Path demoDirectory;
    private final NoticeRegistry registry;
    private final DirectoryWatcherDaemon daemon;
    private final FileActivityTracker tracker;
    private final AuditLogger audit;
    private final Supplier<LocalDate> referenceDate;

    private HttpServer server;
    private ExecutorService httpPool;

    public WebPortalServer(int port, Path webRoot, Path demoDirectory, NoticeRegistry registry,
                           DirectoryWatcherDaemon daemon, FileActivityTracker tracker, AuditLogger audit,
                           Supplier<LocalDate> referenceDate) {
        this.requestedPort = port;
        this.webRoot = webRoot.toAbsolutePath().normalize();
        this.incomingDirectory = daemon.getWatchDirectory();
        this.demoDirectory = demoDirectory.toAbsolutePath().normalize();
        this.registry = registry;
        this.daemon = daemon;
        this.tracker = tracker;
        this.audit = audit;
        this.referenceDate = referenceDate;
    }

    public void start() throws IOException {
        if (!Files.isRegularFile(webRoot.resolve("index.html"))) {
            throw new NoSuchFileException(webRoot.resolve("index.html").toString(), null,
                    "portal page not found; run from the project folder or pass --webroot");
        }
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 0);
        AtomicInteger sequence = new AtomicInteger(1);
        httpPool = Executors.newFixedThreadPool(4, r -> {
            Thread thread = new Thread(r, "circularsync-http-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(httpPool);
        server.createContext("/", guarded(this::handleStatic));
        server.createContext("/api/state", guarded(this::handleState));
        server.createContext("/api/notice", guarded(this::handleNotice));
        server.createContext("/api/upload", guarded(this::handleUpload));
        server.createContext("/api/files", guarded(this::handleFiles));
        server.createContext("/api/demo", guarded(this::handleDemo));
        server.start();
        audit.info("PORTAL_STARTED", "Web portal listening on " + getUrl());
    }

    public String getUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/";
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
    // Handlers
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
                handler.handle(exchange);
            } catch (ApiException e) {
                sendJson(exchange, e.status, new JsonWriter().beginObject().field("error", e.getMessage()).endObject());
            } catch (IOException | RuntimeException e) {
                audit.error("PORTAL_ERROR", exchange.getRequestURI() + ": " + e);
                try {
                    sendJson(exchange, 500, new JsonWriter().beginObject().field("error", "Internal error: " + e.getMessage()).endObject());
                } catch (IOException ignored) {
                    // client has gone away
                }
            } finally {
                exchange.close();
            }
        };
    }

    private void handleStatic(HttpExchange exchange) throws IOException, ApiException {
        requireMethod(exchange, "GET");
        String path = exchange.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/index.html")) {
            throw new ApiException(404, "Not found");
        }
        byte[] page = Files.readAllBytes(webRoot.resolve("index.html"));
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, page.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(page);
        }
    }

    private void handleState(HttpExchange exchange) throws IOException, ApiException {
        requireMethod(exchange, "GET");
        Map<String, String> query = parseQuery(exchange);
        List<Notice> board = filteredBoard(query);

        JsonWriter json = new JsonWriter().beginObject();
        json.field("referenceDate", referenceDate.get().toString());
        json.field("watchDirectory", incomingDirectory.toString());
        json.field("workers", daemon.getWorkerCount());

        DirectoryWatcherDaemon.Statistics stats = daemon.getStatistics();
        Map<NoticeCategory, Integer> counts = registry.countsByCategory();
        json.name("stats").beginObject()
                .field("active", registry.size())
                .field("exam", counts.get(NoticeCategory.EXAM))
                .field("placement", counts.get(NoticeCategory.PLACEMENT))
                .field("club", counts.get(NoticeCategory.CLUB))
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
        Notice notice = registry.find(id).orElseThrow(() -> new ApiException(404, "No notice with id " + id));
        JsonWriter json = new JsonWriter();
        writeNotice(json, notice, 0, true);
        sendJson(exchange, 200, json);
    }

    private void handleUpload(HttpExchange exchange) throws IOException, ApiException {
        requireMethod(exchange, "POST");
        String name = validFileName(parseQuery(exchange).get("name"));
        byte[] content;
        try (InputStream body = exchange.getRequestBody()) {
            content = body.readNBytes((int) NoticeParserService.MAX_FILE_BYTES + 1);
        }
        if (content.length > NoticeParserService.MAX_FILE_BYTES) {
            throw new ApiException(413, "File is larger than " + NoticeParserService.MAX_FILE_BYTES / 1024 + " KB");
        }
        if (content.length == 0) {
            throw new ApiException(400, "File is empty");
        }
        deliver(name, content, FileActivityTracker.Source.UPLOAD);
        sendJson(exchange, 202, new JsonWriter().beginObject().field("accepted", name).field("size", content.length).endObject());
    }

    private void handleFiles(HttpExchange exchange) throws IOException, ApiException {
        requireMethod(exchange, "DELETE");
        String name = validFileName(parseQuery(exchange).get("name"));
        Path target = incomingDirectory.resolve(name).normalize();
        if (!target.getParent().equals(incomingDirectory)) {
            throw new ApiException(400, "Invalid file name");
        }
        if (!Files.deleteIfExists(target)) {
            throw new ApiException(404, name + " is not in the incoming folder");
        }
        audit.info("PORTAL_DELETE", name + " deleted through the portal");
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
            String name = validFileName(parseQuery(exchange).get("name"));
            if (!listDemoFiles().contains(name)) {
                throw new ApiException(404, "No demo file named " + name);
            }
            byte[] content = Files.readAllBytes(demoDirectory.resolve(name));
            deliver(name, content, FileActivityTracker.Source.DEMO);
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
    private void deliver(String name, byte[] content, FileActivityTracker.Source source) throws IOException {
        Path target = incomingDirectory.resolve(name);
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
                    name + " (" + content.length + " bytes) delivered to " + incomingDirectory.getFileName());
        } finally {
            Files.deleteIfExists(temp);
        }
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
            for (Notice notice : registry.search(q)) {
                matches.add(notice.getId());
            }
            notices.removeIf(notice -> !matches.contains(notice.getId()));
        }
        Optional<NoticeCategory> category = NoticeCategory.fromLabel(query.get("category"));
        category.ifPresent(c -> notices.removeIf(notice -> notice.getCategory() != c));
        return notices;
    }

    private void writeNotice(JsonWriter json, Notice notice, int rank, boolean includeBody) {
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
                .field("keywords", new ArrayList<>(new TreeSet<>(notice.getPriorityKeys())))
                .field("summary", notice.formatSummary());
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
            throw new ApiException(415, "Only " + NoticeParserService.SUPPORTED_EXTENSIONS + " files are accepted");
        }
        return name;
    }

    private static void requireMethod(HttpExchange exchange, String method) throws ApiException {
        if (!exchange.getRequestMethod().equalsIgnoreCase(method)) {
            throw new ApiException(405, "Method not allowed");
        }
    }

    private static Map<String, String> parseQuery(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
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
