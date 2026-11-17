package net.thevpc.nuts.toolbox.mvn;

import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.toolbox.mvn.subcommands.MvnCleanSubCommand;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MvnCleanSubCommandTest {

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

    private String pom(String group, String artifact, String version) {
        return "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>" + group + "</groupId>\n"
                + "  <artifactId>" + artifact + "</artifactId>\n"
                + "  <version>" + version + "</version>\n"
                + "</project>";
    }

    @Test
    public void testCleanSimpleUsesWorksetScanAndSkipsTargetPoms() throws Exception {
        NPath root = NPath.of(temp.newFolder("clean-ws"));
        NPath mod1 = root.resolve("mod1");
        NPath mod2 = root.resolve("mod2");
        createPom(mod1, pom("com.cl", "mod1", "1.0.0"));
        createPom(mod2, pom("com.cl", "mod2", "1.0.0"));

        // target dirs that must be deleted
        NPath t1 = mod1.resolve("target");
        NPath t2 = mod2.resolve("target");
        t1.mkdirs();
        t2.mkdirs();
        t1.resolve("dummy.txt").writeString("x");
        t2.resolve("dummy.txt").writeString("x");

        // spurious pom under target/ must be ignored by scanner
        NPath fakePomDir = mod1.resolve("target").resolve("classes").resolve("META-INF").resolve("maven")
                .resolve("com.cl").resolve("mod1");
        createPom(fakePomDir, pom("com.cl", "mod1", "1.0.0"));
        NPath fakeTarget = fakePomDir.resolve("target");
        fakeTarget.mkdirs();
        fakeTarget.resolve("keep.txt").writeString("keep");

        MvnCleanSubCommand cli = new MvnCleanSubCommand();
        int code = cli.run(new String[]{"--simple", "--root", root.toString()});
        Assert.assertEquals(0, code);
        Assert.assertFalse(t1.exists());
        Assert.assertFalse(t2.exists());
        // The duplicate-GA pom under mod1/target/ must have been ignored by the
        // scanner: otherwise scan would have failed with AmbiguousArtifactException
        // and clean would have returned 1. (Deleting mod1/target/ necessarily
        // removes the fake pom dir with it, so no survival assertion here.)
    }

    @Test
    public void testCleanSimpleSupportsShortAndLongFlags() throws Exception {
        NPath root = NPath.of(temp.newFolder("clean-flags"));
        NPath mod1 = root.resolve("mod1");
        createPom(mod1, pom("com.cl", "mod1", "1.0.0"));
        NPath t1 = mod1.resolve("target");
        t1.mkdirs();
        t1.resolve("a.txt").writeString("a");

        MvnCleanSubCommand cli = new MvnCleanSubCommand();
        // -s short flag
        Assert.assertEquals(0, cli.run(new String[]{"-s", "-r", root.toString()}));
        Assert.assertFalse(t1.exists());

        // recreate + --simple long flag + positional root
        t1.mkdirs();
        t1.resolve("a.txt").writeString("a");
        Assert.assertEquals(0, cli.run(new String[]{"--simple", root.toString()}));
        Assert.assertFalse(t1.exists());
    }

    @Test
    public void testCleanRejectsMissingRoot() throws Exception {
        MvnCleanSubCommand cli = new MvnCleanSubCommand();
        int code = cli.run(new String[]{"--simple", "--root", "/no/such/dir/xyz123"});
        Assert.assertEquals(1, code);
    }

    @Test
    public void testCleanRespectsExclude() throws Exception {
        NPath root = NPath.of(temp.newFolder("clean-excl"));
        NPath mod1 = root.resolve("mod1");
        NPath mod2 = root.resolve("skipme");
        createPom(mod1, pom("com.cl", "mod1", "1.0.0"));
        createPom(mod2, pom("com.cl", "skipme", "1.0.0"));
        NPath t1 = mod1.resolve("target");
        NPath t2 = mod2.resolve("target");
        t1.mkdirs();
        t2.mkdirs();
        t1.resolve("a.txt").writeString("a");
        t2.resolve("b.txt").writeString("b");

        MvnCleanSubCommand cli = new MvnCleanSubCommand();
        int code = cli.run(new String[]{"--simple", "--root", root.toString(), "--exclude", "**/skipme/**"});
        Assert.assertEquals(0, code);
        Assert.assertFalse(t1.exists());
        Assert.assertTrue(t2.resolve("b.txt").isRegularFile());
    }

    @Test
    public void testCleanGitignoreDryRunReportsAndApplyUntracks() throws Exception {
        Assume.assumeTrue(isGitAvailable());

        NPath root = NPath.of(temp.newFolder("clean-gitignore"));
        NPath mod1 = root.resolve("mod1");
        createPom(mod1, pom("com.cl", "mod1", "1.0.0"));

        NPath targetFile = mod1.resolve("target").resolve("classes").resolve("a.class");
        targetFile.parent().mkdirs();
        targetFile.writeString("compiled");

        runGit(root, "init", "-q");
        runGit(root, "config", "user.email", "test@example.com");
        runGit(root, "config", "user.name", "Test");
        runGit(root, "add", ".");
        runGit(root, "commit", "-qm", "init");
        Assert.assertTrue(runGit(root, "ls-files", "--", "mod1/target").contains("a.class"));

        MvnCleanSubCommand cli = new MvnCleanSubCommand();

        // Dry run: report only, no index or .gitignore changes.
        Assert.assertEquals(0, cli.run(new String[]{
                "--gitignore", "--dry", "--root", root.toString()
        }));
        Assert.assertFalse(root.resolve(".gitignore").exists());
        Assert.assertTrue(runGit(root, "ls-files", "--", "mod1/target").contains("a.class"));
        Assert.assertTrue(targetFile.isRegularFile());

        // Apply: add ignore rule and remove target from the index, keeping the file.
        Assert.assertEquals(0, cli.run(new String[]{
                "--gitignore", "--root", root.toString()
        }));
        Assert.assertTrue(root.resolve(".gitignore").readString().contains("target/"));
        Assert.assertFalse(runGit(root, "ls-files", "--", "mod1/target").contains("a.class"));
        Assert.assertTrue(targetFile.isRegularFile());
        Assert.assertEquals(0, runGitExit(root, "check-ignore", "--no-index", "-q", "--", "mod1/target/"));
    }

    private static boolean isGitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String runGit(NPath dir, String... args) throws Exception {
        GitRun run = git(dir, args);
        Assert.assertEquals("git " + String.join(" ", args) + " failed: " + run.output, 0, run.exitCode);
        return run.output;
    }

    private static int runGitExit(NPath dir, String... args) throws Exception {
        return git(dir, args).exitCode;
    }

    private static GitRun git(NPath dir, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(dir.toPath().get().toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
        }
        return new GitRun(process.waitFor(), new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    private static final class GitRun {
        private final int exitCode;
        private final String output;

        private GitRun(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
