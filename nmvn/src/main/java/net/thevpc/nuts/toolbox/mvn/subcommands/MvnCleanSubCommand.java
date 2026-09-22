package net.thevpc.nuts.toolbox.mvn.subcommands;

import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.core.NSession;
import net.thevpc.nuts.io.NOut;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.toolbox.mvn.util.MavenCliWrapper;
import net.thevpc.nuts.util.NRef;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI command to clean target directories of Maven projects under given roots.
 */
public class MvnCleanSubCommand {

    public MvnCleanSubCommand() {

    }

    public int run(String[] args) {
        NCmdLine cmd = NCmdLine.of(args);
        NRef<Boolean> simpleRef = NRef.of(false);
        List<NPath> roots = new ArrayList<>();

        while (cmd.hasNext()) {
            if (NSession.of().configureFirst(cmd)) {
                // handled by nuts
            } else if (!cmd.matcher()
                    .when("-s", "--simple").asFlag(a -> simpleRef.set(a.booleanValue()))
                    .when("-r", "--root").asEntry(a -> roots.add(NPath.of(a.stringValue())))
                    .anyMatch()) {
                // treat as root argument
                roots.add(NPath.of(cmd.next().get().image()));
            }
        }

        // If no roots specified via --root or arguments, use current directory
        if (roots.isEmpty()) {
            roots.add(NPath.of("."));
        }

        // Collect all pom.xml files under the roots
        List<NPath> pomFiles = new ArrayList<>();
        for (NPath root : roots) {
            if (!root.exists()) {
                NOut.println(NMsg.ofC("Root does not exist: %s", root));
                return 1;
            }
            if (!root.isDirectory()) {
                NOut.println(NMsg.ofC("Root is not a directory: %s", root));
                return 1;
            }
            try {
                findPomFiles(root, pomFiles);
            } catch (Exception e) {
                NOut.println(NMsg.ofC("Cannot scan root %s: %s", root, e.getMessage()));
            }
        }

        if (pomFiles.isEmpty()) {
            NOut.println(NMsg.ofStyled("No pom.xml files found under the specified roots.", NTextStyle.info()));
            return 0;
        }

        NOut.println(NMsg.ofStyled(String.format("Found %d pom.xml file(s).", pomFiles.size()), NTextStyle.primary4()));

        int successCount = 0;
        for (NPath pomFile : pomFiles) {
            // Get parent directory of pom.xml
            Path parentPath = pomFile.toPath().get().getParent();
            NPath pomDir = NPath.of(parentPath);
            NOut.println(NMsg.ofC("Processing: %s", pomDir));

            NPath targetDir = pomDir.resolve("target");
            if (simpleRef.get()) {
                // Simple mode: delete target directory directly
                if (targetDir.exists()) {
                    targetDir.delete(true);
                    NOut.println(NMsg.ofStyled("  Deleted target directory", NTextStyle.success()));
                } else {
                    NOut.println(NMsg.ofStyled("  No target directory found", NTextStyle.info()));
                }
                successCount++;
            } else {
                // Run mvn clean in the pom directory
                MavenCliWrapper cli = new MavenCliWrapper();
                cli.setWorkingDirectory(pomDir.toString());
                int r = cli.doMain(new String[]{"clean"});
                if (r == 0) {
                    NOut.println(NMsg.ofStyled("  mvn clean succeeded", NTextStyle.success()));
                    successCount++;
                } else {
                    NOut.println(NMsg.ofStyled(String.format("  mvn clean failed with code %d", r), NTextStyle.danger()));
                }
            }
        }

        NOut.println(NMsg.ofStyled(String.format("Completed: %d/%d succeeded", successCount, pomFiles.size()),
                successCount == pomFiles.size() ? NTextStyle.success() : NTextStyle.warn()));
        return successCount == pomFiles.size() ? 0 : 1;
    }

    /**
     * Recursively finds pom.xml files under the given directory.
     */
    private void findPomFiles(NPath dir, List<NPath> result)  {
        for (NPath entry : dir.list()) {
            Path entryPath = entry.toPath().get();
            if (Files.isDirectory(entryPath, LinkOption.NOFOLLOW_LINKS)) {
                // Skip hidden directories
                String dirName = entryPath.getFileName().toString();
                if (!dirName.startsWith(".")) {
                    findPomFiles(entry, result);
                }
            } else {
                String fileName = entryPath.getFileName().toString();
                if ("pom.xml".equals(fileName)) {
                    result.add(entry);
                }
            }
        }
    }
}