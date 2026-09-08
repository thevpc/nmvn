package net.thevpc.nmvn.lib.model;

import net.thevpc.nuts.artifact.NDependency;
import net.thevpc.nuts.artifact.NDependencyBuilder;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.io.NPath;

import java.util.Objects;

public class PomDependency {
    private final NId id;
    private final String rawVersion;
    private String resolvedVersion;
    private final String scope;
    private final String type;
    private final boolean bomImport;
    private final boolean inDependencyManagement;
    private final DependencyEdgeType edgeType;
    private String versionPropertyName;
    private NPath propertyDefiningPom;

    public PomDependency(NId id, String scope, String type,
                         boolean inDependencyManagement, DependencyEdgeType edgeType) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        String v = id.version().isBlank() ? null : id.version().value();
        this.rawVersion = v;
        this.resolvedVersion = v;
        this.scope = scope != null ? scope.trim() : "compile";
        this.type = type != null ? type.trim() : "jar";
        this.inDependencyManagement = inDependencyManagement;
        this.edgeType = edgeType;
        this.bomImport = "pom".equalsIgnoreCase(this.type) && "import".equalsIgnoreCase(this.scope);

        if (v != null && v.startsWith("${") && v.endsWith("}")) {
            this.versionPropertyName = v.substring(2, v.length() - 1).trim();
        }
    }

    public PomDependency(String groupId, String artifactId, String rawVersion, String scope, String type,
                         boolean inDependencyManagement, DependencyEdgeType edgeType) {
        this(rawVersion == null || rawVersion.trim().isEmpty() ? NId.of(groupId, artifactId) : NId.of(groupId, artifactId, rawVersion.trim()),
                scope, type, inDependencyManagement, edgeType);
    }

    public NDependency toDependency() {
        net.thevpc.nuts.Nuts.require();
        return NDependencyBuilder.of()
                .id(id)
                .scope(scope)
                .type(type)
                .build();
    }

    public NId getId() {
        return id;
    }

    public NId getCoord() {
        return id;
    }

    public String getGroupId() {
        return id.groupId();
    }

    public String getArtifactId() {
        return id.artifactId();
    }

    public NId toGa() {
        return id.shortId();
    }

    public String getRawVersion() {
        return rawVersion;
    }

    public String getResolvedVersion() {
        return resolvedVersion;
    }

    public void setResolvedVersion(String resolvedVersion) {
        this.resolvedVersion = resolvedVersion;
    }

    public String getScope() {
        return scope;
    }

    public String getType() {
        return type;
    }

    public boolean isBomImport() {
        return bomImport;
    }

    public boolean isInDependencyManagement() {
        return inDependencyManagement;
    }

    public DependencyEdgeType getEdgeType() {
        return edgeType;
    }

    public boolean isPropertyIndirected() {
        return versionPropertyName != null;
    }

    public String getVersionPropertyName() {
        return versionPropertyName;
    }

    public NPath getPropertyDefiningPom() {
        return propertyDefiningPom;
    }

    public void setPropertyDefiningPom(NPath propertyDefiningPom) {
        this.propertyDefiningPom = propertyDefiningPom;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PomDependency that = (PomDependency) o;
        return bomImport == that.bomImport &&
                inDependencyManagement == that.inDependencyManagement &&
                id.equals(that.id) &&
                Objects.equals(scope, that.scope) &&
                Objects.equals(type, that.type) &&
                edgeType == that.edgeType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, scope, type, bomImport, inDependencyManagement, edgeType);
    }

    @Override
    public String toString() {
        return id.shortName() + ":" + (resolvedVersion != null ? resolvedVersion : rawVersion)
                + (bomImport ? " (BOM)" : "");
    }
}
