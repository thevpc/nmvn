package net.thevpc.nmvn.lib.model;

import net.thevpc.nuts.artifact.NId;

import java.util.Objects;

public class MavenCoord implements Comparable<MavenCoord> {
    private final NId id;

    public MavenCoord(String groupId, String artifactId) {
        this(of(groupId, artifactId));
    }

    public MavenCoord(String groupId, String artifactId, String version) {
        this(of(groupId, artifactId, version));
    }

    public MavenCoord(NId id) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
    }

    public static NId of(String groupId, String artifactId) {
        return NId.of(Objects.requireNonNull(groupId, "groupId cannot be null").trim(),
                Objects.requireNonNull(artifactId, "artifactId cannot be null").trim());
    }

    public static NId of(String groupId, String artifactId, String version) {
        String g = Objects.requireNonNull(groupId, "groupId cannot be null").trim();
        String a = Objects.requireNonNull(artifactId, "artifactId cannot be null").trim();
        if (version == null || version.trim().isEmpty()) {
            return NId.of(g, a);
        }
        return NId.of(g, a, version.trim());
    }

    public static NId parse(String coordStr) {
        if (coordStr == null || coordStr.trim().isEmpty()) {
            throw new IllegalArgumentException("Coordinate string cannot be null or empty");
        }
        String s = coordStr.trim();
        if (s.contains("#")) {
            return NId.of(s);
        }
        String[] parts = s.split(":");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid coordinate format, expected groupId:artifactId[:version], got: " + coordStr);
        }
        String g = parts[0].trim();
        String a = parts[1].trim();
        if (parts.length == 2) {
            return NId.of(g, a);
        }
        String v = parts[2].trim();
        return of(g, a, v);
    }

    public static String toGaString(NId id) {
        if (id == null) return "";
        return id.shortName();
    }

    public static String toGavString(NId id) {
        if (id == null) return "";
        if (id.version().isBlank()) {
            return id.shortName();
        }
        return id.groupId() + ":" + id.artifactId() + ":" + id.version().value();
    }

    public static NId toGa(NId id) {
        if (id == null) return null;
        return id.shortId();
    }

    public static NId withVersion(NId id, String newVersion) {
        if (id == null) return null;
        return of(id.groupId(), id.artifactId(), newVersion);
    }

    public NId toId() {
        return id;
    }

    public String getGroupId() {
        return id.groupId();
    }

    public String getArtifactId() {
        return id.artifactId();
    }

    public String getVersion() {
        return id.version().isBlank() ? null : id.version().value();
    }

    public MavenCoord withVersion(String newVersion) {
        return new MavenCoord(withVersion(id, newVersion));
    }

    public MavenCoord toGa() {
        return new MavenCoord(id.shortId());
    }

    public String toGaString() {
        return id.shortName();
    }

    public String toGavString() {
        return toGavString(id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MavenCoord that = (MavenCoord) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return toGavString();
    }

    @Override
    public int compareTo(MavenCoord o) {
        return id.compareTo(o.id);
    }
}
