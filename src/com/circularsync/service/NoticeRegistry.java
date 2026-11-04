package com.circularsync.service;

import com.circularsync.model.Notice;
import com.circularsync.model.NoticeCategory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe in-memory index of every active notice.
 *
 * <p>Data structures:</p>
 * <ul>
 *   <li>{@code byId} ({@link ConcurrentHashMap}): primary store, lock-free point lookups;</li>
 *   <li>{@code idBySource} ({@link ConcurrentHashMap}): file name to notice id, used to
 *       replace a notice when its file is edited and to drop it when the file is deleted;</li>
 *   <li>{@code idsByCategory} and {@code keywordIndex}: concurrent inverted indexes whose
 *       value sets come from {@link ConcurrentHashMap#newKeySet()};</li>
 *   <li>{@code ranked} ({@link TreeSet}): the notice board, permanently sorted by
 *       {@link Notice#compareTo(Notice)} (priority score, then deadline).</li>
 * </ul>
 *
 * <p>Concurrency: {@code TreeSet} is not thread-safe, and a registration must
 * update several structures as one unit, so every mutation holds the write
 * side of a {@link ReentrantReadWriteLock}. Ranked views take the read side,
 * allowing any number of concurrent readers. Point lookups and keyword
 * searches go straight to the concurrent maps without locking.</p>
 */
public class NoticeRegistry {

    /** Outcome of {@link #register(Notice)}. */
    public enum RegistrationResult {
        /** A notice not seen before was indexed. */
        ADDED,
        /** The source file was edited; its previous version was replaced. */
        REPLACED,
        /** Identical content is already indexed; nothing changed. */
        DUPLICATE
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "are", "with", "this", "that", "from", "will", "all", "you",
            "your", "have", "has", "not", "must", "shall", "their", "they", "who", "which", "into",
            "been", "any", "may", "per", "our", "its", "also", "only", "can", "should", "via");

    private final ConcurrentHashMap<String, Notice> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idBySource = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<NoticeCategory, Set<String>> idsByCategory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> keywordIndex = new ConcurrentHashMap<>();
    private final TreeSet<Notice> ranked = new TreeSet<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Indexes a processed notice. Atomic with respect to every other
     * registry operation.
     */
    public RegistrationResult register(Notice notice) {
        if (!notice.isProcessed()) {
            throw new IllegalArgumentException("Only processed notices can be registered: " + notice.getSourceName());
        }
        lock.writeLock().lock();
        try {
            if (byId.containsKey(notice.getId())) {
                idBySource.putIfAbsent(notice.getSourceName(), notice.getId());
                return RegistrationResult.DUPLICATE;
            }
            String previousId = idBySource.put(notice.getSourceName(), notice.getId());
            boolean replaced = previousId != null && removeInternal(previousId);
            byId.put(notice.getId(), notice);
            ranked.add(notice);
            idsByCategory.computeIfAbsent(notice.getCategory(), c -> ConcurrentHashMap.newKeySet()).add(notice.getId());
            for (String term : extractTerms(notice)) {
                keywordIndex.computeIfAbsent(term, t -> ConcurrentHashMap.newKeySet()).add(notice.getId());
            }
            return replaced ? RegistrationResult.REPLACED : RegistrationResult.ADDED;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Removes the notice that was loaded from the given file name, if any. */
    public Optional<Notice> removeBySource(String sourceName) {
        lock.writeLock().lock();
        try {
            String id = idBySource.remove(sourceName);
            if (id == null) {
                return Optional.empty();
            }
            Notice notice = byId.get(id);
            removeInternal(id);
            return Optional.ofNullable(notice);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Caller must hold the write lock. */
    private boolean removeInternal(String id) {
        Notice old = byId.remove(id);
        if (old == null) {
            return false;
        }
        ranked.remove(old);
        Set<String> categoryIds = idsByCategory.get(old.getCategory());
        if (categoryIds != null) {
            categoryIds.remove(id);
        }
        for (String term : extractTerms(old)) {
            Set<String> ids = keywordIndex.get(term);
            if (ids != null) {
                ids.remove(id);
                if (ids.isEmpty()) {
                    keywordIndex.remove(term, ids);
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** Exact id lookup, or a unique case-insensitive id prefix. Lock-free. */
    public Optional<Notice> find(String idOrPrefix) {
        Notice exact = byId.get(idOrPrefix);
        if (exact != null) {
            return Optional.of(exact);
        }
        String prefix = idOrPrefix.toUpperCase(Locale.ROOT);
        Notice match = null;
        for (Map.Entry<String, Notice> entry : byId.entrySet()) {
            if (entry.getKey().toUpperCase(Locale.ROOT).startsWith(prefix)) {
                if (match != null) {
                    return Optional.empty(); // ambiguous
                }
                match = entry.getValue();
            }
        }
        return Optional.ofNullable(match);
    }

    /** Snapshot of the whole board, highest priority first. */
    public List<Notice> ranked() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(ranked);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** The {@code limit} highest-priority notices. */
    public List<Notice> top(int limit) {
        List<Notice> result = new ArrayList<>(Math.max(0, limit));
        lock.readLock().lock();
        try {
            for (Notice notice : ranked) {
                if (result.size() >= limit) {
                    break;
                }
                result.add(notice);
            }
        } finally {
            lock.readLock().unlock();
        }
        return result;
    }

    /** Notices of one category, in board order. */
    public List<Notice> byCategory(NoticeCategory category) {
        return sortedView(idsByCategory.getOrDefault(category, Collections.emptySet()));
    }

    /**
     * Keyword search with AND semantics over titles, priority keys, header
     * values and body text.
     */
    public List<Notice> search(String query) {
        Set<String> terms = tokenize(query);
        if (terms.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> matches = null;
        for (String term : terms) {
            Set<String> ids = keywordIndex.getOrDefault(term, Collections.emptySet());
            if (matches == null) {
                matches = new HashSet<>(ids);
            } else {
                matches.retainAll(ids);
            }
            if (matches.isEmpty()) {
                break;
            }
        }
        return sortedView(matches);
    }

    /**
     * Notices whose deadline falls within {@code days} days of their reference
     * date, ordered strictly by deadline (soonest first, then score) using a
     * {@link PriorityQueue} as a deadline min-heap.
     */
    public List<Notice> dueWithin(long days) {
        PriorityQueue<Notice> heap = new PriorityQueue<>(
                Comparator.comparing(Notice::getDeadline)
                        .thenComparing(Comparator.comparingInt(Notice::getPriorityScore).reversed())
                        .thenComparing(Notice::getId));
        for (Notice notice : byId.values()) {
            if (notice.getDaysUntilDeadline() <= days) {
                heap.add(notice);
            }
        }
        List<Notice> ordered = new ArrayList<>(heap.size());
        while (!heap.isEmpty()) {
            ordered.add(heap.poll());
        }
        return ordered;
    }

    /** Count of active notices per category (every category present, possibly 0). */
    public Map<NoticeCategory, Integer> countsByCategory() {
        Map<NoticeCategory, Integer> counts = new EnumMap<>(NoticeCategory.class);
        for (NoticeCategory category : NoticeCategory.values()) {
            counts.put(category, idsByCategory.getOrDefault(category, Collections.emptySet()).size());
        }
        return counts;
    }

    public int size() {
        return byId.size();
    }

    public int indexedTermCount() {
        return keywordIndex.size();
    }

    private List<Notice> sortedView(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        TreeSet<Notice> sorted = new TreeSet<>();
        for (String id : ids) {
            Notice notice = byId.get(id);
            if (notice != null) {
                sorted.add(notice);
            }
        }
        return new ArrayList<>(sorted);
    }

    private static Set<String> extractTerms(Notice notice) {
        Set<String> terms = new HashSet<>(notice.getPriorityKeys());
        terms.addAll(tokenize(notice.getTitle()));
        terms.addAll(tokenize(notice.getBody()));
        for (Map.Entry<String, String> header : notice.getHeaders().entrySet()) {
            String key = header.getKey();
            if (!key.equals("DATE") && !key.equals("DEADLINE") && !key.endsWith("_DATE")) {
                terms.addAll(tokenize(header.getValue()));
            }
        }
        terms.add(notice.getCategory().name().toLowerCase(Locale.ROOT));
        return terms;
    }

    /** Lower-cases and splits on anything that is not a letter, digit or hyphen. */
    static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null) {
            return tokens;
        }
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}-]+")) {
            String token = raw.replaceAll("^-+|-+$", "");
            if (token.length() >= 2 && !STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
