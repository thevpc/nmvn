package net.thevpc.nmvn.lib.service;

import net.thevpc.nmvn.lib.graph.MavenDependencyGraph;
import net.thevpc.nmvn.lib.model.PomArtifact;
import net.thevpc.nuts.artifact.NId;

import java.util.Collections;
import java.util.Map;

public class ScanResult {
    private final Map<NId, PomArtifact> artifacts;
    private final MavenDependencyGraph graph;

    public ScanResult(Map<NId, PomArtifact> artifacts, MavenDependencyGraph graph) {
        this.artifacts = artifacts;
        this.graph = graph;
    }

    public Map<NId, PomArtifact> getArtifacts() {
        return Collections.unmodifiableMap(artifacts);
    }

    public MavenDependencyGraph getGraph() {
        return graph;
    }
}
