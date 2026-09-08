package net.thevpc.nmvn.lib.config;

import net.thevpc.nmvn.lib.model.BumpInstruction;
import net.thevpc.nmvn.lib.model.BumpPolicy;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.core.NStoreKey;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.io.NPath;

import java.util.*;

public class NMvnConfigLoader {

    public static final String DEFAULT_CONFIG_FILE = "nmvn.tson";
    public static final String WORKSET_CONFIG_FILE = "workset.tson";
    public static final String ALT_CONFIG_FILE = ".nmvn/config.tson";
    public static final String RECENT_CONFIGS_FILE = "recent-configs.tson";
    public static final NId APP_ID = NId.of("net.thevpc.nmvn:nmvn");

    public static NPath getAppConfigFolder() {
        Nuts.require();
        return NPath.of(NStoreKey.ofConf(APP_ID));
    }

    public static NPath resolveConfigFile(String cliConfigPath, NPath workingDir) {
        Nuts.require();
        if (cliConfigPath != null && !cliConfigPath.trim().isEmpty()) {
            String trimmed = cliConfigPath.trim();
            NPath nPath = NPath.of(trimmed);
            if (nPath.isName()) {
                // 1. Check working directory for exact name
                NPath inWd = workingDir.resolve(trimmed);
                if (inWd.isRegularFile()) {
                    return inWd;
                }
                // 2. Check working directory with .tson appended
                if (!trimmed.endsWith(".tson")) {
                    NPath inWdTson = workingDir.resolve(trimmed + ".tson");
                    if (inWdTson.isRegularFile()) {
                        return inWdTson;
                    }
                }
                // 3. Check Nuts config folder for exact name
                NPath inNutsConf = getAppConfigFolder().resolve(trimmed);
                if (inNutsConf.isRegularFile()) {
                    return inNutsConf;
                }
                // 4. Check Nuts config folder with .tson appended
                if (!trimmed.endsWith(".tson")) {
                    NPath inNutsConfTson = getAppConfigFolder().resolve(trimmed + ".tson");
                    if (inNutsConfTson.isRegularFile()) {
                        return inNutsConfTson;
                    }
                }
                // If neither exists yet, default to workingDir resolve
                return inWd;
            } else {
                return nPath.isAbsolute() ? nPath : workingDir.resolve(nPath);
            }
        }
        NPath p0 = workingDir.resolve(WORKSET_CONFIG_FILE);
        if (p0.isRegularFile()) {
            return p0;
        }
        NPath p1 = workingDir.resolve(DEFAULT_CONFIG_FILE);
        if (p1.isRegularFile()) {
            return p1;
        }
        NPath p2 = workingDir.resolve(ALT_CONFIG_FILE);
        if (p2.isRegularFile()) {
            return p2;
        }
        NPath p3 = getAppConfigFolder().resolve(WORKSET_CONFIG_FILE);
        if (p3.isRegularFile()) {
            return p3;
        }
        NPath p4 = getAppConfigFolder().resolve(DEFAULT_CONFIG_FILE);
        if (p4.isRegularFile()) {
            return p4;
        }
        return p1; // default to nmvn.tson even if doesn't exist yet
    }

    public static List<NPath> loadRecentConfigs() {
        Nuts.require();
        NPath recentFile = getAppConfigFolder().resolve(RECENT_CONFIGS_FILE);
        List<NPath> list = new ArrayList<>();
        if (!recentFile.isRegularFile()) {
            return list;
        }
        try {
            NElement elem = NElementReader.ofTson().read(recentFile);
            if (elem != null) {
                elem.asArray().ifPresent(arr -> {
                    for (NElement item : arr) {
                        item.asStringValue().ifPresent(s -> {
                            list.add(NPath.of(s));
                        });
                    }
                });
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static void recordRecentConfig(NPath path) {
        if (path == null) return;
        Nuts.require();
        try {
            NPath abs = path.toAbsolute().normalize();
            List<NPath> recent = new ArrayList<>();
            recent.add(abs);
            for (NPath p : loadRecentConfigs()) {
                if (!p.toAbsolute().normalize().equals(abs)) {
                    recent.add(p);
                }
            }
            if (recent.size() > 20) {
                recent = recent.subList(0, 20);
            }
            NArrayElementBuilder arr = NElement.ofArrayBuilder();
            for (NPath p : recent) {
                arr.add(NElement.ofString(p.toString()));
            }
            NPath folder = getAppConfigFolder();
            if (!folder.isDirectory()) {
                folder.mkdirs();
            }
            NElementWriter.ofTson()
                    .formatter(NElementFormatter.ofPretty())
                    .write(arr.build(), folder.resolve(RECENT_CONFIGS_FILE));
        } catch (Exception ignored) {
        }
    }

    public static List<NPath> listNamedConfigs() {
        Nuts.require();
        NPath folder = getAppConfigFolder();
        List<NPath> result = new ArrayList<>();
        if (folder.isDirectory()) {
            for (NPath p : folder.list()) {
                String name = p.name();
                if (name.endsWith(".tson") && !RECENT_CONFIGS_FILE.equals(name)) {
                    result.add(p);
                }
            }
        }
        return result;
    }

    public static NMvnConfig load(NPath configFile) {
        Nuts.require();
        NMvnConfig config = new NMvnConfig();
        if (configFile == null || !configFile.isRegularFile()) {
            return config;
        }
        recordRecentConfig(configFile);

        NElement element = NElementReader.ofTson().read(configFile);
        if (element == null) {
            return config;
        }

        NObjectElement obj = element.asObject().orNull();
        if (obj == null) {
            return config;
        }

        // roots
        obj.get("roots").flatMap(NElement::asArray).ifPresent(arr -> {
            List<String> rList = new ArrayList<>();
            for (NElement item : arr) {
                item.asStringValue().ifPresent(rList::add);
            }
            if (!rList.isEmpty()) {
                config.setRoots(rList);
            }
        });

        // global excludes
        obj.get("excludes").flatMap(NElement::asArray).ifPresent(arr -> {
            List<String> eList = new ArrayList<>();
            for (NElement item : arr) {
                item.asStringValue().ifPresent(eList::add);
            }
            if (!eList.isEmpty()) {
                config.setExcludes(eList);
            }
        });

        // rootExcludes
        obj.get("rootExcludes").flatMap(NElement::asObject).ifPresent(reObj -> {
            Map<String, List<String>> reMap = new LinkedHashMap<>();
            for (NElement entry : reObj.children()) {
                if (entry instanceof NPairElement) {
                    NPairElement pair = (NPairElement) entry;
                    String rootKey = pair.key().asStringValue().orElse("");
                    List<String> perRootList = new ArrayList<>();
                    pair.value().asArray().ifPresent(arr -> {
                        for (NElement item : arr) {
                            item.asStringValue().ifPresent(perRootList::add);
                        }
                    });
                    reMap.put(rootKey, perRootList);
                }
            }
            config.setRootExcludes(reMap);
        });

        // bumpPolicy
        obj.get("bumpPolicy").flatMap(NElement::asObject).ifPresent(bpObj -> {
            BumpPolicy policy = config.getBumpPolicy();
            bpObj.getStringValue("defaultIncrement").ifPresent(inc -> policy.setDefaultIncrement(BumpPolicy.IncrementType.parse(inc)));
            bpObj.getStringValue("snapshotSuffix").ifPresent(policy::setSnapshotSuffix);
            bpObj.getStringValue("cascadePolicy").ifPresent(cas -> policy.setCascadePolicy(BumpPolicy.CascadePolicy.parse(cas)));
        });

        // instructions
        obj.get("instructions").flatMap(NElement::asArray).ifPresent(arr -> {
            List<BumpInstruction> instList = new ArrayList<>();
            for (NElement item : arr) {
                item.asObject().ifPresent(instObj -> {
                    String g = instObj.getStringValue("groupId").orNull();
                    String a = instObj.getStringValue("artifactId").orNull();
                    String v = instObj.getStringValue("toVersion").orNull();
                    if (g != null && a != null && v != null) {
                        instList.add(new BumpInstruction(g, a, v));
                    }
                });
            }
            config.setInstructions(instList);
        });

        // historyFile
        obj.getStringValue("historyFile").ifPresent(config::setHistoryFile);

        return config;
    }

    public static void save(NMvnConfig config, NPath targetFile) {
        Nuts.require();
        NObjectElementBuilder builder = NElement.ofObjectBuilder();

        // roots
        NArrayElementBuilder rootsArr = NElement.ofArrayBuilder();
        for (String r : config.getRoots()) {
            rootsArr.add(NElement.ofString(r));
        }
        builder.set("roots", rootsArr.build());

        // excludes
        NArrayElementBuilder excArr = NElement.ofArrayBuilder();
        for (String e : config.getExcludes()) {
            excArr.add(NElement.ofString(e));
        }
        builder.set("excludes", excArr.build());

        // rootExcludes
        if (!config.getRootExcludes().isEmpty()) {
            NObjectElementBuilder reObj = NElement.ofObjectBuilder();
            for (Map.Entry<String, List<String>> entry : config.getRootExcludes().entrySet()) {
                NArrayElementBuilder perRootArr = NElement.ofArrayBuilder();
                for (String p : entry.getValue()) {
                    perRootArr.add(NElement.ofString(p));
                }
                reObj.set(entry.getKey(), perRootArr.build());
            }
            builder.set("rootExcludes", reObj.build());
        }

        // bumpPolicy
        NObjectElementBuilder bpObj = NElement.ofObjectBuilder();
        bpObj.set("defaultIncrement", NElement.ofString(config.getBumpPolicy().getDefaultIncrement().name().toLowerCase()));
        bpObj.set("snapshotSuffix", NElement.ofString(config.getBumpPolicy().getSnapshotSuffix()));
        bpObj.set("cascadePolicy", NElement.ofString(config.getBumpPolicy().getCascadePolicy().name().toLowerCase().replace("_", "-")));
        builder.set("bumpPolicy", bpObj.build());

        // instructions
        if (!config.getInstructions().isEmpty()) {
            NArrayElementBuilder instArr = NElement.ofArrayBuilder();
            for (BumpInstruction inst : config.getInstructions()) {
                NObjectElementBuilder instObj = NElement.ofObjectBuilder();
                instObj.set("groupId", NElement.ofString(inst.getGroupId()));
                instObj.set("artifactId", NElement.ofString(inst.getArtifactId()));
                instObj.set("toVersion", NElement.ofString(inst.getToVersion()));
                instArr.add(instObj.build());
            }
            builder.set("instructions", instArr.build());
        }

        builder.set("historyFile", NElement.ofString(config.getHistoryFile()));

        NPath parent = targetFile.parent();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        NElementWriter.ofTson()
                .formatter(NElementFormatter.ofPretty())
                .write(builder.build(), targetFile);
        recordRecentConfig(targetFile);
    }
}
