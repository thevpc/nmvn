package net.thevpc.nuts.toolbox.mvn.subcommands;

import net.thevpc.nmvn.lib.config.NMvnConfig;
import net.thevpc.nmvn.lib.config.NMvnConfigLoader;
import net.thevpc.nmvn.lib.model.PomArtifact;
import net.thevpc.nmvn.lib.service.ScanResult;
import net.thevpc.nmvn.lib.service.TargetIgnoreService;
import net.thevpc.nmvn.lib.service.VersionService;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.concurrent.NCallable;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        NSession scoped = NSession.of().copy();
        return scoped.callWith(NCallable.of(() -> run0(args)));
    }

    private int run0(String[] args) {
        NCmdLine cmd = NCmdLine.of(args);
        NRef<Boolean> simpleRef = NRef.of(false);
        NRef<Boolean> gitIgnoreRef = NRef.of(false);
        NRef<Boolean> noIgnoreRef = NRef.of(false);
        NRef<String> worksetRef = NRef.ofNull();
        List<String> roots = new ArrayList<>();
        List<String> excludes = new ArrayList<>();

        while (cmd.hasNext()) {
            if (NSession.of().configureFirst(cmd)) {
                // handled by nuts
            } else if (!cmd.matcher()
                    .when("-s", "--simple").asFlag(a -> simpleRef.set(a.booleanValue()))
                    .when("--gitignore", "--fix-gitignore").asFlag(a -> gitIgnoreRef.set(a.booleanValue()))
                    .when("--no-ignore").asFlag(a -> noIgnoreRef.set(a.booleanValue()))
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

        if (simpleRef.get() && gitIgnoreRef.get()) {
            NOut.println(NMsg.ofStyled(
                    "--simple and --gitignore are separate modes; run them one at a time.",
                    NTextStyle.danger()));
            return 1;
        }

        NPath workingDir = NPath.ofUserDirectory();
        NPath cfgFile = NMvnConfigLoader.resolveConfigFile(worksetRef.get(), workingDir);
        NMvnConfig config = NMvnConfigLoader.load(cfgFile);

        // CLI overrides (same semantics as `version scan`)
        if (noIgnoreRef.get()) {
            config.setUseDefaultIgnores(false);
        }
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

        if (gitIgnoreRef.get()) {
            return runGitIgnore(ordered);
        }

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

    /**
     * Git hygiene mode: ensure every scanned project's target/ directory is
     * ignored and no target files remain tracked. In dry mode this only reports.
     */
    private int runGitIgnore(List<PomArtifact> ordered) {
        TargetIgnoreService service = new TargetIgnoreService();
        List<TargetIgnoreService.Status> statuses = service.inspect(ordered);
        boolean dry = NSession.of().isDry();

        int issueCount = 0;
        int inspectionErrors = 0;
        int trackedTargets = 0;
        int trackedFiles = 0;
        Set<String> repositoriesNeedingIgnore = new LinkedHashSet<>();
        for (TargetIgnoreService.Status status : statuses) {
            if (status.isIssue()) {
                issueCount++;
            }
            if (status.getError() != null) {
                inspectionErrors++;
            }
            if (status.isGitRepository() && status.getError() == null && !status.isIgnored()) {
                repositoriesNeedingIgnore.add(status.getGitRoot().toString());
            }
            if (status.isTracked()) {
                trackedTargets++;
                trackedFiles += status.getTrackedFileCount();
            }
        }

        TargetIgnoreService.ApplyResult applied = null;
        if (!dry) {
            applied = service.apply(statuses);
        }

        if (!NOut.isPlain()) {
            NObjectElementBuilder obj = NElement.ofObjectBuilder();
            boolean hasErrors = inspectionErrors > 0 || (applied != null && applied.hasErrors());
            obj.set("status", NElement.ofString(hasErrors ? "error" : "success"));
            obj.set("mode", NElement.ofString("gitignore"));
            obj.set("dry", dry);
            obj.set("projects", statuses.size());
            obj.set("issues", issueCount);
            obj.set("inspectionErrors", inspectionErrors);
            obj.set("ignoreFilesUpdated", applied != null ? applied.getIgnoreFilesUpdated() : 0);
            obj.set("targetsUntracked", applied != null ? applied.getTargetsUntracked() : 0);
            obj.set("filesUntracked", applied != null ? applied.getFilesUntracked() : 0);
            if (dry) {
                obj.set("wouldUpdateIgnoreFiles", repositoriesNeedingIgnore.size());
                obj.set("wouldUntrackTargets", trackedTargets);
                obj.set("wouldUntrackFiles", trackedFiles);
            }

            NArrayElementBuilder detailsArr = NElement.ofArrayBuilder();
            for (TargetIgnoreService.Status status : statuses) {
                NObjectElementBuilder detail = NElement.ofObjectBuilder();
                detail.set("pomDir", NElement.ofString(status.getPomDir().toString()));
                detail.set("targetDir", NElement.ofString(status.getTargetDir().toString()));
                if (status.getGitRoot() != null) {
                    detail.set("gitRoot", NElement.ofString(status.getGitRoot().toString()));
                }
                detail.set("ignored", status.isIgnored());
                detail.set("tracked", status.isTracked());
                detail.set("trackedFiles", status.getTrackedFileCount());
                detail.set("message", NElement.ofString(status.getMessage()));
                detailsArr.add(detail.build());
            }
            obj.set("details", detailsArr.build());

            NArrayElementBuilder errorsArr = NElement.ofArrayBuilder();
            if (applied != null) {
                for (String error : applied.getErrors()) {
                    errorsArr.add(NElement.ofString(error));
                }
            }
            obj.set("errors", errorsArr.build());
            NOut.println(obj.build());
            return hasErrors ? 1 : 0;
        }

        NOut.println(NMsg.ofStyled("Git target audit:", NTextStyle.primary4()));
        for (TargetIgnoreService.Status status : statuses) {
            if (!status.isGitRepository()) {
                NOut.println(NMsg.ofC("  [SKIP] %s: %s", status.getTargetDir(), status.getMessage()));
            } else if (status.getError() != null) {
                NOut.println(NMsg.ofStyled(String.format("  [ERROR] %s: %s",
                        status.getTargetDir(), status.getMessage()), NTextStyle.danger()));
            } else if (status.isIssue()) {
                NOut.println(NMsg.ofStyled(String.format("  [ISSUE] %s: %s",
                        status.getTargetDir(), status.getMessage()), NTextStyle.warn()));
            } else {
                NOut.println(NMsg.ofC("  [OK] %s: %s", status.getTargetDir(), status.getMessage()));
            }
        }

        if (dry) {
            if (inspectionErrors > 0) {
                NOut.println(NMsg.ofStyled(String.format(
                        "DRY RUN: cannot inspect %d project(s); no changes were made.",
                        inspectionErrors), NTextStyle.danger()));
                return 1;
            }
            if (issueCount == 0) {
                NOut.println(NMsg.ofStyled(
                        "DRY RUN: no changes needed; target/ is already ignored and untracked.",
                        NTextStyle.success()));
            } else {
                NOut.println(NMsg.ofStyled(String.format(
                        "DRY RUN: would update %d .gitignore file(s) and untrack %d target dir(s) (%d file(s)).",
                        repositoriesNeedingIgnore.size(), trackedTargets, trackedFiles), NTextStyle.warn()));
            }
            return 0;
        }

        if (applied == null || applied.hasErrors()) {
            if (applied != null) {
                for (String error : applied.getErrors()) {
                    NOut.println(NMsg.ofStyled("  Error: " + error, NTextStyle.danger()));
                }
            }
        }
        if (applied != null && !applied.hasErrors()) {
            if (applied.getIgnoreFilesUpdated() == 0 && applied.getTargetsUntracked() == 0) {
                NOut.println(NMsg.ofStyled(
                        "No changes required; target/ is already ignored and untracked.",
                        NTextStyle.success()));
            } else {
                NOut.println(NMsg.ofStyled(String.format(
                        "Updated %d .gitignore file(s); untracked %d target dir(s) (%d file(s)); working-tree files were kept.",
                        applied.getIgnoreFilesUpdated(), applied.getTargetsUntracked(), applied.getFilesUntracked()),
                        NTextStyle.success()));
            }
        }
        if (inspectionErrors > 0) {
            NOut.println(NMsg.ofStyled(String.format(
                    "Could not inspect %d project(s); see the errors above.",
                    inspectionErrors), NTextStyle.danger()));
        }
        return inspectionErrors > 0 || (applied != null && applied.hasErrors()) ? 1 : 0;
    }
}
