package net.thevpc.nmvn.lib.service;

import net.thevpc.nmvn.lib.config.NMvnConfig;
import net.thevpc.nmvn.lib.config.VersionHistoryStore;
import net.thevpc.nmvn.lib.diagnostic.ArtifactChecker;
import net.thevpc.nmvn.lib.diagnostic.DiagnosticIssue;
import net.thevpc.nmvn.lib.diagnostic.DiagnosticReport;
import net.thevpc.nmvn.lib.diagnostic.DiagnosticRule;
import net.thevpc.nmvn.lib.diagnostic.DiagnosticSeverity;
import net.thevpc.nmvn.lib.exception.StrictSnapshotException;
import net.thevpc.nmvn.lib.graph.DependencyEdge;
import net.thevpc.nmvn.lib.graph.MavenDependencyGraph;
import net.thevpc.nmvn.lib.model.*;
import net.thevpc.nmvn.lib.modifier.PomModifier;
import net.thevpc.nmvn.lib.parser.PropertyResolver;
import net.thevpc.nmvn.lib.scanner.PomScanner;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.io.IOException;
import java.util.*;

public class VersionService {

    private final PomScanner scanner = new PomScanner();
    private final PomModifier modifier = new PomModifier();

    public ScanResult scan(NMvnConfig config, NPath workingDir) {
        return scan(config, workingDir, true);
    }

    public ScanResult scan(NMvnConfig config, NPath workingDir, boolean detectCycles)  {
        List<String> warnings = new ArrayList<>();
        Map<NId, PomArtifact> artifacts = scanner.scan(config, workingDir, warnings);
        MavenDependencyGraph graph = new MavenDependencyGraph(artifacts);
        if (detectCycles) {
            graph.detectCycles();
        }
        return new ScanResult(artifacts, graph, warnings);
    }

    public DiagnosticReport check(NMvnConfig config, NPath workingDir)  {
        ScanResult scanResult = scan(config, workingDir, false);
        return check(scanResult, config);
    }

    public DiagnosticReport check(ScanResult scanResult, NMvnConfig config) {
        DiagnosticReport report = new ArtifactChecker().check(scanResult, config);
        if (scanResult.getWarnings().isEmpty()) {
            return report;
        }
        List<DiagnosticIssue> issues = new ArrayList<>(report.getIssues());
        for (String warning : scanResult.getWarnings()) {
            issues.add(new DiagnosticIssue(
                    DiagnosticRule.INCOMPLETE_SCAN,
                    DiagnosticSeverity.ERROR,
                    (NId) null, null, warning));
        }
        return new DiagnosticReport(issues);
    }

    /**
     * Bumps the configured or explicitly selected artifacts. Empty target sets
     * are rejected so a missing selector cannot silently mean "bump everything".
     */
    public BumpResult bump(NMvnConfig config, NPath workingDir, List<BumpInstruction> explicitBumps,
                           Boolean cascadeVersionsOverride, boolean apply)  {
        return bump(config, workingDir, explicitBumps, null, cascadeVersionsOverride, false, apply);
    }

    public BumpResult bump(NMvnConfig config, NPath workingDir, List<BumpInstruction> explicitBumps,
                           BumpPolicy.IncrementType incrementOverride,
                           Boolean cascadeVersionsOverride, boolean force, boolean apply)  {
        BumpPolicy.CascadePolicy cp = cascadeVersionsOverride == null ? null :
                (cascadeVersionsOverride ? BumpPolicy.CascadePolicy.CASCADE_VERSIONS : BumpPolicy.CascadePolicy.CASCADE_REFERENCES_ONLY);
        return bump(config, workingDir, explicitBumps, incrementOverride, cp, force, apply);
    }

    public BumpResult bump(NMvnConfig config, NPath workingDir, List<BumpInstruction> explicitBumps,
                           BumpPolicy.IncrementType incrementOverride,
                           BumpPolicy.CascadePolicy cascadePolicyOverride, boolean force, boolean apply)  {
        ScanResult scanResult = scan(config, workingDir);
        requireCompleteScan(scanResult);
        Map<NId, PomArtifact> artifacts = scanResult.getArtifacts();
        MavenDependencyGraph graph = scanResult.getGraph();

        BumpPolicy.CascadePolicy cascadePolicy = cascadePolicyOverride != null ? cascadePolicyOverride
                : config.getBumpPolicy().getCascadePolicy();

        BumpPolicy.IncrementType defaultInc = incrementOverride != null ? incrementOverride : config.getBumpPolicy().getDefaultIncrement();

        // Explicit targets are required; broad workspace bumps should be deliberate.
        Map<NId, BumpInstruction> bumpMap = new LinkedHashMap<>();
        if (config.getInstructions() != null) {
            for (BumpInstruction inst : config.getInstructions()) {
                bumpMap.put(inst.toGa(), inst);
            }
        }
        if (explicitBumps != null && !explicitBumps.isEmpty()) {
            for (BumpInstruction inst : explicitBumps) {
                bumpMap.put(inst.toGa(), inst);
            }
        } else if (bumpMap.isEmpty()) {
            throw new NIllegalArgumentException(NMsg.ofP(
                    "No bump targets specified. Select artifacts explicitly before bumping."));
        }

        Map<NId, String> targetVersions = new LinkedHashMap<>();
        for (Map.Entry<NId, BumpInstruction> entry : bumpMap.entrySet()) {
            NId ga = entry.getKey();
            BumpInstruction inst = entry.getValue();
            PomArtifact artifact = artifacts.get(ga);
            if(artifact==null){
                throw new NIllegalArgumentException(NMsg.ofC("failed to find artifact %s",ga));
            }
            String currentVer = artifact != null ? artifact.getResolvedVersion() : null;
            String toVer = inst.getToVersion();

            if (toVer != null && !BumpPolicy.IncrementType.isIncrementKeyword(toVer)) {
                // Explicit version passed
                targetVersions.put(ga, toVer);
            } else {
                // Auto-increment
                BumpPolicy.IncrementType inc = (toVer != null && BumpPolicy.IncrementType.isIncrementKeyword(toVer))
                        ? BumpPolicy.IncrementType.parse(toVer)
                        : defaultInc;

                boolean isSnapshot = config.getBumpPolicy().isSnapshot(currentVer);
                if (isSnapshot && !force) {
                    // Already a snapshot: do not advance unless forced
                } else {
                    String nextVer = config.getBumpPolicy().bumpVersion(currentVer, inc);
                    targetVersions.put(ga, nextVer);
                }
            }
        }

        // Cascade to dependents only when CASCADE_VERSIONS is enabled
        if (cascadePolicy == BumpPolicy.CascadePolicy.CASCADE_VERSIONS) {
            Queue<NId> dirtyQueue = new ArrayDeque<>(targetVersions.keySet());
            Set<NId> visited = new HashSet<>(targetVersions.keySet());

            while (!dirtyQueue.isEmpty()) {
                NId currGa = dirtyQueue.poll();
                Set<NId> dependents = graph.getDirectDependents(currGa);

                for (NId depGa : dependents) {
                    if (!targetVersions.containsKey(depGa)) {
                        PomArtifact depArtifact = artifacts.get(depGa);
                        if (depArtifact != null) {
                            String currentVer = depArtifact.getResolvedVersion();
                            boolean isSnapshot = config.getBumpPolicy().isSnapshot(currentVer);

                            if (!isSnapshot || force) {
                                String nextVer = config.getBumpPolicy().bumpVersion(currentVer, defaultInc);
                                targetVersions.put(depGa, nextVer);
                                if (visited.add(depGa)) {
                                    dirtyQueue.add(depGa);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Apply modifications in memory
        List<PomChange> changes = applyModifications(artifacts, targetVersions, cascadePolicy);

        if (apply) {
            applyChangesToDisk(changes);
            recordHistory(config, workingDir, targetVersions);
        }

        return new BumpResult(changes, targetVersions);
    }

    public BumpResult update(NMvnConfig config, NPath workingDir, Map<NId, String> explicitUpdates,
                             boolean apply)  {
        return update(config, workingDir, explicitUpdates, null, apply);
    }

    public BumpResult update(NMvnConfig config, NPath workingDir, Map<NId, String> explicitUpdates,
                             BumpPolicy.CascadePolicy cascadePolicyOverride,
                             boolean apply)  {
        ScanResult scanResult = scan(config, workingDir);
        requireCompleteScan(scanResult);
        Map<NId, PomArtifact> artifacts = scanResult.getArtifacts();
        MavenDependencyGraph graph = scanResult.getGraph();

        BumpPolicy.CascadePolicy cascadePolicy = cascadePolicyOverride != null ? cascadePolicyOverride
                : config.getBumpPolicy().getCascadePolicy();

        Map<NId, String> targetVersions = new LinkedHashMap<>();
        if (explicitUpdates != null) {
            targetVersions.putAll(explicitUpdates);
        }

        if (targetVersions.isEmpty()) {
            return new BumpResult(Collections.emptyList(), Collections.emptyMap());
        }

        // Release Immutability Cascade (only if CASCADE_VERSIONS):
        // Any dependent whose POM is modified:
        // - if already snapshot -> version is untouched (only dependency reference is updated)
        // - if release (non-snapshot) -> must be bumped to snapshot
        if (cascadePolicy == BumpPolicy.CascadePolicy.CASCADE_VERSIONS) {
            Queue<NId> dirtyQueue = new ArrayDeque<>(targetVersions.keySet());
            Set<NId> visited = new HashSet<>(targetVersions.keySet());

            while (!dirtyQueue.isEmpty()) {
                NId currGa = dirtyQueue.poll();
                Set<NId> dependents = graph.getDirectDependents(currGa);

                for (NId depGa : dependents) {
                    if (!targetVersions.containsKey(depGa)) {
                        PomArtifact depArtifact = artifacts.get(depGa);
                        if (depArtifact != null) {
                            String currentVer = depArtifact.getResolvedVersion();
                            if (!config.getBumpPolicy().isSnapshot(currentVer)) {
                                String nextVer = config.getBumpPolicy().bumpVersion(currentVer, config.getBumpPolicy().getDefaultIncrement());
                                targetVersions.put(depGa, nextVer);
                                if (visited.add(depGa)) {
                                    dirtyQueue.add(depGa);
                                }
                            }
                        }
                    }
                }
            }
        }

        List<PomChange> changes = applyModifications(artifacts, targetVersions, cascadePolicy);

        if (apply) {
            applyChangesToDisk(changes);
            recordHistory(config, workingDir, targetVersions);
        }

        return new BumpResult(changes, targetVersions);
    }

    public ReleaseResult release(NMvnConfig config, NPath workingDir, Map<NId, String> explicitReleases,
                                 boolean strict, boolean apply)  {
        ScanResult scanResult = scan(config, workingDir);
        requireCompleteScan(scanResult);
        Map<NId, PomArtifact> artifacts = scanResult.getArtifacts();

        Map<NId, String> targetVersions = new LinkedHashMap<>();
        List<String> unmanagedSnapshots = new ArrayList<>();

        // Check each artifact in workspace
        for (PomArtifact artifact : artifacts.values()) {
            NId ga = artifact.toGa();
            String explicit = explicitReleases != null ? explicitReleases.get(ga) : null;
            if (explicit != null) {
                targetVersions.put(ga, explicit);
            } else if (artifact.getResolvedVersion() != null && artifact.getResolvedVersion().contains("-SNAPSHOT")) {
                String releaseVer = config.getBumpPolicy().toReleaseVersion(artifact.getResolvedVersion());
                targetVersions.put(ga, releaseVer);
            }

            // Check external snapshot references in strict mode
            for (PomDependency ref : artifact.getAllReferences()) {
                if (!artifacts.containsKey(ref.toGa())) {
                    String refVer = ref.getResolvedVersion();
                    if (refVer != null && refVer.contains("-SNAPSHOT")) {
                        unmanagedSnapshots.add(artifact.getId().shortName() + " -> " + MavenCoord.toGavString(ref.getId()));
                    }
                }
            }
        }

        if (strict && !unmanagedSnapshots.isEmpty()) {
            throw new StrictSnapshotException(unmanagedSnapshots);
        }

        List<PomChange> changes = applyModifications(artifacts, targetVersions);

        if (apply) {
            applyChangesToDisk(changes);
            recordHistory(config, workingDir, targetVersions);
        }

        return new ReleaseResult(changes, targetVersions, unmanagedSnapshots);
    }

    private List<PomChange> applyModifications(Map<NId, PomArtifact> artifacts, Map<NId, String> targetVersions)  {
        return applyModifications(artifacts, targetVersions, BumpPolicy.CascadePolicy.CASCADE_REFERENCES_ONLY);
    }

    private List<PomChange> applyModifications(Map<NId, PomArtifact> artifacts, Map<NId, String> targetVersions, BumpPolicy.CascadePolicy cascadePolicy)  {
        // Map file paths to in-memory modified text content
        Map<NPath, String> contentByPath = new LinkedHashMap<>();
        for (PomArtifact a : artifacts.values()) {
            if (!contentByPath.containsKey(a.getPath())) {
                contentByPath.put(a.getPath(), a.getPath().readString());
            }
        }

        // 1. Update artifact own versions or property-defined versions
        for (Map.Entry<NId, String> entry : targetVersions.entrySet()) {
            NId ga = entry.getKey();
            String newVer = entry.getValue();
            PomArtifact artifact = artifacts.get(ga);
            if (artifact == null) continue;

            NPath pomPath = artifact.getPath();
            String content = contentByPath.get(pomPath);

            if (artifact.isVersionPropertyIndirected()) {
                String propName = artifact.getVersionPropertyName();
                NPath definingPom = PropertyResolver.findDefiningPomForProperty(propName, artifact, artifacts);
                if (definingPom == null) {
                    throw new IllegalArgumentException("Cannot locate defining POM for project version property "
                            + propName + " in " + artifact.getPath());
                }
                String defContent = contentByPath.get(definingPom);
                if (defContent == null) {
                    throw new IllegalArgumentException("Refusing to edit project version property outside the scanned workspace: "
                            + definingPom);
                }
                contentByPath.put(definingPom, PomModifier.updateProperty(defContent, propName, newVer));
            } else if (artifact.getRawVersion() != null) {
                contentByPath.put(pomPath, PomModifier.updateProjectVersion(content, newVer));
            }
        }

        // 2. Cascade references (parent, dependencies, BOMs, plugins)
        if (cascadePolicy != null && cascadePolicy != BumpPolicy.CascadePolicy.NONE) {
            Set<String> updatedProperties = new HashSet<>();

        for (PomArtifact artifact : artifacts.values()) {
            NPath pomPath = artifact.getPath();

            // Check parent update
            if (artifact.getParentId() != null) {
                NId parentGa = artifact.getParentId().shortId();
                if (targetVersions.containsKey(parentGa)) {
                    String newParentVer = targetVersions.get(parentGa);
                    String content = contentByPath.get(pomPath);
                    contentByPath.put(pomPath, PomModifier.updateParentVersion(content, newParentVer));
                }
            }

            // Check dependencies, dependencyManagement, plugins
            List<PomDependency> allRefs = artifact.getAllReferences();
            for (PomDependency ref : allRefs) {
                if (ref.getEdgeType() == DependencyEdgeType.PARENT) {
                    continue;
                }
                NId refGa = ref.toGa();
                if (targetVersions.containsKey(refGa)) {
                    String newVer = targetVersions.get(refGa);

                    if (ref.isPropertyIndirected()) {
                        String propName = ref.getVersionPropertyName();
                        NPath definingPom = ref.getPropertyDefiningPom();
                        if (definingPom != null && contentByPath.containsKey(definingPom)) {
                            String propKey = definingPom.toString() + "#" + propName;
                            if (updatedProperties.add(propKey)) {
                                String defContent = contentByPath.get(definingPom);
                                contentByPath.put(definingPom, PomModifier.updateProperty(defContent, propName, newVer));
                            }
                        } else if (ref.getRawVersion() != null) {
                            throw new IllegalArgumentException("Cannot safely update property " + propName
                                    + " for " + ref.toGa() + ": defining POM is outside the scanned workspace");
                        }
                    } else {
                        // Literal version reference
                        String content = contentByPath.get(pomPath);
                        if (ref.getEdgeType() == DependencyEdgeType.PLUGIN) {
                            contentByPath.put(pomPath, PomModifier.updatePluginVersion(content, ref.getGroupId(), ref.getArtifactId(), newVer));
                        } else {
                            contentByPath.put(pomPath, PomModifier.updateDependencyVersion(content, ref.getGroupId(), ref.getArtifactId(), newVer));
                        }
                    }
                }
            }
        }
        }

        // 3. Build PomChange list
        List<PomChange> changes = new ArrayList<>();
        for (Map.Entry<NPath, String> entry : contentByPath.entrySet()) {
            NPath p = entry.getKey();
            String newContent = entry.getValue();
            PomChange change = modifier.createPomChange(p, newContent);
            if (change.hasChanges()) {
                changes.add(change);
            }
        }
        return changes;
    }

    private void applyChangesToDisk(List<PomChange> changes)  {
        Map<java.nio.file.Path, java.nio.file.Path> staged = new LinkedHashMap<>();
        Map<java.nio.file.Path, java.nio.file.Path> backups = new LinkedHashMap<>();
        List<java.nio.file.Path> committed = new ArrayList<>();
        try {
            for (PomChange change : changes) {
                if (!change.hasChanges()) {
                    continue;
                }
                java.nio.file.Path target = change.getPomFile().toPath().get();
                if (java.nio.file.Files.isSymbolicLink(target)) {
                    throw new IOException("Refusing to replace a symbolic-link POM: " + target);
                }
                String actual = change.getPomFile().readString();
                if (!change.getOriginalContent().equals(actual)) {
                    throw new IOException("POM changed after planning; refusing to overwrite: " + target);
                }
                java.nio.file.Path parent = target.getParent();
                java.nio.file.Path replacement = java.nio.file.Files.createTempFile(parent, ".nmvn-", ".new");
                staged.put(target, replacement);
                java.nio.file.Path backup = java.nio.file.Files.createTempFile(parent, ".nmvn-", ".bak");
                backups.put(target, backup);
                java.nio.file.Files.write(replacement, change.getNewContent().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                java.nio.file.Files.copy(target, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                try {
                    java.nio.file.Files.setPosixFilePermissions(replacement,
                            java.nio.file.Files.getPosixFilePermissions(target));
                } catch (UnsupportedOperationException ignored) {
                    // Non-POSIX filesystems do not expose permission bits.
                }
            }
            for (PomChange change : changes) {
                if (change.hasChanges() && !change.getOriginalContent().equals(change.getPomFile().readString())) {
                    throw new IOException("POM changed while preparing updates; refusing to overwrite: "
                            + change.getPomFile());
                }
            }
            for (Map.Entry<java.nio.file.Path, java.nio.file.Path> entry : staged.entrySet()) {
                moveReplacing(entry.getValue(), entry.getKey());
                committed.add(entry.getKey());
            }
        } catch (Exception failure) {
            for (int i = committed.size() - 1; i >= 0; i--) {
                java.nio.file.Path target = committed.get(i);
                try {
                    moveReplacing(backups.get(target), target);
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw net.thevpc.nuts.util.NException.ofUncheckedException(failure);
        } finally {
            for (java.nio.file.Path path : staged.values()) {
                try { java.nio.file.Files.deleteIfExists(path); } catch (IOException ignored) { }
            }
            for (java.nio.file.Path path : backups.values()) {
                try { java.nio.file.Files.deleteIfExists(path); } catch (IOException ignored) { }
            }
        }
    }

    private void moveReplacing(java.nio.file.Path source, java.nio.file.Path target) throws IOException {
        try {
            java.nio.file.Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void requireCompleteScan(ScanResult scanResult) {
        if (!scanResult.getWarnings().isEmpty()) {
            throw new NIllegalArgumentException(NMsg.ofC(
                    "Refusing to modify versions from an incomplete scan: %s",
                    String.join("; ", scanResult.getWarnings())));
        }
    }

    private void recordHistory(NMvnConfig config, NPath workingDir, Map<NId, String> bumped) {
        String histPath = config.getHistoryFile();
        if (histPath != null && !histPath.trim().isEmpty()) {
            NPath p = workingDir.resolve(histPath);
            VersionHistoryStore store = new VersionHistoryStore(p);
            String commit = getGitCommitHash();
            for (Map.Entry<NId, String> e : bumped.entrySet()) {
                store.record(commit, e.getKey().groupId(), e.getKey().artifactId(), e.getValue());
            }
            store.save();
        }
    }

    private String getGitCommitHash() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "HEAD").start();
            try (Scanner s = new Scanner(p.getInputStream())) {
                if (s.hasNext()) {
                    return s.next().trim();
                }
            }
        } catch (Exception ignored) {
        }
        return "UNKNOWN";
    }
}
