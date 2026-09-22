package net.thevpc.nuts.toolbox.mvn.subcommands;

import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.io.NIOException;
import net.thevpc.nuts.io.NIOUtils;
import net.thevpc.nuts.io.NOut;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.util.NException;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NObjectElementBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * CLI command to compare two JAR files for binary compatibility (ignoring META-INF).
 * Outputs differing entries when not compatible.
 */
public class MvnJarCompareSubCommand {


    public MvnJarCompareSubCommand() {
    }

    public int run(String[] args) {
        NCmdLine cmd = NCmdLine.of(args);
        List<String> repoUrls = new ArrayList<>();
        List<String> jarSpecs = new ArrayList<>();
        cmd.matcher()
                .when("-r", "--repo").asEntry(a -> repoUrls.add(a.stringValue()))
                .whenNonOption().asArg(a -> jarSpecs.add(a.stringValue()))
                .withDefaults()
                .requireAll();

        if (jarSpecs.size() != 2) {
            NOut.println(NMsg.ofC("Usage: nmvn diff-jar [options] <jar1> <jar2>"));
            NOut.println(NMsg.ofC("  Options:"));
            NOut.println(NMsg.ofC("    -r, --repo <url>   Add a repository URL for resolving GAVs (can be repeated)"));
            NOut.println(NMsg.ofC("    -j, --json         Output result as JSON"));
            NOut.println(NMsg.ofC("  Each jar specifier can be a file path, HTTP URL, or GAV (groupId:artifactId:version)"));
            return 1;
        }

        try {
            // Resolve each specifier to a local file path
            NPath file1 = resolveJarSpec(repoUrls, jarSpecs.get(0));
            NPath file2 = resolveJarSpec(repoUrls, jarSpecs.get(1));

            if (file1 == null || !file1.exists()) {
                NOut.println(NMsg.ofC("Failed to resolve first jar specifier: %s", jarSpecs.get(0)));
                return 1;
            }
            if (file2 == null || !file2.exists()) {
                NOut.println(NMsg.ofC("Failed to resolve second jar specifier: %s", jarSpecs.get(1)));
                return 1;
            }

            // Get differing entries (excluding META-INF)
            List<String> differing = getDifferingEntries(file1, file2);
            boolean compatible = differing.isEmpty();

            if (!NOut.isPlain()) {
                // JSON output: list of differing entries, empty if compatible
                NArrayElementBuilder diffArr = NElement.ofArrayBuilder();
                for (String s : differing) {
                    diffArr.add(NElement.ofString(s));
                }
                NObjectElementBuilder objBuilder = NElement.ofObjectBuilder();
                objBuilder.set("compatible", NElement.ofBoolean(compatible));
                objBuilder.set("differingEntries", diffArr.build());
                NOut.println(objBuilder.build());
            } else {
                if (compatible) {
                    NOut.println(NMsg.ofStyled("JARS ARE BINARY COMPATIBLE (ignoring META-INF)", NTextStyle.success()));
                } else {
                    NOut.println(NMsg.ofStyled("JARS ARE NOT BINARY COMPATIBLE (ignoring META-INF)", NTextStyle.danger()));
                    NOut.println(NMsg.ofStyled("Differing entries (first 20):", NTextStyle.primary4()));
                    int limit = Math.min(differing.size(), 20);
                    for (int i = 0; i < limit; i++) {
                        NOut.println(NMsg.ofC("  %s", differing.get(i)));
                    }
                    if (differing.size() > 20) {
                        NOut.println(NMsg.ofC("  ... and %d more", differing.size() - 20));
                    }
                }
            }
            return compatible ? 0 : 1;
        } catch (Exception e) {
            NOut.println(NMsg.ofStyled("Error: " + e.getMessage(), NTextStyle.danger()));
            return 1;
        }
    }

    /**
     * Resolves a jar specifier to a local file path.
     * Specifier can be:
     * - existing file path
     * - HTTP URL
     * - GAV (groupId:artifactId:version) -> resolved from given repositories or local m2 repository
     */
    private NPath resolveJarSpec(List<String> repoUrls, String spec) {
        // Check if it's an existing file
        NPath file = NPath.of(spec);
        if (file.exists() && file.isFile()) {
            return file;
        }

        // Check if it's a URL
        if (spec.toLowerCase().startsWith("http://") || spec.toLowerCase().startsWith("https://")) {
            return downloadUrlToTempFile(spec);
        }

        // Assume it's a GAV: groupId:artifactId:version
        // Parse GAV
        String[] parts = spec.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid GAV format: " + spec);
        }
        String groupId = parts[0];
        String artifactId = parts[1];
        String version = parts[2];

        // Try given repositories first
        for (String repoUrl : repoUrls) {
            NPath downloaded = tryDownloadFromRepo(repoUrl, groupId, artifactId, version);
            if (downloaded != null && downloaded.exists()) {
                return downloaded;
            }
        }

        // Fallback to local m2 repository
        String m2Repo = System.getProperty("user.home") + "/.m2/repository";
        String path = m2Repo + "/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/"
                + artifactId + "-" + version + ".jar";
        NPath gavFile = NPath.of(path);
        if (gavFile.exists() && gavFile.isFile()) {
            return gavFile;
        }

        throw new NIOException(NMsg.ofC("GAV not found in any repository or local m2: %s", spec));
    }

    /**
     * Tries to download a jar from a repository URL constructed as:
     * <repo>/<groupId>/<artifactId>/<version>/<artifactId>-<version>.jar
     * Returns the downloaded file if successful, null otherwise.
     */
    private NPath tryDownloadFromRepo(String repoUrl, String groupId, String artifactId, String version) {
        try {
            // Ensure repoUrl ends with slash
            String base = repoUrl.endsWith("/") ? repoUrl : repoUrl + "/";
            String path = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".jar";
            String urlStr = base + path;
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (InputStream in = conn.getInputStream()) {
                    NPath tempDir = NPath.ofTempFolder();
                    NPath tempFile = tempDir.resolve("jarcompare-" + System.nanoTime() + ".jar");
                    Files.copy(in, tempFile.toPath().get(), StandardCopyOption.REPLACE_EXISTING);
                    return tempFile;
                }
            } else {
                // Not found
                return null;
            }
        } catch (IOException e) {
            // Treat as not found
            return null;
        }
    }

    /**
     * Downloads a URL to a temporary file.
     */
    private NPath downloadUrlToTempFile(String urlStr) {
        URL url = null;
        try {
            url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            try (InputStream in = conn.getInputStream()) {
                NPath tempDir = NPath.ofTempFolder();
                NPath tempFile = tempDir.resolve("jarcompare-" + System.nanoTime() + ".jar");
                Files.copy(in, tempFile.toPath().get(), StandardCopyOption.REPLACE_EXISTING);
                return tempFile;
            } finally {
                conn.disconnect();
            }
        } catch (IOException e) {
            throw NException.ofSafeIOException(e);
        }
    }

    /**
     * Compares two JAR files and returns a list of differing entry names (excluding META-INF).
     * The list is sorted and limited to first 20 entries for brevity.
     * If the list is empty, the jars are binary compatible (ignoring META-INF).
     */
    private List<String> getDifferingEntries(NPath file1, NPath file2) {
        List<String> differing = new ArrayList<>();
        try (ZipFile zip1 = new ZipFile(file1.toPath().get().toFile());
             ZipFile zip2 = new ZipFile(file2.toPath().get().toFile())) {
            // Get entry names excluding META-INF
            Set<String> names1 = new TreeSet<>();
            Set<String> names2 = new TreeSet<>();
            for (ZipEntry e : java.util.Collections.list(zip1.entries())) {
                String name = e.getName();
                if (!name.startsWith("META-INF/")) {
                    names1.add(name);
                }
            }
            for (ZipEntry e : java.util.Collections.list(zip2.entries())) {
                String name = e.getName();
                if (!name.startsWith("META-INF/")) {
                    names2.add(name);
                }
            }
            // Entries only in zip1
            for (String name : names1) {
                if (!names2.contains(name)) {
                    differing.add(name);
                    if (differing.size() >= 20) break;
                }
            }
            // Entries only in zip2
            if (differing.size() < 20) {
                for (String name : names2) {
                    if (!names1.contains(name)) {
                        differing.add(name);
                        if (differing.size() >= 20) break;
                    }
                }
            }
            // Entries in both - compare content
            if (differing.size() < 20) {
                for (String name : names1) {
                    if (names2.contains(name)) {
                        ZipEntry entry1 = zip1.getEntry(name);
                        ZipEntry entry2 = zip2.getEntry(name);
                        if (entry1 == null || entry2 == null) {
                            // Should not happen because we checked name in both sets, but just in case
                            continue;
                        }
                        try (InputStream in1 = zip1.getInputStream(entry1);
                             InputStream in2 = zip2.getInputStream(entry2)) {
                            if (!NIOUtils.compareContent(in1, in2)) {
                                differing.add(name);
                                if (differing.size() >= 20) break;
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw NException.ofSafeIOException(e);
        }
        return differing;
    }


}