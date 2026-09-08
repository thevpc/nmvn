package net.thevpc.nmvn.lib.service;

import net.thevpc.nmvn.lib.model.PomChange;
import net.thevpc.nuts.artifact.NId;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class BumpResult {
    private final List<PomChange> changes;
    private final Map<NId, String> bumpedArtifacts;

    public BumpResult(List<PomChange> changes, Map<NId, String> bumpedArtifacts) {
        this.changes = changes;
        this.bumpedArtifacts = bumpedArtifacts;
    }

    public List<PomChange> getChanges() {
        return Collections.unmodifiableList(changes);
    }

    public Map<NId, String> getBumpedArtifacts() {
        return Collections.unmodifiableMap(bumpedArtifacts);
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
