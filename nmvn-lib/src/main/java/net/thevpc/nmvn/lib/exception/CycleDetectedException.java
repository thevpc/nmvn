package net.thevpc.nmvn.lib.exception;

import java.util.List;

public class CycleDetectedException extends NMvnException {
    private final List<String> cyclePath;

    public CycleDetectedException(List<String> cyclePath) {
        super("Circular dependency detected: " + String.join(" -> ", cyclePath));
        this.cyclePath = cyclePath;
    }

    public List<String> getCyclePath() {
        return cyclePath;
    }
}
