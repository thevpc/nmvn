package net.thevpc.nuts.toolbox.mvn;

import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NSession;
import net.thevpc.nuts.io.NPath;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NMvnCliTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Before
    public void setup() {
        Nuts.require();
    }

    private NPath createPom(NPath dir, String content) {
        dir.mkdirs();
        NPath pomFile = dir.resolve("pom.xml");
        pomFile.writeString(content);
        return pomFile;
    }

    @Test
    public void testCliScanAndBumpDryRun() throws Exception {
        NPath root = NPath.of(temp.newFolder("cli-test"));
        NPath mod1 = root.resolve("mod1");
        NPath mod2 = root.resolve("mod2");

        createPom(mod1,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.cli</groupId>\n" +
                "  <artifactId>mod1</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "</project>");

        createPom(mod2,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.cli</groupId>\n" +
                "  <artifactId>mod2</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>com.cli</groupId>\n" +
                "      <artifactId>mod1</artifactId>\n" +
                "      <version>1.0.0-SNAPSHOT</version>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");

        NSession session = NSession.of();
        MvnVersionCli cli = new MvnVersionCli(session);

        // Run scan
        int scanCode = cli.run(new String[]{"scan", "--root", root.toString()}, false);
        Assert.assertEquals(0, scanCode);

        // Run bump with --dry
        int bumpCode = cli.run(new String[]{"bump", "--root", root.toString(), "-a", "com.cli:mod1=1.1.0-SNAPSHOT", "--dry"}, false);
        Assert.assertEquals(0, bumpCode);

        // Dry-run should not modify files on disk
        String mod1Content = mod1.resolve("pom.xml").readString();
        Assert.assertTrue(mod1Content.contains("<version>1.0.0-SNAPSHOT</version>"));

        // Run bump default (non-dry, applies changes)
        int applyCode = cli.run(new String[]{"bump", "--root", root.toString(), "-a", "com.cli:mod1=1.1.0-SNAPSHOT"}, false);
        Assert.assertEquals(0, applyCode);

        // Now files on disk should be updated
        String mod1Updated = mod1.resolve("pom.xml").readString();
        Assert.assertTrue(mod1Updated.contains("<version>1.1.0-SNAPSHOT</version>"));

        String mod2Updated = mod2.resolve("pom.xml").readString();
        Assert.assertTrue(mod2Updated.contains("<version>1.1.0-SNAPSHOT</version>"));
    }

    @Test
    public void testCliCheckCleanAndDiscrepancy() throws Exception {
        NPath root = NPath.of(temp.newFolder("cli-check-test"));
        NPath mod1 = root.resolve("mod1");
        NPath mod2 = root.resolve("mod2");

        createPom(mod1,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.cli</groupId>\n" +
                "  <artifactId>mod1</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "</project>");

        createPom(mod2,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.cli</groupId>\n" +
                "  <artifactId>mod2</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>com.cli</groupId>\n" +
                "      <artifactId>mod1</artifactId>\n" +
                "      <version>1.0.0-SNAPSHOT</version>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");

        NSession session = NSession.of();
        MvnVersionCli cli = new MvnVersionCli(session);

        // Clean workspace should return 0
        int checkClean = cli.run(new String[]{"check", "--root", root.toString()}, false);
        Assert.assertEquals(0, checkClean);

        // Now introduce a version discrepancy in mod2
        createPom(mod2,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.cli</groupId>\n" +
                "  <artifactId>mod2</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>com.cli</groupId>\n" +
                "      <artifactId>mod1</artifactId>\n" +
                "      <version>2.0.0-SNAPSHOT</version>\n" + // Discrepancy! mod1 is 1.0.0-SNAPSHOT
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");

        // Discrepant workspace should return 1
        int checkDiscrepancy = cli.run(new String[]{"check", "--root", root.toString()}, false);
        Assert.assertEquals(1, checkDiscrepancy);

        // JSON mode should also return 1
        int checkJson = cli.run(new String[]{"check", "--root", root.toString(), "--json"}, false);
        Assert.assertEquals(1, checkJson);
    }

    @Test
    public void testConfigListAndRecent() throws Exception {
        NSession session = NSession.of();
        MvnConfigCli cli = new MvnConfigCli(session);

        // List named configs in Nuts config folder
        int listCode = cli.run(new String[]{"list"}, false);
        Assert.assertEquals(0, listCode);

        // List recent configs
        int recentCode = cli.run(new String[]{"list", "--recent"}, false);
        Assert.assertEquals(0, recentCode);

        // List recent configs JSON
        int recentJsonCode = cli.run(new String[]{"list", "--recent", "--json"}, false);
        Assert.assertEquals(0, recentJsonCode);
    }

    @Test
    public void testConfigSetGetAndPath() throws Exception {
        NSession session = NSession.of();
        MvnConfigCli cli = new MvnConfigCli(session);

        NPath cfg = NPath.of(temp.getRoot()).resolve("test-cfg.tson");

        // Set config
        int setCode = cli.run(new String[]{
                "set", cfg.toString(),
                "--root", "module-a",
                "--exclude", "target/**",
                "--default-increment", "patch"
        }, false);
        Assert.assertEquals(0, setCode);
        Assert.assertTrue(cfg.isRegularFile());

        String content = cfg.readString();
        Assert.assertTrue(content.contains("module-a"));
        Assert.assertTrue(content.contains("patch"));

        // Path
        int pathCode = cli.run(new String[]{"path", cfg.toString()}, false);
        Assert.assertEquals(0, pathCode);

        // Path JSON
        int pathJsonCode = cli.run(new String[]{"path", cfg.toString(), "--json"}, false);
        Assert.assertEquals(0, pathJsonCode);

        // Get
        int getCode = cli.run(new String[]{"get", cfg.toString()}, false);
        Assert.assertEquals(0, getCode);

        // Get JSON
        int getJsonCode = cli.run(new String[]{"get", cfg.toString(), "--json"}, false);
        Assert.assertEquals(0, getJsonCode);
    }

    @Test
    public void testWorksetAddAndRemoveRootsWithScan() throws Exception {
        NPath root = NPath.of(temp.newFolder("ws-test"));
        NPath mod1 = root.resolve("mod1");
        NPath mod2 = root.resolve("mod2");

        createPom(mod1,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.ws</groupId>\n" +
                "  <artifactId>mod1</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "</project>");

        createPom(mod2,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.ws</groupId>\n" +
                "  <artifactId>mod2</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "</project>");

        NSession session = NSession.of();
        MvnWorksetCli cli = new MvnWorksetCli(session);
        NPath wsFile = root.resolve("workset.tson");

        // Add mod1 and mod2 with scan
        int addCode = cli.run(new String[]{
                "add-root",
                "--workset", wsFile.toString(),
                mod1.toString(), mod2.toString(),
                "--scan"
        }, false);
        Assert.assertEquals(0, addCode);
        Assert.assertTrue(wsFile.isRegularFile());

        String content = wsFile.readString();
        Assert.assertTrue(content.contains("mod1"));
        Assert.assertTrue(content.contains("mod2"));

        // List roots
        int listRootsCode = cli.run(new String[]{"root", "list", "--workset", wsFile.toString()}, false);
        Assert.assertEquals(0, listRootsCode);

        // Scan workset directly
        int scanCode = cli.run(new String[]{"scan", "--workset", wsFile.toString()}, false);
        Assert.assertEquals(0, scanCode);

        // Remove mod1 with scan
        int removeCode = cli.run(new String[]{
                "remove-root",
                "--workset", wsFile.toString(),
                mod1.toString(),
                "--scan"
        }, false);
        Assert.assertEquals(0, removeCode);

        String updatedContent = wsFile.readString();
        Assert.assertFalse(updatedContent.contains("mod1"));
        Assert.assertTrue(updatedContent.contains("mod2"));
    }

    @Test
    public void testCliScanSkipsTargetAndDistAtPomLevel() throws Exception {
        NPath root = NPath.of(temp.newFolder("cli-skip-test"));
        NPath warDir = root.resolve("app").resolve("nrepo-war");

        createPom(warDir,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>net.thevpc.nrepo</groupId>\n" +
                "  <artifactId>nrepo-war</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "</project>");

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

        NSession session = NSession.of();
        MvnVersionCli cli = new MvnVersionCli(session);

        int scanCode = cli.run(new String[]{"scan", "--root", root.toString()}, false);
        Assert.assertEquals(0, scanCode);
    }

    @Test
    public void testCliArtifactOptionFormats() throws Exception {
        NPath root = NPath.of(temp.newFolder("cli-format-test"));
        NPath mod1 = root.resolve("mod1");

        createPom(mod1,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.cli</groupId>\n" +
                "  <artifactId>mod1</artifactId>\n" +
                "  <version>1.0.0-SNAPSHOT</version>\n" +
                "</project>");

        NSession session = NSession.of();
        MvnVersionCli cli = new MvnVersionCli(session);

        // Test -a with '#' delimiter
        int c1 = cli.run(new String[]{"bump", "--root", root.toString(), "-a", "com.cli:mod1#1.1.0-SNAPSHOT", "--dry"}, false);
        Assert.assertEquals(0, c1);

        // Test -a= with '#' delimiter
        int c2 = cli.run(new String[]{"bump", "--root", root.toString(), "-a=com.cli:mod1#1.1.0-SNAPSHOT", "--dry"}, false);
        Assert.assertEquals(0, c2);

        // Test --artifact= with '#' delimiter
        int c3 = cli.run(new String[]{"bump", "--root", root.toString(), "--artifact=com.cli:mod1#1.1.0-SNAPSHOT", "--dry"}, false);
        Assert.assertEquals(0, c3);

        // Test -a= with '=' delimiter
        int c4 = cli.run(new String[]{"bump", "--root", root.toString(), "-a=com.cli:mod1=1.1.0-SNAPSHOT", "--dry"}, false);
        Assert.assertEquals(0, c4);

        // Apply using -a=com.cli:mod1#1.2.0-SNAPSHOT
        int c5 = cli.run(new String[]{"bump", "--root", root.toString(), "-a=com.cli:mod1#1.2.0-SNAPSHOT"}, false);
        Assert.assertEquals(0, c5);
        Assert.assertTrue(mod1.resolve("pom.xml").readString().contains("<version>1.2.0-SNAPSHOT</version>"));
    }

    @Test
    public void testVersionUpdateSubcommandWithReleaseImmutability() throws Exception {
        NPath root = NPath.of(temp.newFolder("cli-update-immutability-test"));
        NPath modA = root.resolve("mod-a");
        NPath modB = root.resolve("mod-b");
        NPath modC = root.resolve("mod-c");

        // mod-a: release 1.0.0
        createPom(modA,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.cli</groupId>\n" +
                "  <artifactId>mod-a</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "</project>");

        // mod-b: release 2.0.0, depends on mod-a
        createPom(modB,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.cli</groupId>\n" +
                "  <artifactId>mod-b</artifactId>\n" +
                "  <version>2.0.0</version>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>com.cli</groupId>\n" +
                "      <artifactId>mod-a</artifactId>\n" +
                "      <version>1.0.0</version>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");

        // mod-c: snapshot 3.0.0-SNAPSHOT, depends on mod-a
        createPom(modC,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.cli</groupId>\n" +
                "  <artifactId>mod-c</artifactId>\n" +
                "  <version>3.0.0-SNAPSHOT</version>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>com.cli</groupId>\n" +
                "      <artifactId>mod-a</artifactId>\n" +
                "      <version>1.0.0</version>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "</project>");

        NSession session = NSession.of();
        MvnVersionCli cli = new MvnVersionCli(session);

        // Update mod-a to 1.1.0-SNAPSHOT
        int code = cli.run(new String[]{"update", "--root", root.toString(), "-a", "com.cli:mod-a#1.1.0-SNAPSHOT"}, false);
        Assert.assertEquals(0, code);

        // 1. mod-a should be 1.1.0-SNAPSHOT
        String aContent = modA.resolve("pom.xml").readString();
        Assert.assertTrue(aContent.contains("<version>1.1.0-SNAPSHOT</version>"));

        // 2. mod-b was release 2.0.0, so it MUST bump to snapshot (2.1.0-SNAPSHOT by default minor increment) because POM was modified
        String bContent = modB.resolve("pom.xml").readString();
        Assert.assertTrue("mod-b version should be bumped to 2.1.0-SNAPSHOT", bContent.contains("<version>2.1.0-SNAPSHOT</version>"));
        Assert.assertTrue("mod-b dependency on mod-a should be updated to 1.1.0-SNAPSHOT", bContent.contains("<version>1.1.0-SNAPSHOT</version>"));

        // 3. mod-c was already snapshot 3.0.0-SNAPSHOT, so its version should stay 3.0.0-SNAPSHOT
        String cContent = modC.resolve("pom.xml").readString();
        Assert.assertTrue("mod-c own version should remain 3.0.0-SNAPSHOT", cContent.contains("<artifactId>mod-c</artifactId>\n  <version>3.0.0-SNAPSHOT</version>"));
        Assert.assertTrue("mod-c dependency on mod-a should be updated to 1.1.0-SNAPSHOT", cContent.contains("<version>1.1.0-SNAPSHOT</version>"));
    }

    @Test
    public void testVersionBumpSnapshotIdempotencyAndForce() throws Exception {
        NPath root = NPath.of(temp.newFolder("cli-bump-force-test"));
        NPath mod1 = root.resolve("mod1");

        createPom(mod1,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.cli</groupId>\n" +
                "  <artifactId>mod1</artifactId>\n" +
                "  <version>1.2.1-SNAPSHOT</version>\n" +
                "</project>");

        NSession session = NSession.of();
        MvnVersionCli cli = new MvnVersionCli(session);

        // Running bump --patch without --force on an existing snapshot should NOT advance the version
        int c1 = cli.run(new String[]{"bump", "--root", root.toString(), "-a", "com.cli:mod1", "--patch"}, false);
        Assert.assertEquals(0, c1);
        Assert.assertTrue(mod1.resolve("pom.xml").readString().contains("<version>1.2.1-SNAPSHOT</version>"));

        // Running bump --patch with --force SHOULD advance the snapshot to 1.2.2-SNAPSHOT
        int c2 = cli.run(new String[]{"bump", "--root", root.toString(), "-a", "com.cli:mod1", "--patch", "--force"}, false);
        Assert.assertEquals(0, c2);
        Assert.assertTrue(mod1.resolve("pom.xml").readString().contains("<version>1.2.2-SNAPSHOT</version>"));
    }

    @Test
    public void testVersionBumpAutoIncrementFlags() throws Exception {
        NPath root = NPath.of(temp.newFolder("cli-bump-flags-test"));
        NPath mod1 = root.resolve("mod1");

        // Release 1.2.0
        createPom(mod1,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.cli</groupId>\n" +
                "  <artifactId>mod1</artifactId>\n" +
                "  <version>1.2.0</version>\n" +
                "</project>");

        NSession session = NSession.of();
        MvnVersionCli cli = new MvnVersionCli(session);

        // Bump minor: 1.2.0 -> 1.3.0-SNAPSHOT
        int c1 = cli.run(new String[]{"bump", "--root", root.toString(), "-a", "com.cli:mod1", "--minor"}, false);
        Assert.assertEquals(0, c1);
        Assert.assertTrue(mod1.resolve("pom.xml").readString().contains("<version>1.3.0-SNAPSHOT</version>"));

        // Reset to 1.0.0 release
        createPom(mod1,
                "<project>\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.cli</groupId>\n" +
                "  <artifactId>mod1</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "</project>");

        // Bump major across whole workspace (no -a passed): 1.0.0 -> 2.0.0-SNAPSHOT
        int c2 = cli.run(new String[]{"bump", "--root", root.toString(), "--major"}, false);
        Assert.assertEquals(0, c2);
        Assert.assertTrue(mod1.resolve("pom.xml").readString().contains("<version>2.0.0-SNAPSHOT</version>"));
    }
}
