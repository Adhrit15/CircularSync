package com.circularsync.service;

import com.circularsync.exceptions.ExpiredDeadlineException;
import com.circularsync.exceptions.InvalidNoticeFormatException;
import com.circularsync.model.ClubNotice;
import com.circularsync.model.ExamNotice;
import com.circularsync.model.GeneralNotice;
import com.circularsync.model.Notice;
import com.circularsync.model.NoticeCategory;
import com.circularsync.model.PlacementNotice;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Stateless, thread-safe conversion of notice files into fully processed
 * {@link Notice} objects. One instance is shared by every worker thread.
 *
 * <p>Pipeline for a single file:</p>
 * <ol>
 *   <li>pre-checks on the path: supported extension, non-empty, size limit;</li>
 *   <li>text acquisition: UTF-8 read through a try-with-resources {@link BufferedReader} for
 *       .txt/.md, or {@link DocumentTextExtractor} for .pdf/.docx;</li>
 *   <li>structuring: text already in the header format is used as is, free-form circulars are
 *       converted by {@link CircularStructurer};</li>
 *   <li>category detection from the TYPE header (factory step);</li>
 *   <li>{@code validateFormat()}, {@code parseMetadata()} and
 *       {@code checkDeadline()} on the concrete subclass.</li>
 * </ol>
 */
public class NoticeParserService {

    /** Size limit for plain-text notices. */
    public static final long MAX_FILE_BYTES = 256 * 1024;
    /** Size limit for PDF and Word documents. */
    public static final long MAX_DOCUMENT_BYTES = 15L * 1024 * 1024;
    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".txt", ".md", ".pdf", ".docx");

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();
    private final CircularStructurer structurer = new CircularStructurer();

    private final Supplier<LocalDate> referenceDateSupplier;

    /** Uses the system clock as the reference date. */
    public NoticeParserService() {
        this(LocalDate::now);
    }

    /** @param referenceDateSupplier provides "today"; injectable for deterministic tests */
    public NoticeParserService(Supplier<LocalDate> referenceDateSupplier) {
        this.referenceDateSupplier = Objects.requireNonNull(referenceDateSupplier, "referenceDateSupplier");
    }

    /** @return true if the file name carries one of the supported extensions */
    public static boolean isSupported(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        if (name.startsWith(".") || name.startsWith("~")) {
            return false; // hidden files and editor swap files
        }
        for (String extension : SUPPORTED_EXTENSIONS) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads and fully processes one notice file.
     *
     * @throws InvalidNoticeFormatException if the file is unsupported, empty, too large or malformed
     * @throws ExpiredDeadlineException     if the notice's deadline has already passed
     * @throws IOException                  if the file cannot be read
     */
    public Notice parse(Path file) throws InvalidNoticeFormatException, ExpiredDeadlineException, IOException {
        String sourceName = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        if (!isSupported(file)) {
            throw new InvalidNoticeFormatException(sourceName,
                    "unsupported file type; expected one of " + SUPPORTED_EXTENSIONS);
        }
        long size = Files.size(file);
        if (size == 0) {
            throw new InvalidNoticeFormatException(sourceName, "file is empty");
        }
        long limit = maxBytesFor(sourceName);
        if (size > limit) {
            throw new InvalidNoticeFormatException(sourceName,
                    "file is " + size + " bytes; the limit for this type is " + limit);
        }
        DocumentTextExtractor.Format format = DocumentTextExtractor.Format.of(file);
        if (format == null) {
            return parse(sourceName, readContent(file, sourceName), "TEXT");
        }
        return parse(sourceName, extractor.extract(file, format, sourceName), format.name());
    }

    /** Size limit that applies to a file of this name. */
    public static long maxBytesFor(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".pdf") || lower.endsWith(".docx") ? MAX_DOCUMENT_BYTES : MAX_FILE_BYTES;
    }

    /** Processes notice text that has already been read into memory. */
    public Notice parse(String sourceName, String content) throws InvalidNoticeFormatException, ExpiredDeadlineException {
        return parse(sourceName, content, "TEXT");
    }

    private Notice parse(String sourceName, String content, String formatLabel)
            throws InvalidNoticeFormatException, ExpiredDeadlineException {
        Objects.requireNonNull(content, "content");
        if (content.isBlank()) {
            throw new InvalidNoticeFormatException(sourceName, "file contains only whitespace");
        }
        if (!CircularStructurer.isStructuredAttempt(content)) {
            content = structurer.structure(sourceName, content, formatLabel, referenceDateSupplier.get());
        }
        NoticeCategory category = detectCategory(sourceName, content);
        Notice notice = createNotice(category, sourceName, content, referenceDateSupplier.get());
        notice.validateFormat();
        notice.parseMetadata();
        notice.checkDeadline();
        return notice;
    }

    /** Factory method mapping a category to its concrete subclass. */
    protected Notice createNotice(NoticeCategory category, String sourceName, String content, LocalDate today) {
        switch (category) {
            case EXAM:
                return new ExamNotice(sourceName, content, today);
            case PLACEMENT:
                return new PlacementNotice(sourceName, content, today);
            case CLUB:
                return new ClubNotice(sourceName, content, today);
            case GENERAL:
                return new GeneralNotice(sourceName, content, today);
            default:
                throw new IllegalArgumentException("Unhandled category " + category);
        }
    }

    private static String readContent(Path file, String sourceName) throws IOException, InvalidNoticeFormatException {
        StringBuilder content = new StringBuilder((int) Math.min(Files.size(file), MAX_FILE_BYTES));
        char[] buffer = new char[4096];
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            int read;
            while ((read = reader.read(buffer)) != -1) {
                content.append(buffer, 0, read);
            }
        } catch (MalformedInputException e) {
            throw new InvalidNoticeFormatException(sourceName, "file is not valid UTF-8 text", e);
        }
        if (content.length() > 0 && content.charAt(0) == '﻿') {
            content.deleteCharAt(0); // byte-order mark written by some Windows editors
        }
        return content.toString();
    }

    /** Peeks at the header block for TYPE without fully parsing the notice. */
    private static NoticeCategory detectCategory(String sourceName, String content) throws InvalidNoticeFormatException {
        String[] lines = content.split("\\R");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.equals(Notice.HEADER_TERMINATOR)) {
                break;
            }
            if (line.startsWith("- ") || line.startsWith("* ")) {
                line = line.substring(2).trim();
            }
            line = line.replace("**", "");
            if (line.regionMatches(true, 0, "TYPE", 0, 4)) {
                int colon = line.indexOf(':');
                if (colon == 4 || colon > 4 && line.substring(4, colon).isBlank()) {
                    String declared = line.substring(colon + 1).trim();
                    return NoticeCategory.fromLabel(declared).orElseThrow(() ->
                            new InvalidNoticeFormatException(sourceName,
                                    "unknown TYPE '" + declared + "'; expected EXAM, PLACEMENT, CLUB or GENERAL"));
                }
            }
        }
        throw new InvalidNoticeFormatException(sourceName, "missing TYPE header");
    }
}
