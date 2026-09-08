package net.thevpc.nmvn.lib.parser;

import net.thevpc.nmvn.lib.model.MavenCoord;
import net.thevpc.nmvn.lib.model.PomArtifact;
import net.thevpc.nmvn.lib.model.PomDependency;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.io.NPath;

import java.util.*;

public class PropertyResolver {

    public static void resolveAll(Map<NId, PomArtifact> artifactsByGa) {
        // First, resolve full property sets for each artifact (handling inheritance)
        for (PomArtifact artifact : artifactsByGa.values()) {
            resolveArtifactProperties(artifact, artifactsByGa);
        }

        // Next, resolve versions for all references
        for (PomArtifact artifact : artifactsByGa.values()) {
            // Resolve artifact's own version if property indirected
            if (artifact.isVersionPropertyIndirected()) {
                String propName = artifact.getVersionPropertyName();
                String val = artifact.getResolvedProperties().get(propName);
                if (val != null) {
                    artifact.setResolvedVersion(val);
                }
            } else if (artifact.getRawVersion() == null && artifact.getParentId() != null) {
                // Inherited version from parent
                PomArtifact parent = artifactsByGa.get(artifact.getParentId().shortId());
                if (parent != null) {
                    artifact.setResolvedVersion(parent.getResolvedVersion());
                } else {
                    artifact.setResolvedVersion(artifact.getParentId().version().isBlank() ? null : artifact.getParentId().version().value());
                }
            }

            // Resolve direct dependencies
            for (PomDependency dep : artifact.getDependencies()) {
                resolveDependencyVersion(dep, artifact, artifactsByGa);
            }
            // Resolve dependencyManagement
            for (PomDependency dep : artifact.getDependencyManagement()) {
                resolveDependencyVersion(dep, artifact, artifactsByGa);
            }
            // Resolve plugin dependencies
            for (PomDependency dep : artifact.getPluginDependencies()) {
                resolveDependencyVersion(dep, artifact, artifactsByGa);
            }
        }
    }

    private static void resolveArtifactProperties(PomArtifact artifact, Map<NId, PomArtifact> artifactsByGa) {
        if (!artifact.getResolvedProperties().isEmpty()) {
            return;
        }

        // Collect hierarchy: root parent -> ... -> parent -> artifact
        List<PomArtifact> hierarchy = new ArrayList<>();
        Set<NId> visited = new HashSet<>();
        PomArtifact curr = artifact;
        while (curr != null && visited.add(curr.toGa())) {
            hierarchy.add(0, curr);
            if (curr.getParentId() != null) {
                curr = artifactsByGa.get(curr.getParentId().shortId());
            } else {
                curr = null;
            }
        }

        // Inherit and override properties in hierarchy order
        Map<String, String> merged = new LinkedHashMap<>();
        for (PomArtifact a : hierarchy) {
            merged.putAll(a.getDeclaredProperties());
        }

        // Interpolate property values that reference other properties
        Map<String, String> interpolated = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            interpolated.put(entry.getKey(), interpolate(entry.getValue(), merged, new HashSet<>()));
        }

        artifact.getResolvedProperties().putAll(interpolated);
    }

    private static void resolveDependencyVersion(PomDependency dep, PomArtifact artifact, Map<NId, PomArtifact> artifactsByGa) {
        if (dep.isPropertyIndirected()) {
            String propName = dep.getVersionPropertyName();
            String resolvedVal = artifact.getResolvedProperties().get(propName);
            if (resolvedVal != null) {
                dep.setResolvedVersion(resolvedVal);
            }

            // Find defining POM (starting at artifact and walking up parents)
            NPath definingPom = findDefiningPomForProperty(propName, artifact, artifactsByGa);
            dep.setPropertyDefiningPom(definingPom != null ? definingPom : artifact.getPath());
        } else {
            dep.setResolvedVersion(dep.getRawVersion());
            dep.setPropertyDefiningPom(artifact.getPath());
        }
    }

    public static NPath findDefiningPomForProperty(String propertyName, PomArtifact artifact, Map<NId, PomArtifact> artifactsByGa) {
        Set<NId> visited = new HashSet<>();
        PomArtifact curr = artifact;
        while (curr != null && visited.add(curr.toGa())) {
            if (curr.getDeclaredProperties().containsKey(propertyName)) {
                return curr.getPath();
            }
            if (curr.getParentId() != null) {
                curr = artifactsByGa.get(curr.getParentId().shortId());
            } else {
                curr = null;
            }
        }
        return null;
    }

    private static String interpolate(String value, Map<String, String> props, Set<String> visiting) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (idx < value.length()) {
            int start = value.indexOf("${", idx);
            if (start == -1) {
                sb.append(value.substring(idx));
                break;
            }
            sb.append(value, idx, start);
            int end = value.indexOf('}', start + 2);
            if (end == -1) {
                sb.append(value.substring(start));
                break;
            }
            String propKey = value.substring(start + 2, end).trim();
            if (visiting.add(propKey)) {
                String targetVal = props.get(propKey);
                if (targetVal != null) {
                    sb.append(interpolate(targetVal, props, visiting));
                } else {
                    sb.append("${").append(propKey).append("}");
                }
                visiting.remove(propKey);
            } else {
                // Cycle in property interpolation
                sb.append("${").append(propKey).append("}");
            }
            idx = end + 1;
        }
        return sb.toString();
    }
}
