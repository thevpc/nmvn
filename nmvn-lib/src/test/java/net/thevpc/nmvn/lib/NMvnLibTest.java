package net.thevpc.nmvn.lib;

import net.thevpc.nmvn.lib.config.NMvnConfig;
import net.thevpc.nmvn.lib.config.NMvnConfigLoader;
import net.thevpc.nmvn.lib.exception.AmbiguousArtifactException;
import net.thevpc.nmvn.lib.exception.CycleDetectedException;
import net.thevpc.nmvn.lib.exception.StrictSnapshotException;
import net.thevpc.nmvn.lib.diagnostic.*;
import net.thevpc.nmvn.lib.model.*;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nmvn.lib.modifier.PomModifier;
import net.thevpc.nmvn.lib.service.BumpResult;
import net.thevpc.nmvn.lib.service.ReleaseResult;
import net.thevpc.nmvn.lib.service.ScanResult;
import net.thevpc.nmvn.lib.service.VersionService;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import net.thevpc.nuts.io.NPath;
import java.util.*;

public class NMvnLibTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private VersionService service;

    @Before
    public void setup() {
        Nuts.require();
        service = new VersionService();
    }

    private NPath createPom(NPath dir, String content) {
        dir.mkdirs();
        NPath pomFile = dir.resolve("pom.xml");
        pomFile.writeString(content);
        return pomFile;
    }

    @Test
    public void testPropertyInheritanceAndChildOverride() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("prop-test"));
        NPath parentDir = root.resolve("parent");
        NPath child1Dir = root.resolve("child1");
        NPath child2Dir = root.resolve("child2");

        createPom(parentDir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.test</groupId>\n" +
                "  <artifactId>parent-pom</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "  <packaging>pom</packaging>\n" +
                "  <properties>\n" +
                "    <shared.version>1.0.0-SNAPSHOT</shared.version>\n" +
                "  </properties>\n" +
                "</project>");

        // Child 1 inherits <shared.version>
        createPom(child1Dir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <parent>\n" +
                "    <groupId>com.test</groupId>\n" +
                "    <artifactId>parent-pom</artifactId>\n" +
                "    <version>1.0.0-SNAPSHOT</version>\n" +
                "  </parent>\n" +
                "  <artifactId>child-1</artifactId>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>com.external</groupId>\n" +
                "      <artifactId>ext-lib</artifactId>\n" +
                "      <version>${shared.version}</version>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");

        // Child 2 overrides <shared.version>
        createPom(child2Dir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <parent>\n" +
                "    <groupId>com.test</groupId>\n" +
                "    <artifactId>parent-pom</artifactId>\n" +
                "    <version>1.0.0-SNAPSHOT</version>\n" +
                "  </parent>\n" +
                "  <artifactId>child-2</artifactId>\n" +
                "  <properties>\n" +
                "    <shared.version>2.0.0-SNAPSHOT</shared.version>\n" +
                "  </properties>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>com.external</groupId>\n" +
                "      <artifactId>ext-lib</artifactId>\n" +
                "      <version>${shared.version}</version>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");

        NMvnConfig config = new NMvnConfig();
        config.setRoots(Collections.singletonList(root.toString()));

        ScanResult scan = service.scan(config, root);
        Assert.assertEquals(3, scan.getArtifacts().size());

        PomArtifact child1 = scan.getArtifacts().get(NId.of("com.test", "child-1"));
        PomArtifact child2 = scan.getArtifacts().get(NId.of("com.test", "child-2"));

        Assert.assertEquals("1.0.0-SNAPSHOT", child1.getDependencies().get(0).getResolvedVersion());
        Assert.assertEquals(parentDir.resolve("pom.xml"), child1.getDependencies().get(0).getPropertyDefiningPom());

        Assert.assertEquals("2.0.0-SNAPSHOT", child2.getDependencies().get(0).getResolvedVersion());
        Assert.assertEquals(child2Dir.resolve("pom.xml"), child2.getDependencies().get(0).getPropertyDefiningPom());
    }

    @Test
    public void testPropertyAliasingBumpingMultipleDependencies() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("alias-test"));
        NPath pDir = root.resolve("proj");

        createPom(pDir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.test</groupId>\n" +
                "  <artifactId>alias-project</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "  <properties>\n" +
                "    <lib.version>1.0.0-SNAPSHOT</lib.version>\n" +
                "  </properties>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>com.common</groupId>\n" +
                "      <artifactId>lib-core</artifactId>\n" +
                "      <version>${lib.version}</version>\n" +
                "    </dependency>\n" +
                "    <dependency>\n" +
                "      <groupId>com.common</groupId>\n" +
                "      <artifactId>lib-extra</artifactId>\n" +
                "      <version>${lib.version}</version>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");

        // Also define lib-core in same workspace
        NPath libDir = root.resolve("lib-core");
        createPom(libDir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.common</groupId>\n" +
                "  <artifactId>lib-core</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "</project>");

        NMvnConfig config = new NMvnConfig();
        config.setRoots(Collections.singletonList(root.toString()));

        List<BumpInstruction> bumps = Collections.singletonList(
                new BumpInstruction("com.common", "lib-core", "1.0.0.0")
        );

        BumpResult result = service.bump(config, root, bumps, false, true);
        Assert.assertTrue(result.hasChanges());

        // Check content of alias-project pom.xml on disk
        String projContent = pDir.resolve("pom.xml").readString();
        Assert.assertTrue(projContent.contains("<lib.version>1.0.0.0</lib.version>"));
    }

    @Test
    public void testBomImportCascading() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("bom-test"));
        NPath bomDir = root.resolve("my-bom");
        NPath consumerDir = root.resolve("consumer");

        createPom(bomDir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.test</groupId>\n" +
                "  <artifactId>my-bom</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "  <packaging>pom</packaging>\n" +
                "</project>");

        createPom(consumerDir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.test</groupId>\n" +
                "  <artifactId>consumer-app</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "  <dependencyManagement>\n" +
                "    <dependencies>\n" +
                "      <dependency>\n" +
                "        <groupId>com.test</groupId>\n" +
                "        <artifactId>my-bom</artifactId>\n" +
                "        <version>1.0.0-SNAPSHOT</version>\n" +
                "        <type>pom</type>\n" +
                "        <scope>import</scope>\n" +
                "      </dependency>\n" +
                "    </dependencies>\n" +
                "  </dependencyManagement>\n" +
                "</project>");

        NMvnConfig config = new NMvnConfig();
        config.setRoots(Collections.singletonList(root.toString()));

        ScanResult scan = service.scan(config, root);
        PomArtifact consumer = scan.getArtifacts().get(NId.of("com.test", "consumer-app"));
        Assert.assertTrue(consumer.getDependencyManagement().get(0).isBomImport());

        // Bump the BOM
        List<BumpInstruction> bumps = Collections.singletonList(
                new BumpInstruction("com.test", "my-bom", "2.0.0-SNAPSHOT")
        );
        BumpResult result = service.bump(config, root, bumps, false, true);
        Assert.assertTrue(result.hasChanges());

        String consumerContent = consumerDir.resolve("pom.xml").readString();
        Assert.assertTrue(consumerContent.contains("<version>2.0.0-SNAPSHOT</version>"));
    }

    @Test(expected = AmbiguousArtifactException.class)
    public void testAmbiguousArtifactsFailLoudly() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("ambig-test"));
        NPath repo1 = root.resolve("repo1");
        NPath repo2 = root.resolve("repo2");

        createPom(repo1,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.dup</groupId>\n" +
                "  <artifactId>same-artifact</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "</project>");

        createPom(repo2,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.dup</groupId>\n" +
                "  <artifactId>same-artifact</artifactId>\n" +
                "  <version>2.0.0</version>\n" +
                "</project>");

        NMvnConfig config = new NMvnConfig();
        config.setRoots(Arrays.asList(repo1.toString(), repo2.toString()));

        service.scan(config, root);
    }

    @Test(expected = CycleDetectedException.class)
    public void testCycleDetectionFailsLoudly() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("cycle-test"));
        NPath aDir = root.resolve("a");
        NPath bDir = root.resolve("b");

        createPom(aDir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.cycle</groupId>\n" +
                "  <artifactId>art-a</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>com.cycle</groupId>\n" +
                "      <artifactId>art-b</artifactId>\n" +
                "      <version>1.0.0</version>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");

        createPom(bDir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.cycle</groupId>\n" +
                "  <artifactId>art-b</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>com.cycle</groupId>\n" +
                "      <artifactId>art-a</artifactId>\n" +
                "      <version>1.0.0</version>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");

        NMvnConfig config = new NMvnConfig();
        config.setRoots(Collections.singletonList(root.toString()));

        service.scan(config, root);
    }

    @Test
    public void testExternalSnapshotIgnoredByDefaultAndFailsStrict() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("strict-test"));
        NPath aDir = root.resolve("a");

        createPom(aDir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.app</groupId>\n" +
                "  <artifactId>my-app</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>com.external</groupId>\n" +
                "      <artifactId>ext-snap</artifactId>\n" +
                "      <version>9.9.9-SNAPSHOT</version>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");

        NMvnConfig config = new NMvnConfig();
        config.setRoots(Collections.singletonList(root.toString()));

        // Non-strict release should succeed
        ReleaseResult rel = service.release(config, root, null, false, false);
        Assert.assertEquals("1.0.0", rel.getReleasedArtifacts().get(NId.of("com.app", "my-app")));
        Assert.assertEquals(1, rel.getUnmanagedSnapshots().size());

        // Strict release should throw StrictSnapshotException
        try {
            service.release(config, root, null, true, false);
            Assert.fail("Expected StrictSnapshotException in strict mode");
        } catch (StrictSnapshotException expected) {
            Assert.assertTrue(expected.getMessage().contains("ext-snap"));
        }
    }

    @Test
    public void testDryRunDoesNotModifyFiles() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("dryrun-test"));
        NPath aDir = root.resolve("a");

        String originalContent =
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.app</groupId>\n" +
                "  <artifactId>my-app</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "</project>";
        NPath pom = createPom(aDir, originalContent);

        NMvnConfig config = new NMvnConfig();
        config.setRoots(Collections.singletonList(root.toString()));

        List<BumpInstruction> bumps = Collections.singletonList(
                new BumpInstruction("com.app", "my-app", "1.0.0.0")
        );

        // Dry run: apply = false
        BumpResult result = service.bump(config, root, bumps, false, false);
        Assert.assertTrue(result.hasChanges());
        Assert.assertFalse(result.getChanges().get(0).getDiffLines().isEmpty());

        // File on disk must remain unchanged!
        String contentOnDisk = pom.readString();
        Assert.assertEquals(originalContent, contentOnDisk);

        // Now apply = true
        service.bump(config, root, bumps, false, true);
        String updatedOnDisk = pom.readString();
        Assert.assertTrue(updatedOnDisk.contains("<version>1.0.0.0</version>"));
    }

    @Test
    public void testExclusionFilteringBuildAndTargetDirs() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("exclude-test"));
        NPath srcDir = root.resolve("module-a");
        NPath targetDir = root.resolve("module-a/target/generated-sources");

        createPom(srcDir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.app</groupId>\n" +
                "  <artifactId>mod-a</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "</project>");

        createPom(targetDir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.app</groupId>\n" +
                "  <artifactId>generated-dummy</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "</project>");

        NMvnConfig config = new NMvnConfig();
        config.setRoots(Collections.singletonList(root.toString()));

        ScanResult scan = service.scan(config, root);
        Assert.assertEquals(1, scan.getArtifacts().size());
        Assert.assertTrue(scan.getArtifacts().containsKey(NId.of("com.app", "mod-a")));
        Assert.assertFalse(scan.getArtifacts().containsKey(NId.of("com.app", "generated-dummy")));
    }

    @Test
    public void testTsonConfigLoaderRoundtrip() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("tson-test"));
        NPath configFile = root.resolve("nmvn.tson");

        NMvnConfig cfg = new NMvnConfig();
        cfg.setRoots(Arrays.asList(".", "../other"));
        cfg.setExcludes(Arrays.asList("**/target/**", "**/build/**"));
        cfg.getBumpPolicy().setDefaultIncrement(BumpPolicy.IncrementType.MAJOR);
        cfg.getBumpPolicy().setCascadePolicy(BumpPolicy.CascadePolicy.CASCADE_VERSIONS);
        cfg.setInstructions(Collections.singletonList(
                new BumpInstruction("com.example", "foo", "2.0.0-SNAPSHOT")
        ));

        NMvnConfigLoader.save(cfg, configFile);
        Assert.assertTrue(configFile.isRegularFile());

        NMvnConfig loaded = NMvnConfigLoader.load(configFile);
        Assert.assertEquals(2, loaded.getRoots().size());
        Assert.assertEquals(BumpPolicy.IncrementType.MAJOR, loaded.getBumpPolicy().getDefaultIncrement());
        Assert.assertEquals(BumpPolicy.CascadePolicy.CASCADE_VERSIONS, loaded.getBumpPolicy().getCascadePolicy());
        Assert.assertEquals(1, loaded.getInstructions().size());
        Assert.assertEquals("foo", loaded.getInstructions().get(0).getArtifactId());
    }

    @Test
    public void testFormattingAndCommentsPreserved() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("format-test"));
        NPath pDir = root.resolve("proj");

        String original =
                "<!-- Top license comment header with custom layout -->\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "   <groupId>com.custom</groupId>\n" +
                "     <artifactId>custom-formatting</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "\n" +
                "  <!-- Properties section comment -->\n" +
                "  <properties>\n" +
                "       <!-- inline comment -->\n" +
                "       <dep.version>1.0.0-SNAPSHOT</dep.version>  <!-- trailing comment -->\n" +
                "  </properties>\n" +
                "\n" +
                "  <dependencies>\n" +
                "    <!-- Dependency comment -->\n" +
                "    <dependency>\n" +
                "       <groupId>com.custom</groupId>\n" +
                "       <artifactId>other-lib</artifactId>\n" +
                "       <version>${dep.version}</version>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>";

        NPath pom = createPom(pDir, original);

        NMvnConfig config = new NMvnConfig();
        config.setRoots(Collections.singletonList(root.toString()));

        List<BumpInstruction> bumps = Collections.singletonList(
                new BumpInstruction("com.custom", "custom-formatting", "1.0.0.0")
        );

        service.bump(config, root, bumps, false, true);

        String updated = pom.readString();

        // Verify version changed
        Assert.assertTrue(updated.contains("<version>1.0.0.0</version>"));

        // Verify comments preserved
        Assert.assertTrue(updated.contains("<!-- Top license comment header with custom layout -->"));
        Assert.assertTrue(updated.contains("<!-- Properties section comment -->"));
        Assert.assertTrue(updated.contains("<!-- inline comment -->"));
        Assert.assertTrue(updated.contains("<!-- trailing comment -->"));
        Assert.assertTrue(updated.contains("<!-- Dependency comment -->"));

        // Verify custom indentation preserved
        Assert.assertTrue(updated.contains("   <groupId>com.custom</groupId>"));
        Assert.assertTrue(updated.contains("     <artifactId>custom-formatting</artifactId>"));
        Assert.assertTrue(updated.contains("       <dep.version>1.0.0-SNAPSHOT</dep.version>  <!-- trailing comment -->"));

        // Verify that the ONLY difference is the exact version tag changed
        String expected = original.replace("<version>1.0.0-SNAPSHOT</version>", "<version>1.0.0.0</version>");
        Assert.assertEquals(expected, updated);
    }

    @Test
    public void testCommentsAndNewlinesInsideTagBodyPreserved() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("inner-comment-test"));
        NPath pDir = root.resolve("proj");

        String original =
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.custom</groupId>\n" +
                "  <artifactId>inner-comment-test</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "  <properties>\n" +
                "    <dep.version>1.0.0-SNAPSHOT\n" +
                "<!-- trailing comment -->\n" +
                "</dep.version>\n" +
                "  </properties>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>com.custom</groupId>\n" +
                "      <artifactId>inner-lib</artifactId>\n" +
                "      <version>\n" +
                "        <!-- inner leading comment -->\n" +
                "        1.0.0-SNAPSHOT\n" +
                "        <!-- inner trailing comment -->\n" +
                "      </version>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>";

        NPath pom = createPom(pDir, original);

        NPath libDir = root.resolve("inner-lib");
        createPom(libDir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.custom</groupId>\n" +
                "  <artifactId>inner-lib</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "</project>");

        NMvnConfig config = new NMvnConfig();
        config.setRoots(Collections.singletonList(root.toString()));

        List<BumpInstruction> bumps = Collections.singletonList(
                new BumpInstruction("com.custom", "inner-lib", "1.0.0.0")
        );

        service.bump(config, root, bumps, false, true);

        String updated = pom.readString();

        Assert.assertTrue(updated.contains("1.0.0.0"));
        Assert.assertTrue(updated.contains("<!-- inner leading comment -->"));
        Assert.assertTrue(updated.contains("<!-- inner trailing comment -->"));

        // Also test direct update of <dep.version> with comments inside tag
        String updatedProp = PomModifier.updateProperty(updated, "dep.version", "2.0.0-SNAPSHOT");
        Assert.assertTrue(updatedProp.contains("<dep.version>2.0.0-SNAPSHOT\n<!-- trailing comment -->\n</dep.version>"));
    }

    @Test
    public void testCheckCleanWorkspaceReportsNoIssues() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("clean-workspace"));
        NPath mod1 = root.resolve("mod1");
        createPom(mod1,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.clean</groupId>\n" +
                "  <artifactId>mod1</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "</project>");
        NPath mod2 = root.resolve("mod2");
        createPom(mod2,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.clean</groupId>\n" +
                "  <artifactId>mod2</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>com.clean</groupId>\n" +
                "      <artifactId>mod1</artifactId>\n" +
                "      <version>1.0.0-SNAPSHOT</version>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");

        NMvnConfig config = new NMvnConfig();
        config.setRoots(Collections.singletonList(root.toString()));

        DiagnosticReport report = service.check(config, root);
        Assert.assertTrue(report.isEmpty());
        Assert.assertFalse(report.hasErrors());
        Assert.assertFalse(report.hasWarnings());
    }

    @Test
    public void testCheckDetectsMultipleVersionsAndMixedSnapshots() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("multi-version-check"));
        NPath mod1 = root.resolve("mod1");
        createPom(mod1,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.check</groupId>\n" +
                "  <artifactId>mod1</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>org.apache.commons</groupId>\n" +
                "      <artifactId>commons-lang3</artifactId>\n" +
                "      <version>3.10</version>\n" +
                "    </dependency>\n" +
                "    <dependency>\n" +
                "      <groupId>com.google.guava</groupId>\n" +
                "      <artifactId>guava</artifactId>\n" +
                "      <version>30.0-SNAPSHOT</version>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");
        NPath mod2 = root.resolve("mod2");
        createPom(mod2,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.check</groupId>\n" +
                "  <artifactId>mod2</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>org.apache.commons</groupId>\n" +
                "      <artifactId>commons-lang3</artifactId>\n" +
                "      <version>3.12.0</version>\n" +
                "    </dependency>\n" +
                "    <dependency>\n" +
                "      <groupId>com.google.guava</groupId>\n" +
                "      <artifactId>guava</artifactId>\n" +
                "      <version>30.0-jre</version>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");

        NMvnConfig config = new NMvnConfig();
        config.setRoots(Collections.singletonList(root.toString()));

        DiagnosticReport report = service.check(config, root);
        Assert.assertTrue(report.hasWarnings());
        Assert.assertTrue(report.hasErrors());

        List<DiagnosticIssue> multiVer = report.getByRule(DiagnosticRule.MULTI_VERSION_DEPENDENCY);
        Assert.assertTrue(multiVer.size() >= 2); // commons-lang3 and guava

        List<DiagnosticIssue> mixedSnap = report.getByRule(DiagnosticRule.MIXED_SNAPSHOT_AND_RELEASE);
        Assert.assertEquals(1, mixedSnap.size());
        Assert.assertEquals("guava", mixedSnap.get(0).getTargetId().artifactId());
    }

    @Test
    public void testCheckDetectsInternalVersionMismatch() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("internal-mismatch-check"));
        NPath mod1 = root.resolve("mod1");
        createPom(mod1,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.check</groupId>\n" +
                "  <artifactId>mod1</artifactId>\n" +
                "  <version>2.0.0-SNAPSHOT</version>\n" +
                "</project>");
        NPath mod2 = root.resolve("mod2");
        createPom(mod2,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.check</groupId>\n" +
                "  <artifactId>mod2</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>com.check</groupId>\n" +
                "      <artifactId>mod1</artifactId>\n" +
                "      <version>1.0.0-SNAPSHOT</version>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");

        NMvnConfig config = new NMvnConfig();
        config.setRoots(Collections.singletonList(root.toString()));

        DiagnosticReport report = service.check(config, root);
        Assert.assertTrue(report.hasErrors());

        List<DiagnosticIssue> issues = report.getByRule(DiagnosticRule.INTERNAL_VERSION_MISMATCH);
        Assert.assertEquals(1, issues.size());
        Assert.assertEquals("mod1", issues.get(0).getTargetId().artifactId());
    }

    @Test
    public void testCheckDetectsParentVersionMismatch() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("parent-mismatch-check"));
        NPath parent = root.resolve("parent");
        createPom(parent,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.check</groupId>\n" +
                "  <artifactId>parent-pom</artifactId>\n" +
                "  <version>2.0.0-SNAPSHOT</version>\n" +
                "  <packaging>pom</packaging>\n" +
                "</project>");
        NPath child = root.resolve("child");
        createPom(child,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <parent>\n" +
                "    <groupId>com.check</groupId>\n" +
                "    <artifactId>parent-pom</artifactId>\n" +
                "    <version>1.0.0-SNAPSHOT</version>\n" +
                "  </parent>\n" +
                "  <artifactId>child</artifactId>\n" +
                "</project>");

        NMvnConfig config = new NMvnConfig();
        config.setRoots(Collections.singletonList(root.toString()));

        DiagnosticReport report = service.check(config, root);
        Assert.assertTrue(report.hasErrors());

        List<DiagnosticIssue> issues = report.getByRule(DiagnosticRule.PARENT_VERSION_MISMATCH);
        Assert.assertEquals(1, issues.size());
        Assert.assertEquals("parent-pom", issues.get(0).getTargetId().artifactId());
    }

    @Test
    public void testCheckDetectsSnapshotDependencyInRelease() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("snapshot-in-release-check"));
        NPath mod1 = root.resolve("mod1");
        createPom(mod1,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.check</groupId>\n" +
                "  <artifactId>mod1</artifactId>\n" +
                "  <version>1.0.0</version>\n" + // Release version
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>com.external</groupId>\n" +
                "      <artifactId>ext-lib</artifactId>\n" +
                "      <version>0.5.0-SNAPSHOT</version>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");

        NMvnConfig config = new NMvnConfig();
        config.setRoots(Collections.singletonList(root.toString()));

        DiagnosticReport report = service.check(config, root);
        Assert.assertTrue(report.hasErrors());

        List<DiagnosticIssue> issues = report.getByRule(DiagnosticRule.SNAPSHOT_DEPENDENCY_IN_RELEASE);
        Assert.assertEquals(1, issues.size());
        Assert.assertEquals("ext-lib", issues.get(0).getTargetId().artifactId());
    }

    @Test
    public void testCheckDetectsCircularDependency() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("circular-check"));
        NPath mod1 = root.resolve("mod1");
        createPom(mod1,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.cycle</groupId>\n" +
                "  <artifactId>mod1</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>com.cycle</groupId>\n" +
                "      <artifactId>mod2</artifactId>\n" +
                "      <version>1.0.0-SNAPSHOT</version>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");
        NPath mod2 = root.resolve("mod2");
        createPom(mod2,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.cycle</groupId>\n" +
                "  <artifactId>mod2</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>com.cycle</groupId>\n" +
                "      <artifactId>mod1</artifactId>\n" +
                "      <version>1.0.0-SNAPSHOT</version>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");

        NMvnConfig config = new NMvnConfig();
        config.setRoots(Collections.singletonList(root.toString()));

        DiagnosticReport report = service.check(config, root);
        Assert.assertTrue(report.hasErrors());

        List<DiagnosticIssue> issues = report.getByRule(DiagnosticRule.CIRCULAR_DEPENDENCY);
        Assert.assertFalse(issues.isEmpty());
    }

    @Test
    public void testPomDependencyToNutsDependency() {
        PomDependency dep = new PomDependency("org.apache.commons", "commons-lang3", "3.14.0", "compile", "jar", false, DependencyEdgeType.DIRECT_DEPENDENCY);
        net.thevpc.nuts.artifact.NDependency nutsDep = dep.toDependency();
        Assert.assertNotNull(nutsDep);
        Assert.assertEquals("org.apache.commons", nutsDep.groupId());
        Assert.assertEquals("commons-lang3", nutsDep.artifactId());
        Assert.assertEquals("api", nutsDep.scope());
        Assert.assertEquals("jar", nutsDep.type());
        Assert.assertEquals("org.apache.commons:commons-lang3#3.14.0", nutsDep.toId().longName());
    }

    @Test
    public void testConfigResolutionNutsLayoutAndRecent() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("config-test"));

        // 1. Resolve default in working dir
        NPath resolvedDefault = NMvnConfigLoader.resolveConfigFile(null, root);
        Assert.assertEquals(root.resolve("nmvn.tson"), resolvedDefault);

        // 2. Simple name resolution in working directory
        NPath localCfg = root.resolve("custom.tson");
        localCfg.writeString("{}");
        NPath resolvedNamed = NMvnConfigLoader.resolveConfigFile("custom", root);
        Assert.assertEquals(localCfg, resolvedNamed);

        // 3. Simple name resolution in Nuts layout config folder
        NPath nutsConf = NMvnConfigLoader.getAppConfigFolder();
        Assert.assertNotNull(nutsConf);
        NPath nutsCfg = nutsConf.resolve("global-test.tson");
        if (nutsCfg.parent() != null && !nutsCfg.parent().isDirectory()) {
            nutsCfg.parent().mkdirs();
        }
        nutsCfg.writeString("{}");
        try {
            NPath resolvedGlobal = NMvnConfigLoader.resolveConfigFile("global-test", root);
            Assert.assertEquals(nutsCfg, resolvedGlobal);

            // 4. Test list named configs
            List<NPath> namedConfigs = NMvnConfigLoader.listNamedConfigs();
            boolean found = false;
            for (NPath np : namedConfigs) {
                if ("global-test.tson".equals(np.name())) {
                    found = true;
                    break;
                }
            }
            Assert.assertTrue(found);

            // 5. Test recent configs tracking
            NMvnConfigLoader.recordRecentConfig(localCfg);
            List<NPath> recent = NMvnConfigLoader.loadRecentConfigs();
            Assert.assertTrue(recent.contains(localCfg.toAbsolute().normalize()));
        } finally {
            nutsCfg.delete();
        }
    }

    @Test
    public void testScanSkipsTargetAndDistAtPomLevel() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("scan-skip-target-dist"));
        NPath warDir = root.resolve("app").resolve("nrepo-war");

        // 1. Main module pom
        createPom(warDir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>net.thevpc.nrepo</groupId>\n" +
                "  <artifactId>nrepo-war</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "</project>");

        // 2. Build artifact under dist (e.g. exploded war)
        NPath distExplodedPom = warDir.resolve("dist")
                .resolve("nrepo-exploded")
                .resolve("META-INF")
                .resolve("maven")
                .resolve("net.thevpc.nrepo")
                .resolve("nrepo-war");
        createPom(distExplodedPom,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>net.thevpc.nrepo</groupId>\n" +
                "  <artifactId>nrepo-war</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "</project>");

        // 3. Build artifact under target
        NPath targetExplodedPom = warDir.resolve("target")
                .resolve("classes")
                .resolve("META-INF")
                .resolve("maven")
                .resolve("net.thevpc.nrepo")
                .resolve("nrepo-war");
        createPom(targetExplodedPom,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>net.thevpc.nrepo</groupId>\n" +
                "  <artifactId>nrepo-war</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "</project>");

        // 4. Another sibling module
        NPath otherDir = root.resolve("core").resolve("nrepo-core");
        createPom(otherDir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>net.thevpc.nrepo</groupId>\n" +
                "  <artifactId>nrepo-core</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "</project>");

        NMvnConfig config = new NMvnConfig();
        // Clear default excludes to prove target and dist at pom level are skipped inherently
        config.setExcludes(Collections.emptyList());

        ScanResult scanResult = service.scan(config, root);
        Assert.assertEquals(2, scanResult.getArtifacts().size());
        Assert.assertTrue(scanResult.getArtifacts().containsKey(NId.of("net.thevpc.nrepo:nrepo-war")));
        Assert.assertTrue(scanResult.getArtifacts().containsKey(NId.of("net.thevpc.nrepo:nrepo-core")));
        Assert.assertEquals(warDir.resolve("pom.xml").toAbsolute().normalize(),
                scanResult.getArtifacts().get(NId.of("net.thevpc.nrepo:nrepo-war")).getPath().toAbsolute().normalize());
    }

    @Test
    public void testDependencyManagementAndBomVersionResolution() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("bom-dep-mgmt-test"));

        // 1. A BOM module
        NPath bomDir = root.resolve("my-bom");
        createPom(bomDir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.test</groupId>\n" +
                "  <artifactId>my-bom</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "  <packaging>pom</packaging>\n" +
                "  <properties>\n" +
                "    <lib-a.version>2.5.0</lib-a.version>\n" +
                "  </properties>\n" +
                "  <dependencyManagement>\n" +
                "    <dependencies>\n" +
                "      <dependency>\n" +
                "        <groupId>com.external</groupId>\n" +
                "        <artifactId>lib-a</artifactId>\n" +
                "        <version>${lib-a.version}</version>\n" +
                "      </dependency>\n" +
                "    </dependencies>\n" +
                "  </dependencyManagement>\n" +
                "</project>");

        // 2. A parent POM importing my-bom and declaring its own dependencyManagement
        NPath parentDir = root.resolve("parent");
        createPom(parentDir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.test</groupId>\n" +
                "  <artifactId>parent-pom</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "  <packaging>pom</packaging>\n" +
                "  <dependencyManagement>\n" +
                "    <dependencies>\n" +
                "      <dependency>\n" +
                "        <groupId>com.test</groupId>\n" +
                "        <artifactId>my-bom</artifactId>\n" +
                "        <version>1.0.0</version>\n" +
                "        <type>pom</type>\n" +
                "        <scope>import</scope>\n" +
                "      </dependency>\n" +
                "      <dependency>\n" +
                "        <groupId>com.external</groupId>\n" +
                "        <artifactId>lib-b</artifactId>\n" +
                "        <version>1.2.0</version>\n" +
                "      </dependency>\n" +
                "    </dependencies>\n" +
                "  </dependencyManagement>\n" +
                "</project>");

        // 3. Child module with direct dependencies having no declared version
        NPath childDir = root.resolve("child");
        createPom(childDir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <parent>\n" +
                "    <groupId>com.test</groupId>\n" +
                "    <artifactId>parent-pom</artifactId>\n" +
                "    <version>1.0.0</version>\n" +
                "  </parent>\n" +
                "  <artifactId>child-app</artifactId>\n" +
                "  <dependencies>\n" +
                "    <!-- Managed by imported my-bom -->\n" +
                "    <dependency>\n" +
                "      <groupId>com.external</groupId>\n" +
                "      <artifactId>lib-a</artifactId>\n" +
                "    </dependency>\n" +
                "    <!-- Managed by parent dependencyManagement -->\n" +
                "    <dependency>\n" +
                "      <groupId>com.external</groupId>\n" +
                "      <artifactId>lib-b</artifactId>\n" +
                "    </dependency>\n" +
                "    <!-- Truly missing version -->\n" +
                "    <dependency>\n" +
                "      <groupId>com.external</groupId>\n" +
                "      <artifactId>lib-unmanaged</artifactId>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");

        NMvnConfig config = new NMvnConfig();
        ScanResult scanResult = service.scan(config, root);

        PomArtifact childArtifact = scanResult.getArtifacts().get(NId.of("com.test:child-app"));
        Assert.assertNotNull(childArtifact);

        PomDependency depA = childArtifact.getDependencies().stream()
                .filter(d -> "lib-a".equals(d.getArtifactId())).findFirst().orElse(null);
        Assert.assertNotNull(depA);
        Assert.assertEquals("2.5.0", depA.getResolvedVersion());

        PomDependency depB = childArtifact.getDependencies().stream()
                .filter(d -> "lib-b".equals(d.getArtifactId())).findFirst().orElse(null);
        Assert.assertNotNull(depB);
        Assert.assertEquals("1.2.0", depB.getResolvedVersion());

        PomDependency depUnmanaged = childArtifact.getDependencies().stream()
                .filter(d -> "lib-unmanaged".equals(d.getArtifactId())).findFirst().orElse(null);
        Assert.assertNotNull(depUnmanaged);
        Assert.assertNull(depUnmanaged.getResolvedVersion());

        // Check diagnostics
        DiagnosticReport report = service.check(config, root);
        long missingCount = report.getIssues().stream()
                .filter(i -> i.getRule() == DiagnosticRule.MISSING_VERSION)
                .count();
        Assert.assertEquals(1, missingCount);

        DiagnosticIssue issue = report.getIssues().stream()
                .filter(i -> i.getRule() == DiagnosticRule.MISSING_VERSION)
                .findFirst().orElse(null);
        Assert.assertNotNull(issue);
        Assert.assertEquals(NId.of("com.external:lib-unmanaged"), issue.getTargetId());
    }

    @Test
    public void testExternalSpringBootBomResolution() throws Exception {
        NPath root = NPath.of(tempFolder.newFolder("spring-boot-bom-test"));
        NPath appDir = root.resolve("app");

        createPom(appDir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <parent>\n" +
                "    <groupId>org.springframework.boot</groupId>\n" +
                "    <artifactId>spring-boot-starter-parent</artifactId>\n" +
                "    <version>3.4.4</version>\n" +
                "  </parent>\n" +
                "  <groupId>net.thevpc.test</groupId>\n" +
                "  <artifactId>spring-test-app</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>org.springframework.security</groupId>\n" +
                "      <artifactId>spring-security-test</artifactId>\n" +
                "      <scope>test</scope>\n" +
                "    </dependency>\n" +
                "    <dependency>\n" +
                "      <groupId>org.postgresql</groupId>\n" +
                "      <artifactId>postgresql</artifactId>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");

        NMvnConfig config = new NMvnConfig();
        ScanResult scanResult = service.scan(config, root);

        PomArtifact app = scanResult.getArtifacts().get(NId.of("net.thevpc.test:spring-test-app"));
        Assert.assertNotNull(app);

        PomDependency secDep = app.getDependencies().stream()
                .filter(d -> "spring-security-test".equals(d.getArtifactId())).findFirst().orElse(null);
        Assert.assertNotNull(secDep);
        Assert.assertEquals("6.3.4", secDep.getResolvedVersion());

        PomDependency pgDep = app.getDependencies().stream()
                .filter(d -> "postgresql".equals(d.getArtifactId())).findFirst().orElse(null);
        Assert.assertNotNull(pgDep);
        Assert.assertEquals("42.7.4", pgDep.getResolvedVersion());

        DiagnosticReport report = service.check(config, root);
        long missingCount = report.getIssues().stream()
                .filter(i -> i.getRule() == DiagnosticRule.MISSING_VERSION)
                .count();
        Assert.assertEquals(0, missingCount);
    }
}
