package net.thevpc.nmvn.lib.service;

import net.thevpc.nmvn.lib.graph.MavenDependencyGraph;
import net.thevpc.nmvn.lib.model.PomArtifact;
import net.thevpc.nuts.artifact.NId;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ScanResult {
    private final Map<NId, PomArtifact> artifacts;
    private final MavenDependencyGraph graph;
    private final List<String> warnings;

    public ScanResult(Map<NId, PomArtifact> artifacts, MavenDependencyGraph graph) {
        this(artifacts, graph, Collections.emptyList());
    }

    public ScanResult(Map<NId, PomArtifact> artifacts, MavenDependencyGraph graph, List<String> warnings) {
        this.artifacts = artifacts;
        this.graph = graph;
        this.warnings = new ArrayList<>(warnings);
    }

    public Map<NId, PomArtifact> getArtifacts() {
        return Collections.unmodifiableMap(artifacts);
    }

    public MavenDependencyGraph getGraph() {
        return graph;
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }
}
