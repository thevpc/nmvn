package net.thevpc.nmvn.lib.diagnostic;

public enum DiagnosticRule {
    /**
     * An artifact (dependency or plugin) is referenced with multiple different versions
     * across different projects in the workspace.
     */
    MULTI_VERSION_DEPENDENCY,

    /**
     * An artifact is referenced as both SNAPSHOT and release across different projects.
     */
    MIXED_SNAPSHOT_AND_RELEASE,

    /**
     * A project references an internal workspace artifact with a version differing from
     * the workspace artifact's declared version.
     */
    INTERNAL_VERSION_MISMATCH,

    /**
     * A child project specifies a parent version that does not match the parent POM's actual declared version.
     */
    PARENT_VERSION_MISMATCH,

    /**
     * Workspace modules (e.g. within the same groupId) have mixed SNAPSHOT and release versions.
     */
    MIXED_WORKSPACE_SNAPSHOT_RELEASE,

    /**
     * A release module depends on a SNAPSHOT dependency.
     */
    SNAPSHOT_DEPENDENCY_IN_RELEASE,

    /**
     * A version string is an unresolvable property expression ${...}.
     */
    UNRESOLVED_PROPERTY_VERSION,

    /**
     * A dependency has no version and is not managed by dependencyManagement or BOM.
     */
    MISSING_VERSION,

    /**
     * Cyclic dependencies detected between workspace modules.
     */
    CIRCULAR_DEPENDENCY
}
