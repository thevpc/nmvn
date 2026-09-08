package net.thevpc.nmvn.lib.diagnostic;

import net.thevpc.nmvn.lib.model.MavenCoord;

import java.util.*;

public class DiagnosticReport {
    private final List<DiagnosticIssue> issues;

    public DiagnosticReport(List<DiagnosticIssue> issues) {
        this.issues = issues != null ? Collections.unmodifiableList(new ArrayList<>(issues)) : Collections.emptyList();
    }

    public List<DiagnosticIssue> getIssues() {
        return issues;
    }

    public boolean isEmpty() {
        return issues.isEmpty();
    }

    public int size() {
        return issues.size();
    }

    public boolean hasErrors() {
        for (DiagnosticIssue issue : issues) {
            if (issue.getSeverity() == DiagnosticSeverity.ERROR) {
                return true;
            }
        }
        return false;
    }

    public boolean hasWarnings() {
        for (DiagnosticIssue issue : issues) {
            if (issue.getSeverity() == DiagnosticSeverity.WARNING) {
                return true;
            }
        }
        return false;
    }

    public List<DiagnosticIssue> getErrors() {
        List<DiagnosticIssue> list = new ArrayList<>();
        for (DiagnosticIssue issue : issues) {
            if (issue.getSeverity() == DiagnosticSeverity.ERROR) {
                list.add(issue);
            }
        }
        return list;
    }

    public List<DiagnosticIssue> getWarnings() {
        List<DiagnosticIssue> list = new ArrayList<>();
        for (DiagnosticIssue issue : issues) {
            if (issue.getSeverity() == DiagnosticSeverity.WARNING) {
                list.add(issue);
            }
        }
        return list;
    }

    public List<DiagnosticIssue> getByRule(DiagnosticRule rule) {
        List<DiagnosticIssue> list = new ArrayList<>();
        for (DiagnosticIssue issue : issues) {
            if (issue.getRule() == rule) {
                list.add(issue);
            }
        }
        return list;
    }

    public Map<net.thevpc.nuts.artifact.NId, List<DiagnosticIssue>> groupByTargetId() {
        Map<net.thevpc.nuts.artifact.NId, List<DiagnosticIssue>> map = new LinkedHashMap<>();
        for (DiagnosticIssue issue : issues) {
            map.computeIfAbsent(issue.getTargetId(), k -> new ArrayList<>()).add(issue);
        }
        return map;
    }

    public Map<net.thevpc.nuts.artifact.NId, List<DiagnosticIssue>> groupByTargetCoord() {
        return groupByTargetId();
    }
}
