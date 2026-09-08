package net.thevpc.nmvn.lib.config;

import net.thevpc.nmvn.lib.model.MavenCoord;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.io.NPath;

import java.time.Instant;
import java.util.*;

public class VersionHistoryStore {
    public static class Entry {
        private final String commitHash;
        private final String groupId;
        private final String artifactId;
        private final String version;
        private final String timestamp;

        public Entry(String commitHash, String groupId, String artifactId, String version, String timestamp) {
            this.commitHash = commitHash;
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.timestamp = timestamp != null ? timestamp : Instant.now().toString();
        }

        public String getCommitHash() {
            return commitHash;
        }

        public String getGroupId() {
            return groupId;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public String getVersion() {
            return version;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public MavenCoord toGa() {
            return new MavenCoord(groupId, artifactId);
        }

        public MavenCoord toGav() {
            return new MavenCoord(groupId, artifactId, version);
        }

        public NId toId() {
            return NId.of(groupId, artifactId, version);
        }
    }

    private final NPath file;
    private final List<Entry> entries = new ArrayList<>();

    public VersionHistoryStore(NPath file) {
        net.thevpc.nuts.Nuts.require();
        this.file = file;
        load();
    }

    public synchronized void record(String commitHash, String groupId, String artifactId, String version) {
        entries.add(new Entry(commitHash, groupId, artifactId, version, Instant.now().toString()));
    }

    public synchronized Optional<Entry> findLastRelease(NId ga) {
        if (ga == null) return Optional.empty();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.getGroupId().equals(ga.groupId()) && e.getArtifactId().equals(ga.artifactId())) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<Entry> findLastRelease(MavenCoord ga) {
        return ga != null ? findLastRelease(ga.toGa()) : Optional.empty();
    }

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public synchronized void load() {
        entries.clear();
        if (file == null || !file.isRegularFile()) {
            return;
        }
        try {
            NElement elem = NElementReader.ofTson().read(file);
            if (elem == null) return;
            elem.asObject().flatMap(obj -> obj.get("history")).flatMap(NElement::asArray).ifPresent(arr -> {
                for (NElement item : arr) {
                    item.asObject().ifPresent(obj -> {
                        String c = obj.getStringValue("commit").orNull();
                        String g = obj.getStringValue("groupId").orNull();
                        String a = obj.getStringValue("artifactId").orNull();
                        String v = obj.getStringValue("version").orNull();
                        String t = obj.getStringValue("timestamp").orNull();
                        if (c != null && g != null && a != null && v != null) {
                            entries.add(new Entry(c, g, a, v, t));
                        }
                    });
                }
            });
        } catch (Exception ignored) {
        }
    }

    public synchronized void save() {
        if (file == null) return;
        try {
            NPath parent = file.parent();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            NObjectElementBuilder root = NElement.ofObjectBuilder();
            NArrayElementBuilder arr = NElement.ofArrayBuilder();
            for (Entry e : entries) {
                NObjectElementBuilder item = NElement.ofObjectBuilder();
                item.set("commit", NElement.ofString(e.getCommitHash()));
                item.set("groupId", NElement.ofString(e.getGroupId()));
                item.set("artifactId", NElement.ofString(e.getArtifactId()));
                item.set("version", NElement.ofString(e.getVersion()));
                item.set("timestamp", NElement.ofString(e.getTimestamp()));
                arr.add(item.build());
            }
            root.set("history", arr.build());

            NElementWriter.ofTson()
                    .formatter(NElementFormatter.ofPretty())
                    .write(root.build(), file);
        } catch (Exception ignored) {
        }
    }
}
