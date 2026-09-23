package net.thevpc.nuts.toolbox.mvn.subcommands;

import net.thevpc.nmvn.lib.config.NMvnConfig;
import net.thevpc.nmvn.lib.config.NMvnConfigLoader;
import net.thevpc.nmvn.lib.model.PomArtifact;
import net.thevpc.nmvn.lib.service.ScanResult;
import net.thevpc.nmvn.lib.service.VersionService;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.core.NSession;
import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.io.NOut;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.toolbox.mvn.util.MavenCliWrapper;
import net.thevpc.nuts.util.NRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * CLI command to clean scanned Maven projects.
 * Uses the workset scanner (same as {@code version scan}) so it respects
 * {@code workset.tson}/{@code nmvn.tson} roots and excludes, and skips
 * {@code target/} copies of poms.
 */
public class MvnCleanSubCommand {

    private final VersionService versionService = new VersionService();

    public MvnCleanSubCommand() {

    }

    public int run(String[] args) {
        NCmdLine cmd = NCmdLine.of(args);
        NRef<Boolean> simpleRef = NRef.of(false);
        NRef<String> worksetRef = NRef.ofNull();
        List<String> roots = new ArrayList<>();
        List<String> excludes = new ArrayList<>();

        while (cmd.hasNext()) {
            if (NSession.of().configureFirst(cmd)) {
                // handled by nuts
            } else if (!cmd.matcher()
                    .when("-s", "--simple").asFlag(a -> simpleRef.set(a.booleanValue()))
                    .when("--workset").asEntry(a -> worksetRef.set(a.stringValue()))
                    .when("--root").asEntry(a -> roots.add(a.stringValue()))
                    .when("-r").asEntry(a -> roots.add(a.stringValue()))
                    .when("--exclude").asEntry(a -> excludes.add(a.stringValue()))
                    .whenNonOption().asArg(a -> roots.add(a.asString().get()))
                    .withDefaults()
                    .anyMatch()) {
                cmd.throwUnexpectedArgument();
            }
        }

        NPath workingDir = NPath.ofUserDirectory();
        NPath cfgFile = NMvnConfigLoader.resolveConfigFile(worksetRef.get(), workingDir);
        NMvnConfig config = NMvnConfigLoader.load(cfgFile);

        // CLI overrides (same semantics as `version scan`)
        if (!roots.isEmpty()) {
            for (String r : roots) {
                NPath p = workingDir.resolve(r);
                if (!p.exists()) {
                    NOut.println(NMsg.ofC("Root does not exist: %s", r));
                    return 1;
                }
                if (!p.isDirectory()) {
                    NOut.println(NMsg.ofC("Root is not a directory: %s", r));
                    return 1;
                }
            }
            config.setRoots(roots);
        }
        if (!excludes.isEmpty()) {
            config.getExcludes().addAll(excludes);
        }

        ScanResult scan;
        try {
            scan = versionService.scan(config, workingDir, false);
        } catch (Exception e) {
            NOut.println(NMsg.ofStyled("Scan failed: " + e.getMessage(), NTextStyle.danger()));
            return 1;
        }

        Map<NId, PomArtifact> artifacts = scan.getArtifacts();
        if (artifacts.isEmpty()) {
            NOut.println(NMsg.ofStyled("No Maven projects found in workset.", NTextStyle.info()));
            return 0;
        }

        List<PomArtifact> ordered = new ArrayList<>(artifacts.values());
        ordered.sort(Comparator.comparing(a -> a.getPath().toString()));

        NOut.println(NMsg.ofStyled(String.format("Found %d Maven project(s).", ordered.size()), NTextStyle.primary4()));

        int successCount = 0;
        List<String> failed = new ArrayList<>();
        for (PomArtifact artifact : ordered) {
            NPath pomFile = artifact.getPath();
            NPath pomDir = pomFile.parent();
            NOut.println(NMsg.ofC("Processing: %s", pomDir));

            NPath targetDir = pomDir.resolve("target");
            if (simpleRef.get()) {
                try {
                    if (targetDir.exists()) {
                        targetDir.delete(true);
                        NOut.println(NMsg.ofStyled("  Deleted target directory", NTextStyle.success()));
                    } else {
                        NOut.println(NMsg.ofStyled("  No target directory found", NTextStyle.info()));
                    }
                    successCount++;
                } catch (Exception e) {
                    failed.add(pomDir.toString());
                    NOut.println(NMsg.ofStyled("  Delete failed: " + e.getMessage(), NTextStyle.danger()));
                }
            } else {
                MavenCliWrapper cli = new MavenCliWrapper();
                cli.setWorkingDirectory(pomDir.toString());
                int r = cli.doMain(new String[]{"clean"});
                if (r == 0) {
                    NOut.println(NMsg.ofStyled("  mvn clean succeeded", NTextStyle.success()));
                    successCount++;
                } else {
                    failed.add(pomDir.toString());
                    NOut.println(NMsg.ofStyled(String.format("  mvn clean failed with code %d", r), NTextStyle.danger()));
                }
            }
        }

        if (!NOut.isPlain()) {
            NObjectElementBuilder obj = NElement.ofObjectBuilder();
            obj.set("status", NElement.ofString(failed.isEmpty() ? "success" : "error"));
            obj.set("total", ordered.size());
            obj.set("succeeded", successCount);
            NArrayElementBuilder failedArr = NElement.ofArrayBuilder();
            for (String f : failed) {
                failedArr.add(NElement.ofString(f));
            }
            obj.set("failed", failedArr.build());
            NOut.println(obj.build());
        } else {
            NOut.println(NMsg.ofStyled(String.format("Completed: %d/%d succeeded", successCount, ordered.size()),
                    successCount == ordered.size() ? NTextStyle.success() : NTextStyle.warn()));
        }
        return failed.isEmpty() ? 0 : 1;
    }
}
