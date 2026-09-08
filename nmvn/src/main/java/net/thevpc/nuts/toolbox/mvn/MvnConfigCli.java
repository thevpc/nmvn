package net.thevpc.nuts.toolbox.mvn;

import net.thevpc.nuts.core.NSession;

/**
 * Backward-compatibility alias for {@link MvnWorksetCli}.
 */
public class MvnConfigCli extends MvnWorksetCli {
    public MvnConfigCli(NSession session) {
        super(session);
    }
}
