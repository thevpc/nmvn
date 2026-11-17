package net.thevpc.nmvn.lib.service;

import net.thevpc.nmvn.lib.model.PomArtifact;
import net.thevpc.nuts.io.NPath;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Checks whether each scanned Maven project's {@code target/} directory is
 * ignored by Git, and can untrack it and add it to the repository
 * {@code .gitignore}.
 */
public class TargetIgnoreService {

    private static final String TARGET_DIR = "target";
    private static final String IGNORE_RULE = "target/";

    public static class Status {
        private final PomArtifact artifact;
        private final NPath pomDir;
        private final NPath targetDir;
        private final NPath gitRoot;
        private final NPath gitIgnoreFile;
        private final boolean ignored;
        private final boolean tracked;
        private final int trackedFileCount;
        private final String error;

        public Status(PomArtifact artifact, NPath pomDir, NPath targetDir, NPath gitRoot,
                      NPath gitIgnoreFile, boolean ignored, boolean tracked,
                      int trackedFileCount, String error) {
            this.artifact = artifact;
            this.pomDir = pomDir;
            this.targetDir = targetDir;
            this.gitRoot = gitRoot;
            this.gitIgnoreFile = gitIgnoreFile;
            this.ignored = ignored;
            this.tracked = tracked;
            this.trackedFileCount = trackedFileCount;
            this.error = error;
        }

        public PomArtifact getArtifact() {
            return artifact;
        }

        public NPath getPomDir() {
            return pomDir;
        }

        public NPath getTargetDir() {
            return targetDir;
        }

        public NPath getGitRoot() {
            return gitRoot;
        }

        public NPath getGitIgnoreFile() {
            return gitIgnoreFile;
        }

        public boolean isGitRepository() {
            return gitRoot != null;
        }

        public boolean isIgnored() {
            return ignored;
        }

        public boolean isTracked() {
            return tracked;
        }

        public int getTrackedFileCount() {
            return trackedFileCount;
        }

        public String getError() {
            return error;
        }

        public boolean isIssue() {
            return isGitRepository() && error == null && (!ignored || tracked);
        }

        public String getMessage() {
            if (!isGitRepository()) {
                return "not a Git repository";
            }
            if (error != null) {
                return "cannot inspect Git state: " + error;
            }
            if (!ignored && tracked) {
                return "target/ is not ignored and " + trackedFileCount
                        + " tracked file(s) remain in the index";
            }
            if (!ignored) {
                return "target/ is not ignored";
            }
            if (tracked) {
                return trackedFileCount + " tracked file(s) remain in the index";
            }
            return "target/ is ignored";
        }
    }

    public static class ApplyResult {
        private int ignoreFilesUpdated;
        private int targetsUntracked;
        private int filesUntracked;
        private final List<String> errors = new ArrayList<>();

        public int getIgnoreFilesUpdated() {
            return ignoreFilesUpdated;
        }

        public int getTargetsUntracked() {
            return targetsUntracked;
        }

        public int getFilesUntracked() {
            return filesUntracked;
        }

        public List<String> getErrors() {
            return Collections.unmodifiableList(errors);
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    public List<Status> inspect(ScanResult scanResult) {
        return inspect(scanResult != null ? scanResult.getArtifacts().values() : Collections.<PomArtifact>emptyList());
    }

    public List<Status> inspect(Collection<PomArtifact> artifacts) {
        List<PomArtifact> ordered = new ArrayList<>(artifacts);
        ordered.sort(Comparator.comparing(a -> a.getPath().toString()));

        List<Status> statuses = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PomArtifact artifact : ordered) {
            NPath pomDir = artifact.getPath().parent();
            if (pomDir == null) {
                continue;
            }
            NPath targetDir = pomDir.resolve(TARGET_DIR);
            String targetKey = targetDir.normalize().toAbsolute().toString();
            if (!seen.add(targetKey)) {
                continue;
            }

            NPath gitRoot = findGitRoot(pomDir);
            if (gitRoot == null) {
                statuses.add(new Status(artifact, pomDir, targetDir, null, null,
                        false, false, 0, null));
                continue;
            }

            NPath gitIgnoreFile = gitRoot.resolve(".gitignore");
            Path repoPath = gitRoot.toPath().get();
            Path targetPath = targetDir.toPath().get();
            String relTarget = repoPath.relativize(targetPath).toString().replace('\\', '/');
            String relTargetDir = relTarget.isEmpty() ? TARGET_DIR : relTarget;

            boolean ignored = false;
            boolean tracked = false;
            int trackedFileCount = 0;
            String error = null;

            CommandResult ignoreCheck = runGit(repoPath, "check-ignore", "--no-index", "-q", "--",
                    relTargetDir + "/");
            if (ignoreCheck.exitCode == 0) {
                ignored = true;
            } else if (ignoreCheck.exitCode != 1) {
                error = firstLine(ignoreCheck.output);
            }

            if (error == null) {
                CommandResult trackedCheck = runGit(repoPath, "--literal-pathspecs", "ls-files", "--",
                        relTargetDir);
                if (trackedCheck.exitCode != 0) {
                    error = firstLine(trackedCheck.output);
                } else {
                    trackedFileCount = countLines(trackedCheck.output);
                    tracked = trackedFileCount > 0;
                }
            }

            statuses.add(new Status(artifact, pomDir, targetDir, gitRoot, gitIgnoreFile,
                    ignored, tracked, trackedFileCount, error));
        }
        return statuses;
    }

    /**
     * Adds {@code target/} to the repository {@code .gitignore} when needed and
     * removes tracked target files from the index. Working-tree files are kept.
     * Callers are responsible for implementing dry-run by not calling this method.
     */
    public ApplyResult apply(List<Status> statuses) {
        ApplyResult result = new ApplyResult();
        Map<String, List<Status>> byRepository = new LinkedHashMap<>();
        for (Status status : statuses) {
            if (status.isGitRepository() && status.getError() == null) {
                String key = status.getGitRoot().toString();
                byRepository.computeIfAbsent(key, k -> new ArrayList<>()).add(status);
            }
        }

        for (List<Status> repoStatuses : byRepository.values()) {
            if (repoStatuses.isEmpty()) {
                continue;
            }
            Status first = repoStatuses.get(0);
            NPath gitRoot = first.getGitRoot();
            NPath gitIgnoreFile = first.getGitIgnoreFile();
            Path repoPath = gitRoot.toPath().get();

            boolean needsIgnoreRule = false;
            for (Status status : repoStatuses) {
                if (!status.isIgnored()) {
                    needsIgnoreRule = true;
                    break;
                }
            }
            if (needsIgnoreRule) {
                try {
                    appendIgnoreRule(gitIgnoreFile, IGNORE_RULE);
                    result.ignoreFilesUpdated++;
                } catch (Exception e) {
                    result.errors.add("Cannot update " + gitIgnoreFile + ": " + e.getMessage());
                }
            }

            for (Status status : repoStatuses) {
                if (!status.isTracked()) {
                    continue;
                }
                Path targetPath = status.getTargetDir().toPath().get();
                String relTarget = repoPath.relativize(targetPath).toString().replace('\\', '/');
                CommandResult rm = runGit(repoPath, "--literal-pathspecs", "rm", "-r", "--cached",
                        "--ignore-unmatch", "--", relTarget);
                if (rm.exitCode == 0) {
                    result.targetsUntracked++;
                    result.filesUntracked += status.getTrackedFileCount();
                } else {
                    result.errors.add("Cannot untrack " + status.getTargetDir() + ": "
                            + firstLine(rm.output));
                }
            }
        }
        return result;
    }

    private static void appendIgnoreRule(NPath gitIgnoreFile, String rule) {
        String content = gitIgnoreFile.isRegularFile() ? gitIgnoreFile.readString() : "";
        if (!content.isEmpty() && !content.endsWith("\n") && !content.endsWith("\r")) {
            content += "\n";
        }
        if (!content.isEmpty()) {
            content += "\n";
        }
        content += "# Maven build output\n" + rule + "\n";
        gitIgnoreFile.writeString(content);
    }

    private static NPath findGitRoot(NPath directory) {
        if (directory == null) {
            return null;
        }
        Path current = directory.toPath().get().toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return NPath.of(current);
            }
            current = current.getParent();
        }
        return null;
    }

    private static int countLines(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String line : text.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        int index = trimmed.indexOf('\n');
        return (index < 0 ? trimmed : trimmed.substring(0, index)).trim();
    }

    private static CommandResult runGit(Path directory, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        Collections.addAll(command, args);
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(directory.toFile());
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
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(-1, "git command timed out");
            }
            return new CommandResult(process.exitValue(),
                    new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new CommandResult(-1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, e.getMessage());
        }
    }

    private static final class CommandResult {
        private final int exitCode;
        private final String output;

        private CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
