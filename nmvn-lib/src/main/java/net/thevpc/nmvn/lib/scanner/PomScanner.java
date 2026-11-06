package net.thevpc.nmvn.lib.scanner;

import net.thevpc.nmvn.lib.config.NMvnConfig;
import net.thevpc.nmvn.lib.exception.AmbiguousArtifactException;
import net.thevpc.nmvn.lib.model.MavenCoord;
import net.thevpc.nmvn.lib.model.PomArtifact;
import net.thevpc.nmvn.lib.parser.PomParser;
import net.thevpc.nmvn.lib.parser.PropertyResolver;

import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NException;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class PomScanner {

    private static final Set<String> DEFAULT_IGNORED_DIRECTORIES = new HashSet<>(Arrays.asList(
            ".git", ".hg", ".svn", ".idea", ".gradle", "target", "build", "dist", "node_modules"
    ));
    private final PomParser pomParser = new PomParser();

    public Map<NId, PomArtifact> scan(NMvnConfig config, NPath workingDir)  {
        List<String> warnings = new ArrayList<>();
        Map<NId, PomArtifact> artifacts = scan(config, workingDir, warnings);
        if (!warnings.isEmpty()) {
            throw new IllegalStateException("Incomplete Maven scan: " + String.join("; ", warnings));
        }
        return artifacts;
    }

    public Map<NId, PomArtifact> scan(NMvnConfig config, NPath workingDir, List<String> warnings)  {
        List<String> roots = config.getRoots();
        if (roots == null || roots.isEmpty()) {
            roots = Collections.singletonList(".");
        }

        Map<NId, List<PomArtifact>> collectedByGa = new LinkedHashMap<>();

        for (String rootStr : roots) {
            NPath rootNPath = workingDir.resolve(rootStr).normalize().toAbsolute();
            if (!rootNPath.exists()) {
                warnings.add("Configured scan root does not exist: " + rootNPath);
                continue;
            }
            Path rootPath = rootNPath.toPath().get();

            // Combine global excludes + root-specific excludes
            List<String> combinedExcludes = new ArrayList<>(config.getExcludes());
            if (config.getRootExcludes() != null && config.getRootExcludes().containsKey(rootStr)) {
                combinedExcludes.addAll(config.getRootExcludes().get(rootStr));
            }

            List<PathMatcher> matchers = new ArrayList<>();
            FileSystem fs = rootPath.getFileSystem();
            for (String exc : combinedExcludes) {
                for (String variant : expandGlobVariants(exc)) {
                    try {
                        matchers.add(fs.getPathMatcher("glob:" + variant));
                    } catch (Exception ignored) {
                    }
                }
            }
            GitIgnoreMatcher gitIgnoreMatcher = config.isUseDefaultIgnores()
                    ? new GitIgnoreMatcher(rootPath, warnings) : null;

            try {
                Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (config.isUseDefaultIgnores() && !dir.equals(rootPath)
                                && DEFAULT_IGNORED_DIRECTORIES.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        Path rel = rootPath.relativize(dir);
                        if (isExcluded(rel, matchers)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (gitIgnoreMatcher != null && gitIgnoreMatcher.isIgnored(dir, true)
                                && !gitIgnoreMatcher.mayReincludeDescendant(dir)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if ("pom.xml".equals(file.getFileName().toString())) {
                            Path rel = rootPath.relativize(file);
                            if (!isExcluded(rel, matchers)
                                    && (gitIgnoreMatcher == null || !gitIgnoreMatcher.isIgnored(file, false))) {
                                try {
                                    PomArtifact artifact = pomParser.parse(NPath.of(file));
                                    NId ga = artifact.toGa();
                                    List<PomArtifact> list = collectedByGa.computeIfAbsent(ga, k -> new ArrayList<>());
                                    list.add(artifact);
                                } catch (Exception e) {
                                    warnings.add("Skipped POM " + file + ": " + e.getMessage());
                                }
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw NException.ofUncheckedException(e);
            }
        }

        // Check for ambiguous artifacts (same GA found in multiple places)
        Map<NId, PomArtifact> result = new LinkedHashMap<>();
        for (Map.Entry<NId, List<PomArtifact>> entry : collectedByGa.entrySet()) {
            List<PomArtifact> list = entry.getValue();
            if (list.size() > 1) {
                List<NPath> paths = new ArrayList<>();
                for (PomArtifact pa : list) {
                    paths.add(pa.getPath());
                }
                throw new AmbiguousArtifactException(entry.getKey().groupId(), entry.getKey().artifactId(), paths);
            }
            result.put(entry.getKey(), list.get(0));
        }

        // Resolve property-indirected versions and inheritance
        PropertyResolver.resolveAll(result);

        return result;
    }

    /**
     * Expands a glob so double-star-slash patterns also match at path boundaries.
     * Java globs require a real directory for a leading or trailing double star,
     * so a pattern like star-star-skipme-star-star never matches
     * skipme/pom.xml nor skipme itself. Generate the boundary variants as well.
     */
    static List<String> expandGlobVariants(String pattern) {
        List<String> variants = new ArrayList<>();
        variants.add(pattern);
        boolean leading = pattern.startsWith("**/");
        boolean trailing = pattern.endsWith("/**");
        if (leading) {
            variants.add(pattern.substring(3));
        }
        if (trailing) {
            variants.add(pattern.substring(0, pattern.length() - 3));
        }
        if (leading && trailing && pattern.length() > 5) {
            variants.add(pattern.substring(3, pattern.length() - 3));
        }
        return variants;
    }

    private boolean isExcluded(Path path, List<PathMatcher> matchers) {
        String pathStr = path.toString().replace('\\', '/');
        if (pathStr.isEmpty()) {
            return false;
        }
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(path) || matcher.matches(Paths.get(pathStr))) {
                return true;
            }
        }
        return false;
    }

    private static final class GitIgnoreMatcher {
        private final Path root;
        private final List<String> warnings;
        private final Map<Path, List<GitIgnoreRule>> rulesByDirectory = new HashMap<>();
        private final Set<Path> unreadableFiles = new HashSet<>();

        private GitIgnoreMatcher(Path root, List<String> warnings) {
            this.root = root;
            this.warnings = warnings;
        }

        private boolean isIgnored(Path path, boolean directory) {
            List<Path> prefixes = prefixes(path, directory);
            for (int i = 0; i < prefixes.size(); i++) {
                Path prefix = prefixes.get(i);
                boolean prefixDirectory = directory || i < prefixes.size() - 1;
                Path rulesEndDirectory = prefixDirectory ? prefix : prefix.getParent();
                boolean ignored = false;
                for (Path dir = root; rulesEndDirectory != null && rulesEndDirectory.startsWith(dir); ) {
                    for (GitIgnoreRule rule : rulesFor(dir)) {
                        if (rule.matches(prefix, prefixDirectory)) {
                            ignored = !rule.negated;
                        }
                    }
                    if (dir.equals(rulesEndDirectory)) {
                        break;
                    }
                    dir = dir.resolve(rulesEndDirectory.getName(dir.getNameCount()));
                }
                if (ignored) {
                    return true;
                }
            }
            return false;
        }

        private boolean mayReincludeDescendant(Path directory) {
            for (Path dir = root; directory.startsWith(dir); ) {
                for (GitIgnoreRule rule : rulesFor(dir)) {
                    if (rule.negated) {
                        return true;
                    }
                }
                if (dir.equals(directory)) {
                    break;
                }
                dir = dir.resolve(directory.getName(dir.getNameCount()));
            }
            return false;
        }

        private List<Path> prefixes(Path path, boolean directory) {
            List<Path> result = new ArrayList<>();
            Path current = root;
            result.add(current);
            Path relative = root.relativize(path);
            int count = relative.getNameCount();
            for (int i = 0; i < count; i++) {
                current = current.resolve(relative.getName(i));
                result.add(current);
            }
            if (directory && !path.equals(root) && !result.contains(path)) {
                result.add(path);
            }
            return result;
        }

        private List<GitIgnoreRule> rulesFor(Path directory) {
            if (rulesByDirectory.containsKey(directory)) {
                return rulesByDirectory.get(directory);
            }
            Path ignoreFile = directory.resolve(".gitignore");
            List<GitIgnoreRule> rules = new ArrayList<>();
            if (Files.isRegularFile(ignoreFile)) {
                try {
                    for (String line : Files.readAllLines(ignoreFile)) {
                        GitIgnoreRule rule = GitIgnoreRule.parse(directory, line, warnings);
                        if (rule != null) {
                            rules.add(rule);
                        }
                    }
                } catch (IOException e) {
                    if (unreadableFiles.add(ignoreFile)) {
                        warnings.add("Cannot read ignore file " + ignoreFile + ": " + e.getMessage());
                    }
                }
            }
            rulesByDirectory.put(directory, rules);
            return rules;
        }
    }

    private static final class GitIgnoreRule {
        private final Path base;
        private final String pattern;
        private final boolean negated;
        private final boolean directoryOnly;
        private final boolean matchesAnySegment;
        private final List<PathMatcher> matchers = new ArrayList<>();

        private GitIgnoreRule(Path base, String pattern, boolean negated, boolean directoryOnly,
                              boolean matchesAnySegment, List<String> warnings) {
            this.base = base;
            this.pattern = pattern;
            this.negated = negated;
            this.directoryOnly = directoryOnly;
            this.matchesAnySegment = matchesAnySegment;
            FileSystem fs = base.getFileSystem();
            String glob = pattern.startsWith("/") ? pattern.substring(1) : pattern;
            for (String variant : expandGlobVariants(glob)) {
                try {
                    matchers.add(fs.getPathMatcher("glob:" + variant));
                } catch (Exception e) {
                    warnings.add("Unsupported .gitignore pattern '" + pattern + "' in " + base + ": "
                            + e.getMessage());
                }
            }
        }

        private static GitIgnoreRule parse(Path base, String source, List<String> warnings) {
            String line = source.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                return null;
            }
            boolean negated = line.startsWith("!");
            if (negated) {
                line = line.substring(1);
            }
            if (line.isEmpty()) {
                return null;
            }
            boolean directoryOnly = line.endsWith("/");
            if (directoryOnly) {
                line = line.substring(0, line.length() - 1);
            }
            boolean matchesAnySegment = !line.contains("/");
            return new GitIgnoreRule(base, line, negated, directoryOnly, matchesAnySegment, warnings);
        }

        private boolean matches(Path path, boolean candidateDirectory) {
            if (!path.startsWith(base) || (directoryOnly && !candidateDirectory)) {
                return false;
            }
            Path relative = base.relativize(path);
            if (relative.getNameCount() == 0) {
                return false;
            }
            if (matchesAnySegment) {
                return matches(relative.getFileName());
            }
            return matches(relative);
        }

        private boolean matches(Path path) {
            for (PathMatcher matcher : matchers) {
                if (matcher.matches(path)) {
                    return true;
                }
            }
            return pattern.equals(path.toString().replace('\\', '/'));
        }
    }
}
