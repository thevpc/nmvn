package net.thevpc.nmvn.lib.parser;

import net.thevpc.nmvn.lib.model.PomArtifact;
import net.thevpc.nmvn.lib.model.PomDependency;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.io.NPath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class PropertyResolver {

    private static final PomParser pomParser = new PomParser();

    public static void resolveAll(Map<NId, PomArtifact> artifactsByGa) {
        Map<NId, PomArtifact> externalCache = new LinkedHashMap<>();

        // First, resolve full property sets for each artifact (handling inheritance, including external parents)
        for (PomArtifact artifact : artifactsByGa.values()) {
            resolveArtifactProperties(artifact, artifactsByGa, externalCache);
        }

        // Next, resolve versions for all references
        for (PomArtifact artifact : artifactsByGa.values()) {
            // Resolve artifact's own version if property indirected or inherited from parent
            resolveArtifactVersion(artifact, artifactsByGa, externalCache);

            // Compute effective dependencyManagement (including parent hierarchy and imported BOMs)
            Map<NId, PomDependency> effectiveMgmt = getEffectiveDependencyManagement(artifact, artifactsByGa, externalCache, new HashSet<>());

            // Resolve direct dependencies
            for (PomDependency dep : artifact.getDependencies()) {
                resolveDependencyVersion(dep, artifact, artifactsByGa, externalCache, effectiveMgmt);
            }
            // Resolve dependencyManagement
            for (PomDependency dep : artifact.getDependencyManagement()) {
                resolveDependencyVersion(dep, artifact, artifactsByGa, externalCache, effectiveMgmt);
            }
            // Resolve plugin dependencies
            for (PomDependency dep : artifact.getPluginDependencies()) {
                resolveDependencyVersion(dep, artifact, artifactsByGa, externalCache, effectiveMgmt);
            }
        }
    }

    public static PomArtifact getOrLoadArtifact(NId id, Map<NId, PomArtifact> artifactsByGa, Map<NId, PomArtifact> externalCache) {
        if (id == null) {
            return null;
        }
        PomArtifact existing = artifactsByGa.get(id.shortId());
        if (existing != null) {
            return existing;
        }
        if (externalCache.containsKey(id)) {
            return externalCache.get(id);
        }
        if (externalCache.containsKey(id.shortId())) {
            return externalCache.get(id.shortId());
        }

        if (id.version().isBlank() || id.version().value().contains("${")) {
            return null;
        }

        NPath pomPath = findLocalMavenPom(id);
        if (pomPath != null) {
            try {
                PomArtifact loaded = pomParser.parse(pomPath);
                externalCache.put(id, loaded);
                externalCache.put(loaded.toGa(), loaded);
                resolveArtifactProperties(loaded, artifactsByGa, externalCache);
                resolveArtifactVersion(loaded, artifactsByGa, externalCache);
                return loaded;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public static NPath findLocalMavenPom(NId id) {
        if (id == null || id.groupId() == null || id.artifactId() == null || id.version().isBlank()) {
            return null;
        }
        String ver = id.version().value();
        if (ver.contains("${")) {
            return null;
        }
        String groupPath = id.groupId().replace('.', '/');
        String artId = id.artifactId();
        String pomFileName = artId + "-" + ver + ".pom";

        // 1. Check custom repo if set
        String customRepo = System.getProperty("maven.repo.local");
        if (customRepo == null || customRepo.isEmpty()) {
            customRepo = System.getenv("M2_REPO");
        }
        if (customRepo != null && !customRepo.isEmpty()) {
            Path p = Paths.get(customRepo, groupPath, artId, ver, pomFileName);
            if (Files.isRegularFile(p)) {
                return NPath.of(p);
            }
        }

        // 2. Check standard ~/.m2/repository
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            Path p = Paths.get(userHome, ".m2", "repository", groupPath, artId, ver, pomFileName);
            if (Files.isRegularFile(p)) {
                return NPath.of(p);
            }
        }

        return null;
    }

    private static void resolveArtifactProperties(PomArtifact artifact, Map<NId, PomArtifact> artifactsByGa, Map<NId, PomArtifact> externalCache) {
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
                PomArtifact next = getOrLoadArtifact(curr.getParentId(), artifactsByGa, externalCache);
                if (next == null) {
                    artifact.setHasUnresolvedParentOrBom(true);
                }
                curr = next;
            } else {
                curr = null;
            }
        }

        // Inherit and override properties in hierarchy order
        Map<String, String> merged = new LinkedHashMap<>();
        for (PomArtifact a : hierarchy) {
            if (a.getRawVersion() != null) {
                merged.put("project.version", a.getRawVersion());
                merged.put("version", a.getRawVersion());
            }
            if (a.getGroupId() != null) {
                merged.put("project.groupId", a.getGroupId());
                merged.put("groupId", a.getGroupId());
            }
            if (a.getArtifactId() != null) {
                merged.put("project.artifactId", a.getArtifactId());
                merged.put("artifactId", a.getArtifactId());
            }
            merged.putAll(a.getDeclaredProperties());
        }

        // Current artifact implicit properties take priority
        if (artifact.getRawVersion() != null) {
            merged.put("project.version", artifact.getRawVersion());
            merged.put("version", artifact.getRawVersion());
        }
        if (artifact.getGroupId() != null) {
            merged.put("project.groupId", artifact.getGroupId());
            merged.put("groupId", artifact.getGroupId());
        }
        if (artifact.getArtifactId() != null) {
            merged.put("project.artifactId", artifact.getArtifactId());
            merged.put("artifactId", artifact.getArtifactId());
        }

        // Interpolate property values that reference other properties
        Map<String, String> interpolated = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            interpolated.put(entry.getKey(), interpolate(entry.getValue(), merged, new HashSet<>()));
        }

        artifact.getResolvedProperties().putAll(interpolated);
    }

    private static void resolveArtifactVersion(PomArtifact artifact, Map<NId, PomArtifact> artifactsByGa, Map<NId, PomArtifact> externalCache) {
        if (artifact.isVersionPropertyIndirected()) {
            String propName = artifact.getVersionPropertyName();
            String val = artifact.getResolvedProperties().get(propName);
            if (val != null) {
                artifact.setResolvedVersion(val);
            }
        } else if (artifact.getRawVersion() == null && artifact.getParentId() != null) {
            PomArtifact parent = getOrLoadArtifact(artifact.getParentId(), artifactsByGa, externalCache);
            if (parent != null) {
                artifact.setResolvedVersion(parent.getResolvedVersion());
            } else {
                artifact.setResolvedVersion(artifact.getParentId().version().isBlank() ? null : artifact.getParentId().version().value());
            }
        }
    }

    public static Map<NId, PomDependency> getEffectiveDependencyManagement(
            PomArtifact artifact,
            Map<NId, PomArtifact> artifactsByGa,
            Map<NId, PomArtifact> externalCache,
            Set<NId> visitingBoms) {

        Map<NId, PomDependency> result = new LinkedHashMap<>();

        // 1. Process current artifact's own dependencyManagement first
        for (PomDependency dep : artifact.getDependencyManagement()) {
            if (dep.isBomImport()) {
                String bomVer = dep.getRawVersion();
                if (bomVer != null) {
                    bomVer = interpolate(bomVer, artifact.getResolvedProperties(), new HashSet<>());
                }
                if (bomVer != null && !bomVer.isEmpty() && !bomVer.contains("${")) {
                    NId bomId = NId.of(dep.getGroupId(), dep.getArtifactId(), bomVer);
                    if (visitingBoms.add(bomId.shortId())) {
                        PomArtifact bomArtifact = getOrLoadArtifact(bomId, artifactsByGa, externalCache);
                        if (bomArtifact != null) {
                            Map<NId, PomDependency> bomMgmt = getEffectiveDependencyManagement(bomArtifact, artifactsByGa, externalCache, visitingBoms);
                            for (Map.Entry<NId, PomDependency> entry : bomMgmt.entrySet()) {
                                result.putIfAbsent(entry.getKey(), entry.getValue());
                            }
                        } else {
                            artifact.setHasUnresolvedParentOrBom(true);
                        }
                        visitingBoms.remove(bomId.shortId());
                    }
                } else {
                    artifact.setHasUnresolvedParentOrBom(true);
                }
            } else {
                String rawVer = dep.getRawVersion();
                String resVer = rawVer;
                if (rawVer != null) {
                    resVer = interpolate(rawVer, artifact.getResolvedProperties(), new HashSet<>());
                }
                PomDependency resolvedDep = new PomDependency(
                        dep.getGroupId(), dep.getArtifactId(), resVer,
                        dep.getScope(), dep.getType(), true, dep.getEdgeType()
                );
                resolvedDep.setResolvedVersion(resVer);
                resolvedDep.setPropertyDefiningPom(artifact.getPath());
                result.putIfAbsent(resolvedDep.toGa(), resolvedDep);
            }
        }

        // 2. Process parent dependencyManagement next
        if (artifact.getParentId() != null) {
            PomArtifact parent = getOrLoadArtifact(artifact.getParentId(), artifactsByGa, externalCache);
            if (parent != null && !parent.toGa().equals(artifact.toGa())) {
                Map<NId, PomDependency> parentMgmt = getEffectiveDependencyManagement(parent, artifactsByGa, externalCache, visitingBoms);
                for (Map.Entry<NId, PomDependency> entry : parentMgmt.entrySet()) {
                    result.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }

        return result;
    }

    private static void resolveDependencyVersion(
            PomDependency dep,
            PomArtifact artifact,
            Map<NId, PomArtifact> artifactsByGa,
            Map<NId, PomArtifact> externalCache,
            Map<NId, PomDependency> effectiveMgmt) {

        if (dep.isPropertyIndirected()) {
            String propName = dep.getVersionPropertyName();
            String resolvedVal = artifact.getResolvedProperties().get(propName);
            if (resolvedVal != null) {
                dep.setResolvedVersion(resolvedVal);
            }

            // Find defining POM (starting at artifact and walking up parents)
            NPath definingPom = findDefiningPomForProperty(propName, artifact, artifactsByGa, externalCache);
            dep.setPropertyDefiningPom(definingPom != null ? definingPom : artifact.getPath());
        } else if (dep.getRawVersion() != null && !dep.getRawVersion().trim().isEmpty()) {
            String raw = dep.getRawVersion().trim();
            if (raw.contains("${")) {
                String interpolated = interpolate(raw, artifact.getResolvedProperties(), new HashSet<>());
                dep.setResolvedVersion(interpolated);
            } else {
                dep.setResolvedVersion(raw);
            }
            dep.setPropertyDefiningPom(artifact.getPath());
        } else {
            // Version is not declared: look up in dependencyManagement (including parents and imported BOMs)
            PomDependency managed = effectiveMgmt.get(dep.toGa());
            if (managed != null && managed.getResolvedVersion() != null && !managed.getResolvedVersion().trim().isEmpty()) {
                dep.setResolvedVersion(managed.getResolvedVersion());
                dep.setPropertyDefiningPom(managed.getPropertyDefiningPom());
            } else {
                dep.setResolvedVersion(null);
                dep.setPropertyDefiningPom(artifact.getPath());
            }
        }
    }

    public static NPath findDefiningPomForProperty(String propertyName, PomArtifact artifact, Map<NId, PomArtifact> artifactsByGa) {
        return findDefiningPomForProperty(propertyName, artifact, artifactsByGa, Collections.emptyMap());
    }

    public static NPath findDefiningPomForProperty(String propertyName, PomArtifact artifact, Map<NId, PomArtifact> artifactsByGa, Map<NId, PomArtifact> externalCache) {
        Set<NId> visited = new HashSet<>();
        PomArtifact curr = artifact;
        while (curr != null && visited.add(curr.toGa())) {
            if (curr.getDeclaredProperties().containsKey(propertyName)) {
                return curr.getPath();
            }
            if (curr.getParentId() != null) {
                curr = getOrLoadArtifact(curr.getParentId(), artifactsByGa, externalCache);
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
