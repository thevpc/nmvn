package net.thevpc.nuts.toolbox.mvn;

import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.artifact.NDependencyFilter;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.command.NSearch;
import net.thevpc.nuts.core.NSession;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class NMvnClasspathCompatibilityTest {

    @Before
    public void setup() {
        Nuts.require();
    }

    //@Test
    public void testNutsNmvnExecutionAndClasspath() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("nuts", "-Zy", "exec", "--show-command", "net.thevpc.nmvn:nmvn", "-v");
        pb.redirectErrorStream(true);
        Process p = pb.start();

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        int exitCode = p.waitFor();
        Assert.assertEquals("nuts exec nmvn -v should exit with 0", 0, exitCode);

        String fullOutput = String.join("\n", lines);
        Assert.assertTrue("Output should indicate Apache Maven 3.6.3", fullOutput.contains("Apache Maven 3.6.3"));

        // Extract classpath from output
        String nutsPathLine = null;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).trim();
            if (l.equals("--nuts-path") && i + 1 < lines.size()) {
                nutsPathLine = lines.get(i + 1).trim();
                break;
            }
        }
        Assert.assertNotNull("Command output must contain --nuts-path", nutsPathLine);

        List<String> deps = Arrays.asList(nutsPathLine.split(";"));
        Map<String, String> depVersions = new HashMap<>();
        for (String dep : deps) {
            String[] parts = dep.split("#");
            String artifact = parts[0].split(":")[1];
            String version = parts.length > 1 ? parts[1] : "";
            depVersions.put(artifact, version);
        }

        // Verify Maven 3.6.3 core libraries are resolved and 2.0.9 is NOT resolved
        Assert.assertEquals("maven-model must be 3.6.3", "3.6.3", depVersions.get("maven-model"));
        Assert.assertEquals("maven-plugin-api must be 3.6.3", "3.6.3", depVersions.get("maven-plugin-api"));
        Assert.assertEquals("maven-core must be 3.6.3", "3.6.3", depVersions.get("maven-core"));
        Assert.assertEquals("maven-embedder must be 3.6.3", "3.6.3", depVersions.get("maven-embedder"));

        // Verify commons-io is 2.5 (from wagon-http-shared), not 2.6
        Assert.assertEquals("commons-io must be 2.5", "2.5", depVersions.get("commons-io"));

        // Verify test-scoped dependencies and excluded dependencies are absent
        Assert.assertFalse("junit should not be in runtime classpath", depVersions.containsKey("junit"));
        Assert.assertFalse("plexus-container-default should be excluded", depVersions.containsKey("plexus-container-default"));
        Assert.assertFalse("classworlds should be excluded", depVersions.containsKey("classworlds"));
    }

    //@Test
    public void testClasspathMatchesMavenRuntimeDependencies() throws Exception {
        // 1. Get Maven runtime classpath
        ProcessBuilder pbMvn = new ProcessBuilder("mvn", "dependency:build-classpath", "-DincludeScope=runtime");
        pbMvn.directory(new File("."));
        pbMvn.redirectErrorStream(true);
        Process pMvn = pbMvn.start();

        Set<String> mavenJars = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(pMvn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("/.m2/repository") || line.contains(".jar")) {
                    for (String item : line.split(File.pathSeparator)) {
                        String trimmed = item.trim();
                        if (trimmed.endsWith(".jar")) {
                            mavenJars.add(new File(trimmed).getName());
                        }
                    }
                }
            }
        }
        int exitCodeMvn = pMvn.waitFor();
        Assert.assertEquals(0, exitCodeMvn);
        Assert.assertFalse("Maven runtime classpath should not be empty", mavenJars.isEmpty());

        // 2. Get Nuts runtime classpath
        ProcessBuilder pbNuts = new ProcessBuilder("nuts", "-Zy", "exec", "--show-command", "net.thevpc.nmvn:nmvn", "-v");
        pbNuts.redirectErrorStream(true);
        Process pNuts = pbNuts.start();

        Set<String> nutsJars = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(pNuts.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("-classpath")) {
                    // Next line or current line contains the classpath
                    String cpLine = reader.readLine();
                    if (cpLine != null) {
                        for (String item : cpLine.trim().split(File.pathSeparator)) {
                            String trimmed = item.trim();
                            if (trimmed.endsWith(".jar")) {
                                String jarName = new File(trimmed).getName();
                                // Exclude the application jar itself
                                if (!jarName.startsWith("nmvn-1.0.0.0.jar")) {
                                    nutsJars.add(jarName);
                                }
                            }
                        }
                    }
                }
            }
        }
        int exitCodeNuts = pNuts.waitFor();
        Assert.assertEquals(0, exitCodeNuts);
        Assert.assertFalse("Nuts runtime classpath should not be empty", nutsJars.isEmpty());

        // 3. Compare sets
        Set<String> inNutsNotMaven = new TreeSet<>(nutsJars);
        inNutsNotMaven.removeAll(mavenJars);

        Set<String> inMavenNotNuts = new TreeSet<>(mavenJars);
        inMavenNotNuts.removeAll(nutsJars);

        Assert.assertTrue(
                "Nuts classpath has extra jars not in Maven: " + inNutsNotMaven
                        + " ; Maven has extra jars not in Nuts: " + inMavenNotNuts,
                inNutsNotMaven.isEmpty() && inMavenNotNuts.isEmpty()
        );
        Assert.assertEquals("Classpath jar count must be identical", mavenJars.size(), nutsJars.size());
    }

    @Test
    public void testSolverResolutionViaApi() {
        net.thevpc.nuts.core.NWorkspace ws = Nuts.openWorkspace();
        NSession session = ws.createSession();
        List<NId> resolved = session.callWith(() ->
                NSearch.of("net.thevpc.nmvn:nmvn#1.0.0.0")
                        .transitive(true)
                        .inlineDependencies(true)
                        .dependencyFilter(NDependencyFilter.ofRunnable())
                        .getResultIds().toList()
        );

        Map<String, String> versions = new HashMap<>();
        for (NId id : resolved) {
            versions.put(id.artifactId(), id.version().value());
        }

        Assert.assertEquals("3.6.3", versions.get("maven-model"));
        Assert.assertEquals("3.6.3", versions.get("maven-core"));
        Assert.assertEquals("2.5", versions.get("commons-io"));
        Assert.assertFalse(versions.containsKey("junit"));
        Assert.assertFalse(versions.containsKey("plexus-container-default"));
    }
}
