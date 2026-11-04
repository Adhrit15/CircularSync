package com.circularsync.web;

import com.circularsync.daemon.ProcessingEvent;
import com.circularsync.daemon.ProcessingListener;
import com.circularsync.model.Notice;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks every file that enters the incoming directory, whether uploaded
 * through the portal or dropped into the folder directly, and the outcome the
 * pipeline reached for it. Also keeps a short activity feed.
 *
 * <p>Writes arrive from HTTP threads (uploads) and from watcher and worker
 * threads (outcomes); volume is tiny, so every method simply synchronizes on
 * the tracker and readers receive immutable snapshots.</p>
 */
public final class FileActivityTracker implements ProcessingListener {

    /** Where a tracked file came from. */
    public enum Source { UPLOAD, DEMO, FOLDER }

    /** Lifecycle status shown in the Uploads tab. */
    public enum Status { RECEIVED, INDEXED, REINDEXED, DUPLICATE, REJECTED_FORMAT, REJECTED_EXPIRED, REMOVED, IO_ERROR }

    private static final int MAX_FILES = 200;
    private static final int MAX_ACTIVITY = 120;

    private final Deque<FileRecord> files = new ArrayDeque<>();
    private final Map<String, FileRecord> latestByName = new HashMap<>();
    private final Deque<ActivityEntry> activity = new ArrayDeque<>();
    private long sequence;

    /** Called by the portal just before an uploaded file is moved into the incoming directory. */
    public synchronized void recordArrival(String fileName, long size, Source source) {
        FileRecord record = new FileRecord(++sequence, fileName, source, size);
        files.addFirst(record);
        latestByName.put(fileName, record);
        trim();
        addActivity("info", source == Source.UPLOAD ? "UPLOADED" : "DEMO_DROP", fileName,
                (source == Source.UPLOAD ? "Uploaded through the portal (" : "Demo file dropped (") + size + " bytes)");
    }

    @Override
    public synchronized void onProcessed(ProcessingEvent event) {
        String name = event.getFileName();
        FileRecord record = latestByName.get(name);
        Status status = Status.valueOf(event.getOutcome().name());

        if (status == Status.REMOVED) {
            // A pending upload that replaces an existing file can produce a delete event first; ignore it.
            if (record != null && record.status != Status.RECEIVED) {
                record.update(status, event);
            }
        } else {
            // A fresh outcome for a file that already reached one means the file changed on disk.
            if (record == null || record.status != Status.RECEIVED) {
                record = new FileRecord(++sequence, name, Source.FOLDER, Math.max(0, event.getFileSize()));
                files.addFirst(record);
                latestByName.put(name, record);
                trim();
            }
            record.update(status, event);
        }

        String level;
        switch (status) {
            case REJECTED_FORMAT:
            case REJECTED_EXPIRED:
                level = "warn";
                break;
            case IO_ERROR:
                level = "error";
                break;
            default:
                level = "info";
        }
        addActivity(level, status.name(), name, event.getMessage());
    }

    public synchronized List<FileView> files() {
        List<FileView> snapshot = new ArrayList<>(files.size());
        for (FileRecord record : files) {
            snapshot.add(record.view());
        }
        return snapshot;
    }

    public synchronized List<ActivityEntry> activity() {
        return new ArrayList<>(activity);
    }

    private void addActivity(String level, String outcome, String fileName, String message) {
        activity.addFirst(new ActivityEntry(LocalDateTime.now(), level, outcome, fileName, message));
        while (activity.size() > MAX_ACTIVITY) {
            activity.removeLast();
        }
    }

    private void trim() {
        while (files.size() > MAX_FILES) {
            FileRecord oldest = files.removeLast();
            latestByName.remove(oldest.fileName, oldest);
        }
    }

    // ------------------------------------------------------------------

    private static final class FileRecord {
        final long id;
        final String fileName;
        final Source source;
        final LocalDateTime receivedAt = LocalDateTime.now();
        long size;
        Status status = Status.RECEIVED;
        String noticeId;
        String category;
        int score = -1;
        String message = "Waiting for a worker thread";
        LocalDateTime updatedAt = receivedAt;

        FileRecord(long id, String fileName, Source source, long size) {
            this.id = id;
            this.fileName = fileName;
            this.source = source;
            this.size = size;
        }

        void update(Status newStatus, ProcessingEvent event) {
            status = newStatus;
            message = event.getMessage();
            updatedAt = event.getTimestamp();
            if (event.getFileSize() >= 0) {
                size = event.getFileSize();
            }
            Notice notice = event.getNotice();
            if (notice != null) {
                noticeId = notice.getId();
                category = notice.getCategory().name();
                score = notice.getPriorityScore();
            }
        }

        FileView view() {
            return new FileView(id, fileName, source, size, receivedAt, status, noticeId, category, score, message, updatedAt);
        }
    }

    /** Immutable snapshot of a tracked file. */
    public static final class FileView {
        public final long id;
        public final String fileName;
        public final Source source;
        public final long size;
        public final LocalDateTime receivedAt;
        public final Status status;
        public final String noticeId;
        public final String category;
        public final int score;
        public final String message;
        public final LocalDateTime updatedAt;

        FileView(long id, String fileName, Source source, long size, LocalDateTime receivedAt, Status status,
                 String noticeId, String category, int score, String message, LocalDateTime updatedAt) {
            this.id = id;
            this.fileName = fileName;
            this.source = source;
            this.size = size;
            this.receivedAt = receivedAt;
            this.status = status;
            this.noticeId = noticeId;
            this.category = category;
            this.score = score;
            this.message = message;
            this.updatedAt = updatedAt;
        }
    }

    /** One line of the live activity feed. */
    public static final class ActivityEntry {
        public final LocalDateTime time;
        public final String level;
        public final String outcome;
        public final String fileName;
        public final String message;

        ActivityEntry(LocalDateTime time, String level, String outcome, String fileName, String message) {
            this.time = time;
            this.level = level;
            this.outcome = outcome;
            this.fileName = fileName;
            this.message = message;
        }
    }
}
