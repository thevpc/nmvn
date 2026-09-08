package net.thevpc.nmvn.lib.service;

import net.thevpc.nmvn.lib.config.NMvnConfig;
import net.thevpc.nmvn.lib.config.VersionHistoryStore;
import net.thevpc.nmvn.lib.diagnostic.ArtifactChecker;
import net.thevpc.nmvn.lib.diagnostic.DiagnosticReport;
import net.thevpc.nmvn.lib.exception.StrictSnapshotException;
import net.thevpc.nmvn.lib.graph.DependencyEdge;
import net.thevpc.nmvn.lib.graph.MavenDependencyGraph;
import net.thevpc.nmvn.lib.model.*;
import net.thevpc.nmvn.lib.modifier.PomModifier;
import net.thevpc.nmvn.lib.scanner.PomScanner;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.io.NPath;

import java.io.IOException;
import java.util.*;

public class VersionService {

    private final PomScanner scanner = new PomScanner();
    private final PomModifier modifier = new PomModifier();

    public ScanResult scan(NMvnConfig config, NPath workingDir) throws IOException {
        return scan(config, workingDir, true);
    }

    public ScanResult scan(NMvnConfig config, NPath workingDir, boolean detectCycles) throws IOException {
        Map<NId, PomArtifact> artifacts = scanner.scan(config, workingDir);
        MavenDependencyGraph graph = new MavenDependencyGraph(artifacts);
        if (detectCycles) {
            graph.detectCycles();
        }
        return new ScanResult(artifacts, graph);
    }

    public DiagnosticReport check(NMvnConfig config, NPath workingDir) throws IOException {
        ScanResult scanResult = scan(config, workingDir, false);
        return new ArtifactChecker().check(scanResult, config);
    }

    public BumpResult bump(NMvnConfig config, NPath workingDir, List<BumpInstruction> explicitBumps,
                           Boolean cascadeVersionsOverride, boolean apply) throws IOException {
        return bump(config, workingDir, explicitBumps, null, cascadeVersionsOverride, false, apply);
    }

    public BumpResult bump(NMvnConfig config, NPath workingDir, List<BumpInstruction> explicitBumps,
                           BumpPolicy.IncrementType incrementOverride,
                           Boolean cascadeVersionsOverride, boolean force, boolean apply) throws IOException {
        ScanResult scanResult = scan(config, workingDir);
        Map<NId, PomArtifact> artifacts = scanResult.getArtifacts();
        MavenDependencyGraph graph = scanResult.getGraph();

        boolean cascadeVersions = cascadeVersionsOverride != null ? cascadeVersionsOverride
                : config.getBumpPolicy().getCascadePolicy() == BumpPolicy.CascadePolicy.CASCADE_VERSIONS;

        BumpPolicy.IncrementType defaultInc = incrementOverride != null ? incrementOverride : config.getBumpPolicy().getDefaultIncrement();

        // Target artifacts: if explicitBumps is empty or null, target all workspace artifacts
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
            for (NId ga : artifacts.keySet()) {
                bumpMap.put(ga, new BumpInstruction(ga.groupId(), ga.artifactId(), null));
            }
        }

        Map<NId, String> targetVersions = new LinkedHashMap<>();
        for (Map.Entry<NId, BumpInstruction> entry : bumpMap.entrySet()) {
            NId ga = entry.getKey();
            BumpInstruction inst = entry.getValue();
            PomArtifact artifact = artifacts.get(ga);
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

        // Cascade to dependents
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

                        if (cascadeVersions) {
                            if (!isSnapshot || force) {
                                String nextVer = config.getBumpPolicy().bumpVersion(currentVer, defaultInc);
                                targetVersions.put(depGa, nextVer);
                                if (visited.add(depGa)) {
                                    dirtyQueue.add(depGa);
                                }
                            }
                        } else {
                            // Release immutability: if release, must bump to snapshot
                            if (!isSnapshot) {
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

        // Apply modifications in memory
        List<PomChange> changes = applyModifications(artifacts, targetVersions);

        if (apply) {
            applyChangesToDisk(changes);
            recordHistory(config, workingDir, targetVersions);
        }

        return new BumpResult(changes, targetVersions);
    }

    public BumpResult update(NMvnConfig config, NPath workingDir, Map<NId, String> explicitUpdates,
                             boolean apply) throws IOException {
        ScanResult scanResult = scan(config, workingDir);
        Map<NId, PomArtifact> artifacts = scanResult.getArtifacts();
        MavenDependencyGraph graph = scanResult.getGraph();

        Map<NId, String> targetVersions = new LinkedHashMap<>();
        if (explicitUpdates != null) {
            targetVersions.putAll(explicitUpdates);
        }

        if (targetVersions.isEmpty()) {
            return new BumpResult(Collections.emptyList(), Collections.emptyMap());
        }

        // Release Immutability Cascade:
        // Any dependent whose POM is modified:
        // - if already snapshot -> version is untouched (only dependency reference is updated)
        // - if release (non-snapshot) -> must be bumped to snapshot
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

        List<PomChange> changes = applyModifications(artifacts, targetVersions);

        if (apply) {
            applyChangesToDisk(changes);
            recordHistory(config, workingDir, targetVersions);
        }

        return new BumpResult(changes, targetVersions);
    }

    public ReleaseResult release(NMvnConfig config, NPath workingDir, Map<NId, String> explicitReleases,
                                 boolean strict, boolean apply) throws IOException {
        ScanResult scanResult = scan(config, workingDir);
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

    private List<PomChange> applyModifications(Map<NId, PomArtifact> artifacts, Map<NId, String> targetVersions) throws IOException {
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
                NPath definingPom = artifact.getPath(); // or parent if defined in parent
                String defContent = contentByPath.get(definingPom);
                if (defContent != null) {
                    contentByPath.put(definingPom, PomModifier.updateProperty(defContent, propName, newVer));
                }
            } else if (artifact.getRawVersion() != null) {
                contentByPath.put(pomPath, PomModifier.updateProjectVersion(content, newVer));
            }
        }

        // 2. Cascade references (parent, dependencies, BOMs, plugins)
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

    private void applyChangesToDisk(List<PomChange> changes) throws IOException {
        for (PomChange c : changes) {
            if (c.hasChanges()) {
                c.getPomFile().writeString(c.getNewContent());
            }
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
