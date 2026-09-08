package net.thevpc.nmvn.lib.graph;

import net.thevpc.nmvn.lib.model.DependencyEdgeType;
import net.thevpc.nmvn.lib.model.MavenCoord;
import net.thevpc.nmvn.lib.model.PomDependency;
import net.thevpc.nuts.artifact.NId;

import java.util.Objects;

public class DependencyEdge {
    private final NId source;
    private final NId target;
    private final DependencyEdgeType edgeType;
    private final PomDependency dependency;

    public DependencyEdge(NId source, NId target, DependencyEdgeType edgeType, PomDependency dependency) {
        this.source = Objects.requireNonNull(source, "source cannot be null").shortId();
        this.target = Objects.requireNonNull(target, "target cannot be null").shortId();
        this.edgeType = edgeType;
        this.dependency = dependency;
    }

    public DependencyEdge(MavenCoord source, MavenCoord target, DependencyEdgeType edgeType, PomDependency dependency) {
        this(source != null ? source.toId() : null, target != null ? target.toId() : null, edgeType, dependency);
    }

    public NId getSource() {
        return source;
    }

    public NId getTarget() {
        return target;
    }

    public DependencyEdgeType getEdgeType() {
        return edgeType;
    }

    public PomDependency getDependency() {
        return dependency;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DependencyEdge that = (DependencyEdge) o;
        return source.equals(that.source) &&
                target.equals(that.target) &&
                edgeType == that.edgeType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, target, edgeType);
    }

    @Override
    public String toString() {
        return source.shortName() + " -[" + edgeType + "]-> " + target.shortName();
    }
}
