import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * Downloads the third-party jars CircularSync needs (Apache PDFBox for PDF
 * text extraction) from Maven Central into lib/, verifying each against a
 * pinned SHA-256 checksum. Jars already present with the right checksum are
 * left alone, so this is cheap to run on every build.
 *
 * Usage: java tools/FetchLibs.java [targetDirectory]
 */
public class FetchLibs {

    private static final String REPOSITORY = "https://repo1.maven.org/maven2/";

    /** Maven path, pinned SHA-256. */
    private static final String[][] LIBRARIES = {
            {"org/apache/pdfbox/pdfbox/3.0.3/pdfbox-3.0.3.jar",
                    "5be38d2ec81691b05d535eb720de4dc566c5d07e5a04731fa00668d153a8b4a6"},
            {"org/apache/pdfbox/fontbox/3.0.3/fontbox-3.0.3.jar",
                    "65690c3f39b04a14d12c17f4998c15186ce877d3e2ec222c708577e3cc028030"},
            {"org/apache/pdfbox/pdfbox-io/3.0.3/pdfbox-io-3.0.3.jar",
                    "123ea3187b497c54e661d50c3c867479bf77668ff450e50710c658f2bb4687ba"},
            {"commons-logging/commons-logging/1.3.4/commons-logging-1.3.4.jar",
                    "bc2dfe32f1ef06509e6a065144c1adf7b420eabf11a87f30bd127f8faa332016"},
    };

    public static void main(String[] args) throws Exception {
        Path target = Paths.get(args.length > 0 ? args[0] : "lib");
        Files.createDirectories(target);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        boolean failed = false;
        for (String[] library : LIBRARIES) {
            String mavenPath = library[0];
            String pinned = library[1];
            Path jar = target.resolve(mavenPath.substring(mavenPath.lastIndexOf('/') + 1));
            if (Files.exists(jar) && sha256(jar).equals(pinned)) {
                continue;
            }
            System.out.println("Downloading " + jar.getFileName() + " from Maven Central...");
            Path temp = Files.createTempFile(target, "download-", ".part");
            try {
                HttpResponse<InputStream> response = http.send(
                        HttpRequest.newBuilder(URI.create(REPOSITORY + mavenPath)).timeout(Duration.ofMinutes(2)).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + mavenPath);
                }
                try (InputStream body = response.body()) {
                    Files.copy(body, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                String actual = sha256(temp);
                if (!actual.equals(pinned)) {
                    throw new IOException("checksum mismatch for " + jar.getFileName()
                            + ": expected " + pinned + " but got " + actual);
                }
                Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("  FAILED: " + e.getMessage());
                failed = true;
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        if (failed) {
            System.exit(1);
        }
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
