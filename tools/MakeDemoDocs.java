import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates the free-form PDF and Word demo circulars in demo/. They are
 * written like real college letters (no CircularSync headers) to show the
 * extraction and inference pipeline.
 *
 * Usage: java -cp "lib/*" tools/MakeDemoDocs.java [demoDirectory]
 */
public class MakeDemoDocs {

    public static void main(String[] args) throws IOException {
        Path dir = Paths.get(args.length > 0 ? args[0] : "demo");
        Files.createDirectories(dir);

        writePdf(dir.resolve("5_scholarship_circular.pdf"), new String[][]{
                {"B", "NORTHFIELD INSTITUTE OF ENGINEERING AND TECHNOLOGY"},
                {"", "Office of the Dean, Student Welfare"},
                {"", ""},
                {"", "Ref: NIET/DSW/2026/287"},
                {"", "Date: 04/11/2026"},
                {"", ""},
                {"B", "CIRCULAR"},
                {"", ""},
                {"", "Sub: Submission of applications for the Merit-cum-Means Scholarship 2026-27"},
                {"", ""},
                {"", "All eligible undergraduate students are informed that applications for the Merit-cum-Means "
                        + "Scholarship for the academic year 2026-27 are now open. Students whose annual family income "
                        + "is below the prescribed limit and who have secured at least 7.5 CGPA in the previous year may apply."},
                {"", ""},
                {"", "The completed application form, along with the income certificate, mark sheets and a bonafide "
                        + "certificate, must be submitted to the Student Welfare Office on or before 18th November 2026. "
                        + "Incomplete applications will not be considered. Submission of documents is mandatory for "
                        + "renewal candidates as well."},
                {"", ""},
                {"", "Shortlisted students will be called for document verification on 25 November 2026."},
                {"", ""},
                {"", "Sd/-"},
                {"", "Dean, Student Welfare"},
        });

        writeDocx(dir.resolve("6_placement_drive.docx"),
                "NORTHFIELD INSTITUTE OF ENGINEERING AND TECHNOLOGY",
                "Training and Placement Cell",
                "Date: 04-11-2026",
                "NOTICE",
                "Subject: Campus Recruitment Drive by Fabrikam Software Solutions Pvt Ltd",
                "The Training and Placement Cell is pleased to announce a campus recruitment drive by "
                        + "Fabrikam Software Solutions Pvt Ltd for final year students of the 2027 batch.",
                "Company: Fabrikam Software Solutions Pvt Ltd",
                "Role: Associate Software Engineer",
                "Eligibility: B.Tech CSE, IT and ECE with CGPA 7.0 and above, no active backlogs",
                "Package: 12 LPA",
                "Interested students must register on the placement portal latest by 10.11.2026. "
                        + "The online assessment and interviews will be conducted on 17/11/2026 in the Placement Block.",
                "Students are advised to keep an updated resume ready. This is a dream company drive.",
                "Placement Officer");

        System.out.println("Demo documents written to " + dir.toAbsolutePath());
    }

    private static void writePdf(Path file, String[][] paragraphs) throws IOException {
        PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        float fontSize = 11;
        float leading = 15;
        float margin = 60;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            float width = page.getMediaBox().getWidth() - 2 * margin;
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.newLineAtOffset(margin, page.getMediaBox().getHeight() - margin);
                for (String[] paragraph : paragraphs) {
                    PDType1Font font = paragraph[0].equals("B") ? bold : regular;
                    content.setFont(font, fontSize);
                    if (paragraph[1].isEmpty()) {
                        content.newLineAtOffset(0, -leading / 2);
                        continue;
                    }
                    for (String line : wrap(paragraph[1], font, fontSize, width)) {
                        content.showText(line);
                        content.newLineAtOffset(0, -leading);
                    }
                }
                content.endText();
            }
            document.save(file.toFile());
        }
    }

    private static List<String> wrap(String text, PDType1Font font, float size, float width) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (font.getStringWidth(candidate) / 1000 * size > width && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static void writeDocx(Path file, String... paragraphs) throws IOException {
        StringBuilder body = new StringBuilder();
        for (String paragraph : paragraphs) {
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                    .append(paragraph.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                    .append("</w:t></w:r></w:p>");
        }
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            entry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "</Types>");
            entry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                    + "</Relationships>");
            entry(zip, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                    + body + "</w:body></w:document>");
        }
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
