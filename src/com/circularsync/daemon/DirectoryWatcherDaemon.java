package com.circularsync.daemon;

import com.circularsync.exceptions.ExpiredDeadlineException;
import com.circularsync.exceptions.InvalidNoticeFormatException;
import com.circularsync.model.Notice;
import com.circularsync.service.AuditLogger;
import com.circularsync.service.NoticeParserService;
import com.circularsync.service.NoticeRegistry;
import com.circularsync.service.NoticeRegistry.RegistrationResult;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Real-time ingestion engine.
 *
 * <p>Thread model (producer / consumer):</p>
 * <ul>
 *   <li><b>Watcher thread</b> blocks on {@link WatchService#take()} for
 *       CREATE / MODIFY / DELETE events in the incoming directory. It never
 *       reads file content; it only translates events into paths and offers
 *       them to the work queue, so it is always ready for the next event.</li>
 *   <li><b>Work queue</b> is a bounded {@link LinkedBlockingQueue}. Its bound
 *       applies back-pressure if notices arrive faster than they can be parsed.</li>
 *   <li><b>Worker pool</b> created with {@link Executors#newFixedThreadPool}
 *       runs {@code workerCount} long-lived consumer loops that
 *       {@code take()} paths, wait for the file to finish being written, parse,
 *       validate and register it.</li>
 * </ul>
 *
 * <p>Race prevention: a path is queued at most once at a time
 * ({@code queuedPaths}), each path is processed under its own
 * {@link ReentrantLock} so two workers never handle the same file
 * concurrently, and a (size, modified-time) fingerprint suppresses the
 * duplicate MODIFY events most operating systems emit for one save.</p>
 */
public class DirectoryWatcherDaemon implements AutoCloseable {

    /** Identity-compared sentinel that tells a worker loop to exit. */
    private static final Path POISON_PILL = Paths.get("circularsync-poison-pill");
    private static final int QUEUE_CAPACITY = 1024;
    private static final long STABILITY_POLL_MILLIS = 120;
    private static final int STABILITY_MAX_POLLS = 25;

    private final Path watchDirectory;
    private final NoticeParserService parser;
    private final NoticeRegistry registry;
    private final AuditLogger audit;
    private final int workerCount;

    private final BlockingQueue<Path> workQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final Set<Path> queuedPaths = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Path, ReentrantLock> pathLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Path, FileFingerprint> lastProcessed = new ConcurrentHashMap<>();
    private final List<Consumer<Notice>> indexListeners = new CopyOnWriteArrayList<>();
    private final Statistics statistics = new Statistics();

    private final Object idleMonitor = new Object();
    private int inFlight; // guarded by idleMonitor

    private final AtomicBoolean workersStarted = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private ExecutorService workerPool;
    private WatchService watchService;
    private Thread watcherThread;

    public DirectoryWatcherDaemon(Path watchDirectory, NoticeParserService parser, NoticeRegistry registry,
                                  AuditLogger audit, int workerCount) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be at least 1");
        }
        this.watchDirectory = watchDirectory.toAbsolutePath().normalize();
        this.parser = Objects.requireNonNull(parser, "parser");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.workerCount = workerCount;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Starts workers, processes files already present, then begins watching. */
    public void start() throws IOException {
        startWorkers();
        enqueueExistingFiles();
        startWatching();
    }

    /** Creates the fixed worker pool and launches the consumer loops. */
    public void startWorkers() throws IOException {
        if (!workersStarted.compareAndSet(false, true)) {
            return;
        }
        Files.createDirectories(watchDirectory);
        workerPool = Executors.newFixedThreadPool(workerCount, new NamedThreadFactory("circularsync-worker-"));
        for (int i = 0; i < workerCount; i++) {
            workerPool.execute(this::workerLoop);
        }
        audit.info("WORKERS_STARTED", workerCount + " worker thread(s) consuming from a queue of capacity " + QUEUE_CAPACITY);
    }

    /** Registers the directory with a {@link WatchService} and starts the watcher thread. */
    public void startWatching() throws IOException {
        if (!workersStarted.get()) {
            throw new IllegalStateException("startWorkers() must be called before startWatching()");
        }
        if (watcherThread != null) {
            return;
        }
        watchService = watchDirectory.getFileSystem().newWatchService();
        watchDirectory.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        watcherThread = new Thread(this::watchLoop, "circularsync-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
        audit.info("WATCH_STARTED", "Watching " + watchDirectory);
    }

    /** Queues every supported file currently in the directory. */
    public int enqueueExistingFiles() throws IOException {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(watchDirectory)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path) && NoticeParserService.isSupported(path)) {
                    if (enqueue(path)) {
                        count++;
                    }
                }
            }
        }
        audit.info("INITIAL_SCAN", count + " existing notice file(s) queued from " + watchDirectory);
        return count;
    }

    /**
     * Blocks until every queued or in-progress file has been handled.
     *
     * @return true if the pipeline drained within the timeout
     */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (idleMonitor) {
            while (inFlight > 0) {
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMillis <= 0) {
                    return false;
                }
                idleMonitor.wait(remainingMillis);
            }
            return true;
        }
    }

    /** Stops watching, lets workers finish queued work, and shuts the pool down. Idempotent. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (watchService != null) {
            try {
                watchService.close(); // unblocks take() with ClosedWatchServiceException
            } catch (IOException e) {
                audit.warn("WATCH_CLOSE", "Error closing watch service: " + e.getMessage());
            }
        }
        if (watcherThread != null) {
            try {
                watcherThread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (workerPool != null) {
            try {
                for (int i = 0; i < workerCount; i++) {
                    // Pills go behind any remaining work, so queued files are still processed.
                    if (!workQueue.offer(POISON_PILL, 5, TimeUnit.SECONDS)) {
                        break;
                    }
                }
                workerPool.shutdown();
                if (!workerPool.awaitTermination(15, TimeUnit.SECONDS)) {
                    workerPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        audit.info("DAEMON_STOPPED", statistics.toString());
    }

    // ------------------------------------------------------------------
    // Producer side
    // ------------------------------------------------------------------

    private void watchLoop() {
        while (!closed.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (ClosedWatchServiceException | InterruptedException e) {
                break;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    audit.warn("WATCH_OVERFLOW", "Events were lost; rescanning " + watchDirectory);
                    try {
                        enqueueExistingFiles();
                    } catch (IOException e) {
                        audit.error("RESCAN_FAILED", e.getMessage());
                    }
                    continue;
                }
                Path relative = (Path) event.context();
                Path file = watchDirectory.resolve(relative);
                if (!NoticeParserService.isSupported(file)) {
                    continue;
                }
                statistics.eventsObserved.incrementAndGet();
                if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                    handleDeletion(file);
                } else if (Files.isRegularFile(file)) {
                    enqueue(file);
                }
            }
            if (!key.reset()) {
                audit.error("WATCH_INVALID", "Watch key invalidated; directory " + watchDirectory + " is no longer accessible");
                break;
            }
        }
    }

    private boolean enqueue(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        if (closed.get() || !queuedPaths.add(normalized)) {
            return false; // already waiting in the queue; one pass will see the latest content
        }
        synchronized (idleMonitor) {
            inFlight++;
        }
        boolean accepted;
        try {
            accepted = workQueue.offer(normalized, 5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            accepted = false;
        }
        if (!accepted) {
            queuedPaths.remove(normalized);
            markDone();
            audit.error("QUEUE_SATURATED", "Dropped " + normalized.getFileName() + "; it will be retried on its next change");
            return false;
        }
        statistics.filesQueued.incrementAndGet();
        return true;
    }

    private void handleDeletion(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        lastProcessed.remove(normalized);
        Optional<Notice> removed = registry.removeBySource(normalized.getFileName().toString());
        removed.ifPresent(notice -> {
            statistics.removed.incrementAndGet();
            audit.info("REMOVED", notice.getId() + " withdrawn because " + normalized.getFileName() + " was deleted");
        });
    }

    // ------------------------------------------------------------------
    // Consumer side
    // ------------------------------------------------------------------

    private void workerLoop() {
        while (true) {
            Path file;
            try {
                file = workQueue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (file == POISON_PILL) {
                return;
            }
            queuedPaths.remove(file);
            try {
                processWithLock(file);
            } catch (RuntimeException e) {
                statistics.ioErrors.incrementAndGet();
                audit.error("WORKER_FAULT", file.getFileName() + ": " + e);
            } finally {
                markDone();
            }
        }
    }

    private void processWithLock(Path file) {
        ReentrantLock pathLock = pathLocks.computeIfAbsent(file, p -> new ReentrantLock());
        pathLock.lock();
        try {
            process(file);
        } finally {
            pathLock.unlock();
        }
    }

    private void process(Path file) {
        String name = file.getFileName().toString();
        try {
            Optional<FileFingerprint> fingerprint = awaitStableFile(file);
            if (fingerprint.isEmpty()) {
                return; // deleted before it could be read
            }
            if (fingerprint.get().equals(lastProcessed.get(file))) {
                statistics.duplicateEvents.incrementAndGet();
                return; // duplicate OS event for a write we already handled
            }
            lastProcessed.put(file, fingerprint.get());

            long started = System.nanoTime();
            Notice notice = parser.parse(file);
            RegistrationResult result = registry.register(notice);
            long micros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started);

            switch (result) {
                case ADDED:
                case REPLACED:
                    statistics.indexed.incrementAndGet();
                    audit.info(result == RegistrationResult.ADDED ? "INDEXED" : "REINDEXED",
                            notice.getId() + " from " + name + " score=" + notice.getPriorityScore()
                                    + " deadline=" + notice.getDeadline() + " (" + micros + " us)");
                    for (Consumer<Notice> listener : indexListeners) {
                        listener.accept(notice);
                    }
                    break;
                case DUPLICATE:
                    statistics.duplicateContent.incrementAndGet();
                    audit.info("DUPLICATE", name + " has the same content as already indexed " + notice.getId());
                    break;
                default:
                    throw new IllegalStateException("Unhandled result " + result);
            }
        } catch (InvalidNoticeFormatException e) {
            statistics.rejectedInvalid.incrementAndGet();
            withdrawStaleVersion(name);
            audit.warn("REJECTED_FORMAT", e.getMessage());
        } catch (ExpiredDeadlineException e) {
            statistics.rejectedExpired.incrementAndGet();
            withdrawStaleVersion(name);
            audit.warn("REJECTED_EXPIRED", e.getMessage());
        } catch (NoSuchFileException e) {
            lastProcessed.remove(file);
        } catch (IOException e) {
            statistics.ioErrors.incrementAndGet();
            lastProcessed.remove(file); // allow a retry on the next event
            audit.error("IO_ERROR", name + ": " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** An edited file that no longer validates must not leave its old version on the board. */
    private void withdrawStaleVersion(String sourceName) {
        registry.removeBySource(sourceName).ifPresent(old ->
                audit.info("WITHDRAWN", old.getId() + " removed because the new version of " + sourceName + " was rejected"));
    }

    /**
     * Polls size and modification time until two consecutive readings agree,
     * so a file that is still being copied into the directory is not parsed
     * half-written.
     */
    private Optional<FileFingerprint> awaitStableFile(Path file) throws IOException, InterruptedException {
        FileFingerprint previous = null;
        for (int attempt = 0; attempt < STABILITY_MAX_POLLS; attempt++) {
            FileFingerprint current;
            try {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                current = new FileFingerprint(attributes.size(), attributes.lastModifiedTime().toMillis());
            } catch (NoSuchFileException e) {
                return Optional.empty();
            }
            if (current.equals(previous) && current.size > 0) {
                return Optional.of(current);
            }
            previous = current;
            Thread.sleep(STABILITY_POLL_MILLIS);
        }
        return Optional.ofNullable(previous);
    }

    private void markDone() {
        synchronized (idleMonitor) {
            inFlight--;
            if (inFlight <= 0) {
                idleMonitor.notifyAll();
            }
        }
    }

    // ------------------------------------------------------------------
    // Accessors and support types
    // ------------------------------------------------------------------

    /** Registers a callback invoked on a worker thread whenever a notice is (re)indexed. */
    public void addIndexListener(Consumer<Notice> listener) {
        indexListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public Statistics getStatistics() {
        return statistics;
    }

    public Path getWatchDirectory() {
        return watchDirectory;
    }

    public int getWorkerCount() {
        return workerCount;
    }

    public int getQueueDepth() {
        return workQueue.size();
    }

    /** Lock-free counters updated by every pipeline thread. */
    public static final class Statistics {
        public final AtomicLong eventsObserved = new AtomicLong();
        public final AtomicLong filesQueued = new AtomicLong();
        public final AtomicLong indexed = new AtomicLong();
        public final AtomicLong duplicateContent = new AtomicLong();
        public final AtomicLong duplicateEvents = new AtomicLong();
        public final AtomicLong rejectedInvalid = new AtomicLong();
        public final AtomicLong rejectedExpired = new AtomicLong();
        public final AtomicLong removed = new AtomicLong();
        public final AtomicLong ioErrors = new AtomicLong();

        @Override
        public String toString() {
            return "queued=" + filesQueued + " indexed=" + indexed + " duplicates=" + duplicateContent
                    + " rejectedFormat=" + rejectedInvalid + " rejectedExpired=" + rejectedExpired
                    + " removed=" + removed + " ioErrors=" + ioErrors + " events=" + eventsObserved
                    + " suppressedEvents=" + duplicateEvents;
        }
    }

    private static final class FileFingerprint {
        private final long size;
        private final long modifiedMillis;

        private FileFingerprint(long size, long modifiedMillis) {
            this.size = size;
            this.modifiedMillis = modifiedMillis;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof FileFingerprint)) {
                return false;
            }
            FileFingerprint other = (FileFingerprint) o;
            return size == other.size && modifiedMillis == other.modifiedMillis;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(size) * 31 + Long.hashCode(modifiedMillis);
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger(1);

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + sequence.getAndIncrement());
            thread.setDaemon(false);
            thread.setUncaughtExceptionHandler((t, e) ->
                    System.err.println("[CircularSync] uncaught failure in " + t.getName() + ": " + e));
            return thread;
        }
    }
}
