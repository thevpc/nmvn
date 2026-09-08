package net.thevpc.nmvn.lib.graph;

import net.thevpc.nmvn.lib.exception.CycleDetectedException;
import net.thevpc.nmvn.lib.model.DependencyEdgeType;
import net.thevpc.nmvn.lib.model.MavenCoord;
import net.thevpc.nmvn.lib.model.PomArtifact;
import net.thevpc.nmvn.lib.model.PomDependency;
import net.thevpc.nuts.artifact.NId;

import java.util.*;

public class MavenDependencyGraph {
    private final Map<NId, PomArtifact> artifacts = new LinkedHashMap<>();
    private final Map<NId, List<DependencyEdge>> outgoingEdges = new LinkedHashMap<>();
    private final Map<NId, List<DependencyEdge>> incomingEdges = new LinkedHashMap<>();

    public MavenDependencyGraph(Map<NId, PomArtifact> artifacts) {
        for (Map.Entry<NId, PomArtifact> entry : artifacts.entrySet()) {
            this.artifacts.put(entry.getKey().shortId(), entry.getValue());
        }
        for (NId ga : this.artifacts.keySet()) {
            outgoingEdges.put(ga, new ArrayList<>());
            incomingEdges.put(ga, new ArrayList<>());
        }
        buildEdges();
    }

    private void buildEdges() {
        for (PomArtifact artifact : artifacts.values()) {
            NId srcGa = artifact.toGa();

            // Parent edge
            if (artifact.getParentId() != null) {
                NId targetGa = artifact.getParentId().shortId();
                PomDependency parentDep = new PomDependency(targetGa.groupId(), targetGa.artifactId(),
                        artifact.getParentId().version().isBlank() ? null : artifact.getParentId().version().value(),
                        null, "pom", false, DependencyEdgeType.PARENT);
                addEdge(new DependencyEdge(srcGa, targetGa, DependencyEdgeType.PARENT, parentDep));
            }

            // Direct dependencies
            for (PomDependency dep : artifact.getDependencies()) {
                NId targetGa = dep.toGa();
                addEdge(new DependencyEdge(srcGa, targetGa, dep.getEdgeType(), dep));
            }

            // DependencyManagement (including BOM imports)
            for (PomDependency dep : artifact.getDependencyManagement()) {
                NId targetGa = dep.toGa();
                DependencyEdgeType edgeType = dep.isBomImport() ? DependencyEdgeType.BOM_IMPORT : DependencyEdgeType.DIRECT_DEPENDENCY;
                addEdge(new DependencyEdge(srcGa, targetGa, edgeType, dep));
            }

            // Plugins
            for (PomDependency dep : artifact.getPluginDependencies()) {
                NId targetGa = dep.toGa();
                addEdge(new DependencyEdge(srcGa, targetGa, DependencyEdgeType.PLUGIN, dep));
            }
        }
    }

    private void addEdge(DependencyEdge edge) {
        outgoingEdges.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge);
        incomingEdges.computeIfAbsent(edge.getTarget(), k -> new ArrayList<>()).add(edge);
    }

    public Map<NId, PomArtifact> getArtifacts() {
        return Collections.unmodifiableMap(artifacts);
    }

    public List<DependencyEdge> getOutgoingEdges(NId ga) {
        List<DependencyEdge> list = outgoingEdges.get(ga.shortId());
        return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
    }

    public List<DependencyEdge> getOutgoingEdges(MavenCoord ga) {
        return getOutgoingEdges(ga.toId());
    }

    public List<DependencyEdge> getIncomingEdges(NId ga) {
        List<DependencyEdge> list = incomingEdges.get(ga.shortId());
        return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
    }

    public List<DependencyEdge> getIncomingEdges(MavenCoord ga) {
        return getIncomingEdges(ga.toId());
    }

    /**
     * Returns all artifacts that reference the given targetGa (reverse dependencies).
     */
    public Set<NId> getDirectDependents(NId targetGa) {
        Set<NId> set = new LinkedHashSet<>();
        for (DependencyEdge edge : getIncomingEdges(targetGa)) {
            if (artifacts.containsKey(edge.getSource())) {
                set.add(edge.getSource());
            }
        }
        return set;
    }

    public Set<NId> getDirectDependents(MavenCoord targetGa) {
        return getDirectDependents(targetGa.toId());
    }

    /**
     * Detects cycles in the workspace dependency graph.
     * Throws CycleDetectedException with full cycle path if cycle is found.
     */
    public void detectCycles() {
        List<List<String>> cycles = findCycles();
        if (!cycles.isEmpty()) {
            throw new CycleDetectedException(cycles.get(0));
        }
    }

    /**
     * Finds all cycles in the workspace dependency graph.
     * Returns a list of cycle paths (empty if no cycles).
     */
    public List<List<String>> findCycles() {
        Map<NId, Integer> state = new HashMap<>(); // 0: unvisited, 1: visiting, 2: visited
        List<NId> stack = new ArrayList<>();
        List<List<String>> cycles = new ArrayList<>();

        for (NId node : artifacts.keySet()) {
            if (state.getOrDefault(node, 0) == 0) {
                dfsFindCycles(node, state, stack, cycles);
            }
        }
        return cycles;
    }

    private void dfsFindCycles(NId node, Map<NId, Integer> state, List<NId> stack, List<List<String>> cycles) {
        state.put(node, 1);
        stack.add(node);

        for (DependencyEdge edge : getOutgoingEdges(node)) {
            NId target = edge.getTarget();
            // Only care about cycles within the scanned workspace
            if (artifacts.containsKey(target)) {
                int targetState = state.getOrDefault(target, 0);
                if (targetState == 1) {
                    // Cycle detected! Extract cycle path
                    int startIndex = stack.indexOf(target);
                    List<String> cyclePath = new ArrayList<>();
                    for (int i = startIndex; i < stack.size(); i++) {
                        cyclePath.add(stack.get(i).shortName());
                    }
                    cyclePath.add(target.shortName());
                    cycles.add(cyclePath);
                } else if (targetState == 0) {
                    dfsFindCycles(target, state, stack, cycles);
                }
            }
        }

        stack.remove(stack.size() - 1);
        state.put(node, 2);
    }

    /**
     * Returns artifacts in topological order (leaves first, dependents last).
     */
    public List<NId> topologicalOrder() {
        detectCycles();
        List<NId> order = new ArrayList<>();
        Set<NId> visited = new HashSet<>();

        for (NId node : artifacts.keySet()) {
            if (!visited.contains(node)) {
                dfsTopo(node, visited, order);
            }
        }
        return order;
    }

    private void dfsTopo(NId node, Set<NId> visited, List<NId> order) {
        visited.add(node);
        for (DependencyEdge edge : getOutgoingEdges(node)) {
            if (artifacts.containsKey(edge.getTarget()) && !visited.contains(edge.getTarget())) {
                dfsTopo(edge.getTarget(), visited, order);
            }
        }
        order.add(node);
    }
}
