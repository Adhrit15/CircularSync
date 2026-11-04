package com.circularsync.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asynchronous, append-only audit trail backed by the NIO {@link Files} API.
 *
 * <p>Producers (watcher and worker threads) call {@link #log} which only
 * formats the record and offers it to an in-memory {@link BlockingQueue}; it
 * never touches the disk. A single dedicated writer thread drains the queue,
 * writes records in batches through one long-lived
 * {@link Files#newBufferedWriter} opened in a try-with-resources block, and
 * flushes after each batch. Disk latency therefore never stalls parsing, and
 * because only one thread writes, lines are never interleaved.</p>
 */
public final class AuditLogger implements AutoCloseable {

    /** Severity of an audit record. */
    public enum Level { INFO, WARN, ERROR }

    public static final String AUDIT_FILE_NAME = "audit.log";

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final int QUEUE_CAPACITY = 10_000;
    /** Identity-compared sentinel that tells the writer thread to finish. */
    private static final String END_OF_STREAM = new String("<end-of-audit-stream>");

    private final Path logDirectory;
    private final Path logFile;
    private final BlockingQueue<String> pending = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final Thread writerThread;

    public AuditLogger(Path logDirectory) throws IOException {
        this.logDirectory = Files.createDirectories(logDirectory.toAbsolutePath().normalize());
        this.logFile = this.logDirectory.resolve(AUDIT_FILE_NAME);
        this.writerThread = new Thread(this::drainLoop, "circularsync-audit-writer");
        this.writerThread.start();
    }

    /**
     * Queues one audit record. Never blocks for more than a few milliseconds;
     * if the backlog is saturated the record is counted as dropped instead of
     * stalling a worker.
     */
    public void log(Level level, String event, String detail) {
        if (closed.get()) {
            dropped.incrementAndGet();
            return;
        }
        String line = String.format("%s | %-5s | %-26s | %-16s | %s",
                LocalDateTime.now().format(TIMESTAMP), level, Thread.currentThread().getName(),
                event, detail.replace('\n', ' ').replace('\r', ' '));
        try {
            if (!pending.offer(line, 50, TimeUnit.MILLISECONDS)) {
                dropped.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dropped.incrementAndGet();
        }
    }

    public void info(String event, String detail) {
        log(Level.INFO, event, detail);
    }

    public void warn(String event, String detail) {
        log(Level.WARN, event, detail);
    }

    public void error(String event, String detail) {
        log(Level.ERROR, event, detail);
    }

    /**
     * Writes a complete snapshot file (for example the ranked notice board)
     * atomically: the content goes to a temporary sibling first and is then
     * moved over the target, so readers never observe a half-written file.
     */
    public Path writeSnapshot(String fileName, List<String> lines) throws IOException {
        Path target = logDirectory.resolve(fileName);
        Path temp = Files.createTempFile(logDirectory, fileName, ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                for (String line : lines) {
                    writer.write(line);
                    writer.newLine();
                }
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return target;
    }

    private void drainLoop() {
        try (BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
            boolean finished = false;
            while (!finished) {
                String line = pending.take();
                // Batch everything already queued, then flush once.
                while (line != null) {
                    if (line == END_OF_STREAM) {
                        finished = true;
                        break;
                    }
                    writer.write(line);
                    writer.newLine();
                    written.incrementAndGet();
                    line = pending.poll();
                }
                writer.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.err.println("[CircularSync] audit log unavailable at " + logFile + ": " + e.getMessage());
        }
    }

    /** Flushes every queued record and stops the writer thread. Idempotent. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            pending.put(END_OF_STREAM);
            writerThread.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Path getLogFile() {
        return logFile;
    }

    public Path getLogDirectory() {
        return logDirectory;
    }

    public long getWrittenCount() {
        return written.get();
    }

    public long getDroppedCount() {
        return dropped.get();
    }
}
