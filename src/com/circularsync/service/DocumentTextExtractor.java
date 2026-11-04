package com.circularsync.service;

import com.circularsync.exceptions.InvalidNoticeFormatException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Pulls plain text out of the binary formats circulars are usually published
 * in. Plain-text formats are read by {@link NoticeParserService} directly.
 *
 * <ul>
 *   <li><b>PDF</b> via Apache PDFBox, reading at most {@value #MAX_PDF_PAGES} pages, with text
 *       sorted by position so multi-column letterheads read in order.</li>
 *   <li><b>DOCX</b> with the JDK only: the document part {@code word/document.xml} is read from
 *       the ZIP container and streamed through StAX, emitting paragraph and line breaks.
 *       DTDs and external entities are disabled (no XXE), and the decompressed size is capped
 *       to defeat ZIP bombs.</li>
 * </ul>
 *
 * Stateless and thread-safe.
 */
public final class DocumentTextExtractor {

    public static final int MAX_PDF_PAGES = 40;
    public static final int MAX_TEXT_CHARS = 200_000;
    private static final long MAX_DOCX_XML_BYTES = 20L * 1024 * 1024;

    /** Strong reference keeps the level setting alive; PDFBox font warnings are noise here. */
    private static final Logger PDFBOX_LOGGER = Logger.getLogger("org.apache.pdfbox");
    private static final Logger FONTBOX_LOGGER = Logger.getLogger("org.apache.fontbox");

    static {
        PDFBOX_LOGGER.setLevel(Level.SEVERE);
        FONTBOX_LOGGER.setLevel(Level.SEVERE);
    }

    /** Document formats that need extraction, by lower-case extension. */
    public enum Format {
        PDF, DOCX;

        public static Format of(Path file) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".pdf")) {
                return PDF;
            }
            if (name.endsWith(".docx")) {
                return DOCX;
            }
            return null;
        }
    }

    public String extract(Path file, Format format, String sourceName) throws IOException, InvalidNoticeFormatException {
        String text;
        switch (format) {
            case PDF:
                text = extractPdf(file, sourceName);
                break;
            case DOCX:
                text = extractDocx(file, sourceName);
                break;
            default:
                throw new IllegalArgumentException("Unhandled format " + format);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n').replace(' ', ' ').strip();
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
        }
        if (text.isBlank()) {
            throw new InvalidNoticeFormatException(sourceName, format == Format.PDF
                    ? "the PDF contains no selectable text (it is probably a scanned image; OCR is not supported)"
                    : "the document contains no text");
        }
        return text;
    }

    private static String extractPdf(Path file, String sourceName) throws IOException, InvalidNoticeFormatException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setEndPage(MAX_PDF_PAGES);
            stripper.setLineSeparator("\n");
            return stripper.getText(document);
        } catch (InvalidPasswordException e) {
            throw new InvalidNoticeFormatException(sourceName, "the PDF is password protected", e);
        } catch (IOException e) {
            if (isCorruptionMessage(e)) {
                throw new InvalidNoticeFormatException(sourceName, "the PDF could not be read: " + e.getMessage(), e);
            }
            throw e;
        }
    }

    private static boolean isCorruptionMessage(IOException e) {
        String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("header") || message.contains("trailer") || message.contains("xref")
                || message.contains("not a pdf") || message.contains("end of file") || message.contains("parse");
    }

    private static String extractDocx(Path file, String sourceName) throws IOException, InvalidNoticeFormatException {
        byte[] xml;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry("word/document.xml");
            if (entry == null) {
                throw new InvalidNoticeFormatException(sourceName, "not a Word document (word/document.xml is missing)");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                xml = readCapped(in, MAX_DOCX_XML_BYTES, sourceName);
            }
        } catch (ZipException e) {
            throw new InvalidNoticeFormatException(sourceName, "the .docx file is not a valid Word document", e);
        }

        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        StringBuilder text = new StringBuilder();
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            try {
                boolean inText = false;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String name = reader.getLocalName();
                        if (name.equals("t")) {
                            inText = true;
                        } else if (name.equals("tab")) {
                            text.append('\t');
                        } else if (name.equals("br") || name.equals("cr")) {
                            text.append('\n');
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        String name = reader.getLocalName();
                        if (name.equals("t")) {
                            inText = false;
                        } else if (name.equals("p")) {
                            text.append('\n');
                        } else if (name.equals("tc")) {
                            text.append('\t'); // table cell
                        }
                    } else if (inText && (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)) {
                        text.append(reader.getText());
                    }
                    if (text.length() > MAX_TEXT_CHARS) {
                        break;
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new InvalidNoticeFormatException(sourceName, "the Word document is damaged: " + e.getMessage(), e);
        }
        return text.toString();
    }

    private static byte[] readCapped(InputStream in, long limit, String sourceName) throws IOException, InvalidNoticeFormatException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new InvalidNoticeFormatException(sourceName, "the Word document expands to more than "
                        + limit / (1024 * 1024) + " MB of XML");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
