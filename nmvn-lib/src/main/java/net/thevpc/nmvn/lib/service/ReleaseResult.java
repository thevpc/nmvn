package net.thevpc.nmvn.lib.service;

import net.thevpc.nmvn.lib.model.PomChange;
import net.thevpc.nuts.artifact.NId;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ReleaseResult {
    private final List<PomChange> changes;
    private final Map<NId, String> releasedArtifacts;
    private final List<String> unmanagedSnapshots;

    public ReleaseResult(List<PomChange> changes, Map<NId, String> releasedArtifacts, List<String> unmanagedSnapshots) {
        this.changes = changes;
        this.releasedArtifacts = releasedArtifacts;
        this.unmanagedSnapshots = unmanagedSnapshots;
    }

    public List<PomChange> getChanges() {
        return Collections.unmodifiableList(changes);
    }

    public Map<NId, String> getReleasedArtifacts() {
        return Collections.unmodifiableMap(releasedArtifacts);
    }

    public List<String> getUnmanagedSnapshots() {
        return Collections.unmodifiableList(unmanagedSnapshots);
    }

    public boolean hasChanges() {
        for (PomChange c : changes) {
            if (c.hasChanges()) {
                return true;
            }
        }
        return false;
    }
}
