package net.thevpc.nmvn.lib.model;

import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.io.NPath;

import java.util.*;

public class PomArtifact {
    private final NId id;
    private String resolvedVersion;
    private final NId parentId;
    private final NPath path;
    private final Map<String, String> declaredProperties = new LinkedHashMap<>();
    private final Map<String, String> resolvedProperties = new LinkedHashMap<>();
    private final List<PomDependency> dependencies = new ArrayList<>();
    private final List<PomDependency> dependencyManagement = new ArrayList<>();
    private final List<PomDependency> pluginDependencies = new ArrayList<>();
    private final List<String> modules = new ArrayList<>();
    private String versionPropertyName;

    public PomArtifact(NId id, NId parentId, NPath path) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.parentId = parentId;
        this.path = Objects.requireNonNull(path, "path cannot be null");
        String v = id.version().isBlank() ? null : id.version().value();
        this.resolvedVersion = v;
        if (v != null && v.startsWith("${") && v.endsWith("}")) {
            this.versionPropertyName = v.substring(2, v.length() - 1).trim();
        }
    }

    public PomArtifact(MavenCoord coord, MavenCoord parentCoord, NPath path) {
        this(coord != null ? coord.toId() : null, parentCoord != null ? parentCoord.toId() : null, path);
    }

    public NId getId() {
        return id;
    }

    public NId getCoord() {
        return id;
    }

    public NId toGa() {
        return id.shortId();
    }

    public String getGroupId() {
        return id.groupId();
    }

    public String getArtifactId() {
        return id.artifactId();
    }

    public String getRawVersion() {
        return id.version().isBlank() ? null : id.version().value();
    }

    public String getResolvedVersion() {
        return resolvedVersion;
    }

    public void setResolvedVersion(String resolvedVersion) {
        this.resolvedVersion = resolvedVersion;
    }

    public NId getParentId() {
        return parentId;
    }

    public NId getParentCoord() {
        return parentId;
    }

    public NPath getPath() {
        return path;
    }

    public Map<String, String> getDeclaredProperties() {
        return declaredProperties;
    }

    public Map<String, String> getResolvedProperties() {
        return resolvedProperties;
    }

    public List<PomDependency> getDependencies() {
        return dependencies;
    }

    public List<PomDependency> getDependencyManagement() {
        return dependencyManagement;
    }

    public List<PomDependency> getPluginDependencies() {
        return pluginDependencies;
    }

    public List<String> getModules() {
        return modules;
    }

    public boolean isVersionPropertyIndirected() {
        return versionPropertyName != null;
    }

    public String getVersionPropertyName() {
        return versionPropertyName;
    }

    public List<PomDependency> getAllReferences() {
        List<PomDependency> all = new ArrayList<>();
        if (parentId != null) {
            all.add(new PomDependency(parentId.groupId(), parentId.artifactId(),
                    parentId.version().isBlank() ? null : parentId.version().value(), null, "pom", false, DependencyEdgeType.PARENT));
        }
        all.addAll(dependencies);
        all.addAll(dependencyManagement);
        all.addAll(pluginDependencies);
        return Collections.unmodifiableList(all);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PomArtifact that = (PomArtifact) o;
        return id.shortId().equals(that.id.shortId()) && path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id.shortId(), path);
    }

    @Override
    public String toString() {
        return id.shortName() + ":" + (resolvedVersion != null ? resolvedVersion : getRawVersion())
                + " [" + path + "]";
    }
}
