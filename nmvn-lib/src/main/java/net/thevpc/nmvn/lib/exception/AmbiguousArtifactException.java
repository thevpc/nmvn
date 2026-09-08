package net.thevpc.nmvn.lib.exception;

import net.thevpc.nuts.io.NPath;

import java.util.List;

public class AmbiguousArtifactException extends NMvnException {
    private final String groupId;
    private final String artifactId;
    private final List<NPath> definingPoms;

    public AmbiguousArtifactException(String groupId, String artifactId, List<NPath> definingPoms) {
        super(String.format("Ambiguous artifact %s:%s found in multiple locations: %s",
                groupId, artifactId, definingPoms));
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.definingPoms = definingPoms;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public List<NPath> getDefiningPoms() {
        return definingPoms;
    }
}
