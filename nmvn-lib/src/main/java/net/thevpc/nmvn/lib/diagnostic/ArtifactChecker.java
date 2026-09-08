package net.thevpc.nmvn.lib.diagnostic;

import net.thevpc.nmvn.lib.config.NMvnConfig;
import net.thevpc.nmvn.lib.graph.DependencyEdge;
import net.thevpc.nmvn.lib.graph.MavenDependencyGraph;
import net.thevpc.nmvn.lib.model.DependencyEdgeType;
import net.thevpc.nmvn.lib.model.MavenCoord;
import net.thevpc.nmvn.lib.model.PomArtifact;
import net.thevpc.nmvn.lib.model.PomDependency;
import net.thevpc.nmvn.lib.service.ScanResult;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.io.NPath;

import java.util.*;

public class ArtifactChecker {

    public DiagnosticReport check(ScanResult scanResult, NMvnConfig config) {
        List<DiagnosticIssue> issues = new ArrayList<>();
        Map<NId, PomArtifact> artifacts = scanResult.getArtifacts();
        MavenDependencyGraph graph = scanResult.getGraph();

        // 1. Check for cycles
        checkCycles(graph, artifacts, issues);

        // 2. Check for unresolved properties and missing versions
        checkPropertiesAndMissingVersions(artifacts, issues);

        // 3. Check for internal version mismatch and parent version mismatch
        checkInternalVersionMismatches(artifacts, graph, issues);

        // 4. Check for snapshot dependencies in release artifacts
        checkSnapshotInRelease(artifacts, issues);

        // 5. Check for multi-version usage and mixed snapshot/release across projects
        checkMultiVersionUsage(artifacts, graph, issues);

        // 6. Check for mixed snapshot/release modules within workspace groups
        checkWorkspaceGroupConsistency(artifacts, issues);

        return new DiagnosticReport(issues);
    }

    private void checkCycles(MavenDependencyGraph graph, Map<NId, PomArtifact> artifacts, List<DiagnosticIssue> issues) {
        List<List<String>> cycles = graph.findCycles();
        for (List<String> cycle : cycles) {
            String cycleStr = String.join(" -> ", cycle);
            NId firstGa = MavenCoord.parse(cycle.get(0));
            PomArtifact src = artifacts.get(firstGa);
            issues.add(new DiagnosticIssue(
                    DiagnosticRule.CIRCULAR_DEPENDENCY,
                    DiagnosticSeverity.ERROR,
                    firstGa,
                    src != null ? src.getPath() : null,
                    "Circular dependency detected: " + cycleStr
            ));
        }
    }

    private void checkPropertiesAndMissingVersions(Map<NId, PomArtifact> artifacts, List<DiagnosticIssue> issues) {
        for (PomArtifact artifact : artifacts.values()) {
            // Artifact's own version property
            if (artifact.getRawVersion() != null && artifact.getRawVersion().startsWith("${") && artifact.getRawVersion().endsWith("}")) {
                if (artifact.getRawVersion().equals(artifact.getResolvedVersion())) {
                    issues.add(new DiagnosticIssue(
                            DiagnosticRule.UNRESOLVED_PROPERTY_VERSION,
                            DiagnosticSeverity.ERROR,
                            artifact.toGa(),
                            artifact.getPath(),
                            "Artifact version property " + artifact.getRawVersion() + " could not be resolved"
                    ));
                }
            }

            // Reference versions
            for (PomDependency ref : artifact.getAllReferences()) {
                String resVer = ref.getResolvedVersion();
                if (resVer != null && resVer.startsWith("${") && resVer.endsWith("}")) {
                    issues.add(new DiagnosticIssue(
                            DiagnosticRule.UNRESOLVED_PROPERTY_VERSION,
                            DiagnosticSeverity.ERROR,
                            ref.toGa(),
                            artifact.getPath(),
                            "Version property " + resVer + " for " + ref.toGa().shortName() + " could not be resolved"
                    ));
                }
            }

            // Direct dependencies with missing version
            for (PomDependency dep : artifact.getDependencies()) {
                String ver = dep.getResolvedVersion();
                if (ver == null || ver.trim().isEmpty()) {
                    issues.add(new DiagnosticIssue(
                            DiagnosticRule.MISSING_VERSION,
                            DiagnosticSeverity.WARNING,
                            dep.toGa(),
                            artifact.getPath(),
                            "Direct dependency " + dep.toGa().shortName() + " has no declared version"
                    ));
                }
            }
        }
    }

    private void checkInternalVersionMismatches(Map<NId, PomArtifact> artifacts,
                                                MavenDependencyGraph graph,
                                                List<DiagnosticIssue> issues) {
        for (PomArtifact workspaceArtifact : artifacts.values()) {
            String declaredVer = workspaceArtifact.getResolvedVersion();
            if (declaredVer == null) {
                continue;
            }

            for (DependencyEdge edge : graph.getIncomingEdges(workspaceArtifact.toGa())) {
                PomArtifact consumer = artifacts.get(edge.getSource());
                PomDependency dep = edge.getDependency();
                String refVer = dep.getResolvedVersion();

                if (refVer != null && !refVer.equals(declaredVer)) {
                    NPath consumerPom = consumer != null ? consumer.getPath() : null;
                    String consumerDesc = consumer != null ? consumer.getId().shortName() : edge.getSource().shortName();

                    if (edge.getEdgeType() == DependencyEdgeType.PARENT) {
                        issues.add(new DiagnosticIssue(
                                DiagnosticRule.PARENT_VERSION_MISMATCH,
                                DiagnosticSeverity.ERROR,
                                workspaceArtifact.toGa(),
                                consumerPom,
                                "Child project " + consumerDesc + " specifies parent version " + refVer
                                        + " but parent artifact is declared as " + declaredVer
                        ));
                    } else {
                        issues.add(new DiagnosticIssue(
                                DiagnosticRule.INTERNAL_VERSION_MISMATCH,
                                DiagnosticSeverity.ERROR,
                                workspaceArtifact.toGa(),
                                consumerPom,
                                "Project " + consumerDesc + " references internal artifact " + workspaceArtifact.toGa().shortName()
                                        + ":" + refVer + " but workspace artifact is declared as " + declaredVer
                        ));
                    }
                }
            }
        }
    }

    private void checkSnapshotInRelease(Map<NId, PomArtifact> artifacts, List<DiagnosticIssue> issues) {
        for (PomArtifact artifact : artifacts.values()) {
            String myVer = artifact.getResolvedVersion();
            if (myVer != null && !myVer.toUpperCase().endsWith("-SNAPSHOT")) {
                // This artifact is a release!
                for (PomDependency ref : artifact.getAllReferences()) {
                    String refVer = ref.getResolvedVersion();
                    if (refVer != null && refVer.toUpperCase().endsWith("-SNAPSHOT")) {
                        issues.add(new DiagnosticIssue(
                                DiagnosticRule.SNAPSHOT_DEPENDENCY_IN_RELEASE,
                                DiagnosticSeverity.ERROR,
                                ref.toGa(),
                                artifact.getPath(),
                                "Release artifact " + artifact.getId().shortName() + ":" + myVer
                                        + " depends on SNAPSHOT " + ref.toGa().shortName() + ":" + refVer
                        ));
                    }
                }
            }
        }
    }

    private void checkMultiVersionUsage(Map<NId, PomArtifact> artifacts,
                                        MavenDependencyGraph graph,
                                        List<DiagnosticIssue> issues) {
        // Collect all referenced GAs across incoming edges
        Set<NId> allReferencedGas = new LinkedHashSet<>();
        for (PomArtifact a : artifacts.values()) {
            allReferencedGas.add(a.toGa());
            for (PomDependency dep : a.getAllReferences()) {
                allReferencedGas.add(dep.toGa());
            }
        }

        for (NId ga : allReferencedGas) {
            Map<String, List<String>> versionToProjects = new LinkedHashMap<>();

            // If it's a workspace artifact, include its own declared version
            PomArtifact workspaceArtifact = artifacts.get(ga);
            if (workspaceArtifact != null && workspaceArtifact.getResolvedVersion() != null) {
                versionToProjects.computeIfAbsent(workspaceArtifact.getResolvedVersion(), k -> new ArrayList<>())
                        .add(workspaceArtifact.getId().shortName() + " [declared]");
            }

            // Check all incoming edges
            for (DependencyEdge edge : graph.getIncomingEdges(ga)) {
                String refVer = edge.getDependency().getResolvedVersion();
                if (refVer != null && !refVer.trim().isEmpty() && !refVer.startsWith("${")) {
                    PomArtifact consumer = artifacts.get(edge.getSource());
                    String desc = (consumer != null ? consumer.getId().shortName() : edge.getSource().shortName())
                            + " (" + edge.getEdgeType() + ")";
                    List<String> projects = versionToProjects.computeIfAbsent(refVer, k -> new ArrayList<>());
                    if (!projects.contains(desc)) {
                        projects.add(desc);
                    }
                }
            }

            if (versionToProjects.keySet().size() > 1) {
                // Format details
                Map<String, String> details = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> entry : versionToProjects.entrySet()) {
                    details.put(entry.getKey(), String.join(", ", entry.getValue()));
                }

                // Check for mixed snapshot and release
                boolean hasSnapshot = false;
                boolean hasRelease = false;
                for (String v : versionToProjects.keySet()) {
                    if (v.toUpperCase().endsWith("-SNAPSHOT")) {
                        hasSnapshot = true;
                    } else {
                        hasRelease = true;
                    }
                }

                String versionsSummary = String.join(" vs ", versionToProjects.keySet());

                if (hasSnapshot && hasRelease) {
                    issues.add(new DiagnosticIssue(
                            DiagnosticRule.MIXED_SNAPSHOT_AND_RELEASE,
                            DiagnosticSeverity.ERROR,
                            ga,
                            null,
                            "Artifact " + ga.shortName() + " has mixed SNAPSHOT and release versions: " + versionsSummary,
                            details
                    ));
                }

                issues.add(new DiagnosticIssue(
                        DiagnosticRule.MULTI_VERSION_DEPENDENCY,
                        DiagnosticSeverity.WARNING,
                        ga,
                        null,
                        "Multiple versions of " + ga.shortName() + " referenced across workspace: " + versionsSummary,
                        details
                ));
            }
        }
    }

    private void checkWorkspaceGroupConsistency(Map<NId, PomArtifact> artifacts, List<DiagnosticIssue> issues) {
        Map<String, List<PomArtifact>> byGroup = new LinkedHashMap<>();
        for (PomArtifact a : artifacts.values()) {
            byGroup.computeIfAbsent(a.getGroupId(), k -> new ArrayList<>()).add(a);
        }

        for (Map.Entry<String, List<PomArtifact>> entry : byGroup.entrySet()) {
            String groupId = entry.getKey();
            List<PomArtifact> groupArtifacts = entry.getValue();
            if (groupArtifacts.size() <= 1) {
                continue;
            }

            boolean hasSnapshot = false;
            boolean hasRelease = false;
            Map<String, String> details = new LinkedHashMap<>();

            for (PomArtifact a : groupArtifacts) {
                String v = a.getResolvedVersion();
                if (v != null) {
                    details.put(a.getArtifactId(), v);
                    if (v.toUpperCase().endsWith("-SNAPSHOT")) {
                        hasSnapshot = true;
                    } else {
                        hasRelease = true;
                    }
                }
            }

            if (hasSnapshot && hasRelease) {
                issues.add(new DiagnosticIssue(
                        DiagnosticRule.MIXED_WORKSPACE_SNAPSHOT_RELEASE,
                        DiagnosticSeverity.WARNING,
                        NId.of(groupId, "*"),
                        null,
                        "Workspace group '" + groupId + "' has mixed SNAPSHOT and release modules",
                        details
                ));
            }
        }
    }
}
