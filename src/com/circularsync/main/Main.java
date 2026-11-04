package com.circularsync.main;

import com.circularsync.daemon.DirectoryWatcherDaemon;
import com.circularsync.model.Notice;
import com.circularsync.service.AuditLogger;
import com.circularsync.service.NoticeParserService;
import com.circularsync.service.NoticeRegistry;
import com.circularsync.web.FileActivityTracker;
import com.circularsync.web.WebPortalServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.BindException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Command-line harness for CircularSync.
 *
 * <pre>
 * java -cp out com.circularsync.main.Main scan
 * java -cp out com.circularsync.main.Main daemon [--duration 30]
 * java -cp out com.circularsync.main.Main web [--port 8080]
 * java -cp out com.circularsync.main.Main query top 3
 * </pre>
 */
public final class Main {

    static final String BOARD_SNAPSHOT_FILE = "processed_notices.txt";

    private static final String USAGE = String.join(System.lineSeparator(),
            "CircularSync - campus circular parsing daemon and query system",
            "",
            "Usage: java -cp out com.circularsync.main.Main <command> [options]",
            "",
            "Commands:",
            "  scan              parse every notice in the incoming directory once, print the board, exit",
            "  daemon            process existing notices, then watch the directory for new ones and",
            "                    open an interactive query shell (type 'help' inside it)",
            "  web               like daemon, plus a browser portal for uploads and the live board",
            "  query <command>   scan once, then run a single query command and exit",
            "  help              show this text",
            "",
            "Options:",
            "  --dir <path>        directory to watch      (default: incoming_notices)",
            "  --logs <path>       audit log directory     (default: audit_logs)",
            "  --threads <n>       worker thread count     (default: 4)",
            "  --duration <secs>   daemon only: run unattended for this long, then stop",
            "  --today <date>      override today's date (yyyy-MM-dd), for reproducible runs",
            "  --port <n>          web only: portal port   (default: 8080)",
            "  --webroot <path>    web only: portal files  (default: web)",
            "  --no-browser        web only: do not open the portal in a browser",
            "",
            QueryConsole.HELP_TEXT);

    private Main() {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Run with 'help' for usage.");
            exitCode = 2;
        } catch (IOException e) {
            System.err.println("I/O failure: " + e.getMessage());
            exitCode = 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted.");
            exitCode = 130;
        }
        System.exit(exitCode);
    }

    static int run(String[] args) throws IOException, InterruptedException {
        Options options = Options.parse(args);
        switch (options.command) {
            case "help":
                System.out.println(USAGE);
                return 0;
            case "scan":
                return runScan(options, List.of("list"));
            case "query":
                if (options.queryArgs.isEmpty()) {
                    throw new IllegalArgumentException("query needs a command, for example: query top 3");
                }
                return runScan(options, List.of(String.join(" ", options.queryArgs)));
            case "daemon":
                return runDaemon(options, false);
            case "web":
                return runDaemon(options, true);
            default:
                throw new IllegalArgumentException("Unknown command '" + options.command + "'");
        }
    }

    /** One-shot mode: drain the directory through the worker pool, answer queries, exit. */
    private static int runScan(Options options, List<String> queries) throws IOException, InterruptedException {
        NoticeRegistry registry = new NoticeRegistry();
        try (AuditLogger audit = new AuditLogger(options.logDirectory)) {
            audit.info("SESSION_START", "scan of " + options.watchDirectory.toAbsolutePath()
                    + " reference date " + options.today.get());
            DirectoryWatcherDaemon.Statistics stats;
            try (DirectoryWatcherDaemon daemon = newDaemon(options, registry, audit)) {
                daemon.startWorkers();
                int queued = daemon.enqueueExistingFiles();
                System.out.println("CircularSync: processing " + queued + " notice file(s) from "
                        + daemon.getWatchDirectory() + " with " + options.threads + " worker thread(s)...");
                if (!daemon.awaitIdle(Duration.ofSeconds(60))) {
                    System.err.println("Warning: processing did not finish within 60 seconds.");
                }
                stats = daemon.getStatistics();
                QueryConsole console = new QueryConsole(registry, daemon, System.out);
                System.out.println();
                for (String query : queries) {
                    console.execute(query);
                }
            }
            Path snapshot = writeBoardSnapshot(audit, registry);
            System.out.println();
            System.out.println("Pipeline summary: " + stats);
            System.out.println("Audit log: " + audit.getLogFile());
            System.out.println("Board snapshot: " + snapshot);
            audit.info("SESSION_END", "scan complete, " + registry.size() + " active notice(s)");
            return stats.ioErrors.get() == 0 ? 0 : 1;
        }
    }

    /**
     * Continuous mode: watch the directory and serve queries until quit, Ctrl+C or --duration
     * elapses. With {@code withPortal} the web portal runs alongside the console.
     */
    private static int runDaemon(Options options, boolean withPortal) throws IOException, InterruptedException {
        NoticeRegistry registry = new NoticeRegistry();
        AuditLogger audit = new AuditLogger(options.logDirectory);
        DirectoryWatcherDaemon daemon = newDaemon(options, registry, audit);
        AtomicReference<WebPortalServer> portal = new AtomicReference<>();
        CountDownLatch stopped = new CountDownLatch(1);
        Runnable shutdown = new Runnable() {
            private boolean done;

            @Override
            public synchronized void run() {
                if (done) {
                    return;
                }
                done = true;
                WebPortalServer server = portal.get();
                if (server != null) {
                    server.close();
                }
                daemon.close();
                try {
                    writeBoardSnapshot(audit, registry);
                } catch (IOException e) {
                    System.err.println("Could not write board snapshot: " + e.getMessage());
                }
                audit.info("SESSION_END", "daemon stopped, " + registry.size() + " active notice(s)");
                audit.close();
                stopped.countDown();
            }
        };
        Thread hook = new Thread(shutdown, "circularsync-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);

        audit.info("SESSION_START", "daemon on " + options.watchDirectory.toAbsolutePath()
                + " reference date " + options.today.get());
        daemon.addIndexListener(notice -> System.out.println(System.lineSeparator()
                + ">> New notice indexed: " + notice.formatSummary()));
        if (withPortal) {
            FileActivityTracker tracker = new FileActivityTracker();
            daemon.addProcessingListener(tracker);
            WebPortalServer server = new WebPortalServer(options.port, options.webRoot, options.demoDirectory,
                    registry, daemon, tracker, audit, options.today);
            try {
                server.start();
            } catch (BindException e) {
                throw new IllegalArgumentException("port " + options.port + " is already in use; pass --port <n>");
            }
            portal.set(server);
        }
        daemon.start();
        daemon.awaitIdle(Duration.ofSeconds(30));
        System.out.println("CircularSync daemon watching " + daemon.getWatchDirectory()
                + " with " + options.threads + " worker thread(s). " + registry.size() + " notice(s) active.");
        if (portal.get() != null) {
            String url = portal.get().getUrl();
            System.out.println();
            System.out.println("  Web portal running at " + url);
            System.out.println();
            if (options.openBrowser) {
                openBrowser(url);
            }
        }

        QueryConsole console = new QueryConsole(registry, daemon, System.out);
        if (options.durationSeconds > 0) {
            System.out.println("Running unattended for " + options.durationSeconds + " second(s).");
            stopped.await(options.durationSeconds, TimeUnit.SECONDS);
            daemon.awaitIdle(Duration.ofSeconds(10));
            System.out.println();
            console.execute("list");
            console.execute("stats");
        } else {
            System.out.println("Type 'help' for query commands, 'quit' to stop.");
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                while (true) {
                    System.out.print("circularsync> ");
                    System.out.flush();
                    String line = in.readLine();
                    if (line == null) {
                        // stdin closed (e.g. running as a background service): keep watching until signalled.
                        System.out.println();
                        System.out.println("Input closed; daemon keeps running. Press Ctrl+C to stop.");
                        stopped.await();
                        break;
                    }
                    if (console.execute(line) == QueryConsole.Outcome.QUIT) {
                        break;
                    }
                }
            }
        }
        shutdown.run();
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // JVM is already shutting down; the hook has done the work.
        }
        System.out.println("Pipeline summary: " + daemon.getStatistics());
        System.out.println("Audit log: " + audit.getLogFile());
        return 0;
    }

    private static void openBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (IOException | RuntimeException e) {
            // fall through to the manual hint
        }
        System.out.println("  Open " + url + " in your browser.");
    }

    private static DirectoryWatcherDaemon newDaemon(Options options, NoticeRegistry registry, AuditLogger audit) {
        return new DirectoryWatcherDaemon(options.watchDirectory, new NoticeParserService(options.today),
                registry, audit, options.threads);
    }

    private static Path writeBoardSnapshot(AuditLogger audit, NoticeRegistry registry) throws IOException {
        List<Notice> board = registry.ranked();
        List<String> lines = new ArrayList<>();
        lines.add("CircularSync processed notice board");
        lines.add("Generated: " + java.time.LocalDateTime.now().withNano(0));
        lines.add("Active notices: " + board.size());
        lines.add("");
        lines.addAll(QueryConsole.renderBoard(board));
        return audit.writeSnapshot(BOARD_SNAPSHOT_FILE, lines);
    }

    /** Parsed command-line options. */
    static final class Options {
        String command = "help";
        final List<String> queryArgs = new ArrayList<>();
        Path watchDirectory = Paths.get("incoming_notices");
        Path logDirectory = Paths.get("audit_logs");
        int threads = 4;
        long durationSeconds;
        Supplier<LocalDate> today = LocalDate::now;
        int port = 8080;
        Path webRoot = Paths.get("web");
        Path demoDirectory = Paths.get("demo");
        boolean openBrowser = true;

        static Options parse(String[] args) {
            Options options = new Options();
            boolean commandSeen = false;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--dir":
                        options.watchDirectory = Paths.get(value(args, ++i, arg));
                        break;
                    case "--logs":
                        options.logDirectory = Paths.get(value(args, ++i, arg));
                        break;
                    case "--threads":
                        options.threads = integer(value(args, ++i, arg), arg, 1, 64);
                        break;
                    case "--duration":
                        options.durationSeconds = integer(value(args, ++i, arg), arg, 1, 86_400);
                        break;
                    case "--port":
                        options.port = integer(value(args, ++i, arg), arg, 0, 65_535);
                        break;
                    case "--webroot":
                        options.webRoot = Paths.get(value(args, ++i, arg));
                        break;
                    case "--no-browser":
                        options.openBrowser = false;
                        break;
                    case "--today":
                        String date = value(args, ++i, arg);
                        try {
                            LocalDate fixed = LocalDate.parse(date);
                            options.today = () -> fixed;
                        } catch (DateTimeParseException e) {
                            throw new IllegalArgumentException("--today expects yyyy-MM-dd, got '" + date + "'");
                        }
                        break;
                    case "-h":
                    case "--help":
                        options.command = "help";
                        commandSeen = true;
                        break;
                    default:
                        if (arg.startsWith("--")) {
                            throw new IllegalArgumentException("Unknown option " + arg);
                        }
                        if (!commandSeen) {
                            options.command = arg.toLowerCase(java.util.Locale.ROOT);
                            commandSeen = true;
                        } else if (options.command.equals("query")) {
                            options.queryArgs.add(arg);
                        } else {
                            throw new IllegalArgumentException("Unexpected argument '" + arg + "'");
                        }
                }
            }
            return options;
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }

        private static int integer(String value, String option, int min, int max) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < min || parsed > max) {
                    throw new NumberFormatException();
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " expects a whole number between " + min + " and " + max);
            }
        }
    }
}
