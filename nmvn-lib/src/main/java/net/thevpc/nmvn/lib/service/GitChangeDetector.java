package net.thevpc.nmvn.lib.service;

import net.thevpc.nmvn.lib.config.VersionHistoryStore;
import net.thevpc.nmvn.lib.model.PomArtifact;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.io.NPath;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

public interface GitChangeDetector {

    /**
     * Determines which artifacts changed between the given git ref range.
     *
     * @param fromRef   Starting git ref (commit hash, branch, or tag)
     * @param toRef     Ending git ref (default HEAD)
     * @param artifacts Map of scanned artifacts
     * @param history   Persisted version history store
     * @return List of artifacts whose underlying directories had file changes
     */
    Set<NId> detectChangedArtifacts(String fromRef, String toRef, Map<NId, PomArtifact> artifacts, VersionHistoryStore history);

    class Default implements GitChangeDetector {
        @Override
        public Set<NId> detectChangedArtifacts(String fromRef, String toRef, Map<NId, PomArtifact> artifacts, VersionHistoryStore history) {
            Set<NId> changed = new LinkedHashSet<>();
            if (fromRef == null || fromRef.trim().isEmpty()) {
                return changed;
            }
            String targetRef = (toRef != null && !toRef.trim().isEmpty()) ? toRef.trim() : "HEAD";

            try {
                ProcessBuilder pb = new ProcessBuilder("git", "diff", "--name-only", fromRef.trim(), targetRef);
                Process process = pb.start();
                List<String> changedFiles = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.trim().isEmpty()) {
                            changedFiles.add(line.trim());
                        }
                    }
                }
                process.waitFor();

                // Map changed file paths to artifacts
                for (String file : changedFiles) {
                    for (PomArtifact artifact : artifacts.values()) {
                        NPath pomDir = artifact.getPath().parent();
                        if (pomDir != null) {
                            String dirStr = pomDir.toString().replace('\\', '/');
                            if (file.contains(dirStr) || pomDir.name().equals(file) || file.startsWith(dirStr)) {
                                changed.add(artifact.toGa());
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // In environments without git or non-git directories, return empty set
            }
            return changed;
        }
    }
}
