package net.thevpc.nmvn.lib.model;

public class BumpPolicy {
    public enum IncrementType {
        MAJOR,
        MINOR,
        PATCH;

        public static IncrementType parse(String str) {
            if (str == null) return MINOR;
            switch (str.trim().toLowerCase()) {
                case "major": return MAJOR;
                case "patch": return PATCH;
                case "minor":
                default:
                    return MINOR;
            }
        }

        public static boolean isIncrementKeyword(String str) {
            if (str == null) return false;
            String s = str.trim().toLowerCase();
            if (s.startsWith("+")) s = s.substring(1).trim();
            return "major".equals(s) || "minor".equals(s) || "patch".equals(s);
        }
    }

    public enum CascadePolicy {
        CASCADE_REFERENCES_ONLY,
        CASCADE_VERSIONS;

        public static CascadePolicy parse(String str) {
            if (str == null) return CASCADE_REFERENCES_ONLY;
            String clean = str.trim().toLowerCase().replace("_", "-");
            if ("cascade-versions".equals(clean)) {
                return CASCADE_VERSIONS;
            }
            return CASCADE_REFERENCES_ONLY;
        }
    }

    private IncrementType defaultIncrement = IncrementType.MINOR;
    private String snapshotSuffix = "-SNAPSHOT";
    private CascadePolicy cascadePolicy = CascadePolicy.CASCADE_REFERENCES_ONLY;

    public BumpPolicy() {
    }

    public BumpPolicy(IncrementType defaultIncrement, String snapshotSuffix, CascadePolicy cascadePolicy) {
        if (defaultIncrement != null) this.defaultIncrement = defaultIncrement;
        if (snapshotSuffix != null) this.snapshotSuffix = snapshotSuffix;
        if (cascadePolicy != null) this.cascadePolicy = cascadePolicy;
    }

    public IncrementType getDefaultIncrement() {
        return defaultIncrement;
    }

    public void setDefaultIncrement(IncrementType defaultIncrement) {
        this.defaultIncrement = defaultIncrement;
    }

    public String getSnapshotSuffix() {
        return snapshotSuffix;
    }

    public void setSnapshotSuffix(String snapshotSuffix) {
        this.snapshotSuffix = snapshotSuffix;
    }

    public CascadePolicy getCascadePolicy() {
        return cascadePolicy;
    }

    public void setCascadePolicy(CascadePolicy cascadePolicy) {
        this.cascadePolicy = cascadePolicy;
    }

    /**
     * Given a version like "1.2.3" or "1.2.3-SNAPSHOT", calculates the next snapshot version.
     */
    public String bumpVersion(String currentVersion, IncrementType increment) {
        if (currentVersion == null || currentVersion.isEmpty()) {
            return "1.0.0" + snapshotSuffix;
        }
        String cleanVer = currentVersion;
        if (cleanVer.endsWith(snapshotSuffix)) {
            cleanVer = cleanVer.substring(0, cleanVer.length() - snapshotSuffix.length());
        }
        String[] parts = cleanVer.split("\\.");
        int major = 1, minor = 0, patch = 0;
        try {
            if (parts.length > 0) major = Integer.parseInt(parts[0]);
            if (parts.length > 1) minor = Integer.parseInt(parts[1]);
            if (parts.length > 2) patch = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            // If non-numeric suffix exists like 1.2.3.Final
            return currentVersion + snapshotSuffix;
        }

        IncrementType inc = increment != null ? increment : defaultIncrement;
        switch (inc) {
            case MAJOR:
                major++;
                minor = 0;
                patch = 0;
                break;
            case MINOR:
                minor++;
                patch = 0;
                break;
            case PATCH:
                patch++;
                break;
        }

        if (parts.length == 1) {
            return major + snapshotSuffix;
        } else if (parts.length == 2) {
            return major + "." + minor + snapshotSuffix;
        } else {
            return major + "." + minor + "." + patch + snapshotSuffix;
        }
    }

    /**
     * Strips snapshot suffix or resolves release version.
     */
    public String toReleaseVersion(String currentVersion) {
        if (currentVersion == null) return null;
        if (currentVersion.endsWith(snapshotSuffix)) {
            return currentVersion.substring(0, currentVersion.length() - snapshotSuffix.length());
        }
        if (currentVersion.endsWith("-SNAPSHOT")) {
            return currentVersion.substring(0, currentVersion.length() - "-SNAPSHOT".length());
        }
        return currentVersion;
    }

    public boolean isSnapshot(String version) {
        if (version == null) return false;
        return version.endsWith(snapshotSuffix) || version.endsWith("-SNAPSHOT");
    }
}
