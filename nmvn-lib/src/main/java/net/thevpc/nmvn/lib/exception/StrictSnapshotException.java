package net.thevpc.nmvn.lib.exception;

import java.util.List;

public class StrictSnapshotException extends NMvnException {
    private final List<String> unmanagedSnapshots;

    public StrictSnapshotException(List<String> unmanagedSnapshots) {
        super("External/unmanaged SNAPSHOT dependencies found in strict mode: " + String.join(", ", unmanagedSnapshots));
        this.unmanagedSnapshots = unmanagedSnapshots;
    }

    public List<String> getUnmanagedSnapshots() {
        return unmanagedSnapshots;
    }
}
