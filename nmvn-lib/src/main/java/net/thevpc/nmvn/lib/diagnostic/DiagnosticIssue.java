package net.thevpc.nmvn.lib.diagnostic;

import net.thevpc.nmvn.lib.model.MavenCoord;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.io.NPath;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class DiagnosticIssue {
    private final DiagnosticRule rule;
    private final DiagnosticSeverity severity;
    private final NId targetId;
    private final NPath sourcePom;
    private final String message;
    private final Map<String, String> details;

    public DiagnosticIssue(DiagnosticRule rule, DiagnosticSeverity severity, NId targetId,
                           NPath sourcePom, String message) {
        this(rule, severity, targetId, sourcePom, message, Collections.emptyMap());
    }

    public DiagnosticIssue(DiagnosticRule rule, DiagnosticSeverity severity, NId targetId,
                           NPath sourcePom, String message, Map<String, String> details) {
        this.rule = rule;
        this.severity = severity;
        this.targetId = targetId != null ? targetId.shortId() : null;
        this.sourcePom = sourcePom;
        this.message = message;
        this.details = details != null ? new LinkedHashMap<>(details) : Collections.emptyMap();
    }

    public DiagnosticIssue(DiagnosticRule rule, DiagnosticSeverity severity, MavenCoord targetCoord,
                           NPath sourcePom, String message) {
        this(rule, severity, targetCoord != null ? targetCoord.toId() : null, sourcePom, message);
    }

    public DiagnosticRule getRule() {
        return rule;
    }

    public DiagnosticSeverity getSeverity() {
        return severity;
    }

    public NId getTargetId() {
        return targetId;
    }

    public NId getTargetCoord() {
        return targetId;
    }

    public NPath getSourcePom() {
        return sourcePom;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, String> getDetails() {
        return Collections.unmodifiableMap(details);
    }

    @Override
    public String toString() {
        return "[" + severity + "] " + rule + ": " + message + (sourcePom != null ? " (" + sourcePom + ")" : "");
    }
}
