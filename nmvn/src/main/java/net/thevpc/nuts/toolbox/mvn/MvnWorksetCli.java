package net.thevpc.nuts.toolbox.mvn;

import net.thevpc.nmvn.lib.config.NMvnConfig;
import net.thevpc.nmvn.lib.config.NMvnConfigLoader;
import net.thevpc.nmvn.lib.model.BumpPolicy;
import net.thevpc.nmvn.lib.model.PomArtifact;
import net.thevpc.nmvn.lib.service.ScanResult;
import net.thevpc.nmvn.lib.service.VersionService;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.core.NSession;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.io.NOut;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.util.NRef;

import java.io.IOException;
import java.util.*;

public class MvnWorksetCli {

    private final NSession session;
    private final VersionService versionService = new VersionService();

    public MvnWorksetCli(NSession session) {
        this.session = session;
    }

    public int run(String[] args, boolean jsonOutput) {
        NCmdLine cmd = NCmdLine.of(args);
        NRef<String> subCommand = NRef.ofNull();
        NRef<String> worksetName = NRef.ofNull();
        NRef<String> rootOp = NRef.ofNull();
        NRef<Boolean> recent = NRef.of(false);
        NRef<Boolean> scan = NRef.of(false);
        NRef<Boolean> jsonOutputRef = NRef.of(jsonOutput);

        List<String> roots = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        NRef<String> defaultIncrement = NRef.ofNull();
        NRef<String> snapshotSuffix = NRef.ofNull();
        NRef<String> cascadePolicy = NRef.ofNull();
        NRef<String> historyFile = NRef.ofNull();

        while (cmd.hasNext()) {
            if (subCommand.isNull()) {
                if (session.configureFirst(cmd)) {
                    // handled by nuts
                } else if (!cmd.matcher()
                        .when("list", "ls").asArg(a -> subCommand.set("list"))
                        .when("path").asArg(a -> subCommand.set("path"))
                        .when("get", "cat", "view").asArg(a -> subCommand.set("get"))
                        .when("set").asArg(a -> subCommand.set("set"))
                        .when("add-root", "add", "add-folder").asArg(a -> subCommand.set("add-root"))
                        .when("remove-root", "rm-root", "remove", "rm", "remove-folder").asArg(a -> subCommand.set("remove-root"))
                        .when("root", "roots").asArg(a -> subCommand.set("root"))
                        .when("scan").asArg(a -> subCommand.set("scan"))
                        .when("edit").asArg(a -> subCommand.set("edit"))
                        .when("-j", "--json").asFlag(a -> jsonOutputRef.set(a.booleanValue()))
                        .when("--recent").asFlag(a -> recent.set(a.booleanValue()))
                        .when("--scan").asFlag(a -> scan.set(a.booleanValue()))
                        .when("--name", "--workset", "--ws", "--config").asEntry(a -> worksetName.set(a.stringValue()))
                        .anyMatch()) {
                    cmd.throwUnexpectedArgument();
                }
            } else {
                if (session.configureFirst(cmd)) {
                    // handled by nuts
                } else if (!cmd.matcher()
                        .when("-j", "--json").asFlag(a -> jsonOutputRef.set(a.booleanValue()))
                        .when("--recent").asFlag(a -> recent.set(a.booleanValue()))
                        .when("--scan").asFlag(a -> scan.set(a.booleanValue()))
                        .when("--name", "--workset", "--ws", "--config").asEntry(a -> worksetName.set(a.stringValue()))
                        .when("--root", "--folder").asEntry(a -> roots.add(a.stringValue()))
                        .when("--exclude").asEntry(a -> excludes.add(a.stringValue()))
                        .when("--default-increment").asEntry(a -> defaultIncrement.set(a.stringValue()))
                        .when("--snapshot-suffix").asEntry(a -> snapshotSuffix.set(a.stringValue()))
                        .when("--cascade-policy").asEntry(a -> cascadePolicy.set(a.stringValue()))
                        .when("--history-file").asEntry(a -> historyFile.set(a.stringValue()))
                        .whenNonOption().asArg(a -> {
                            String val = a.asString().get();
                            String sc = subCommand.get();
                            if ("root".equals(sc)) {
                                if (rootOp.isNull()) {
                                    rootOp.set(val);
                                } else {
                                    roots.add(val);
                                }
                            } else if ("add-root".equals(sc) || "remove-root".equals(sc)) {
                                roots.add(val);
                            } else {
                                if (worksetName.isNull()) {
                                    worksetName.set(val);
                                } else {
                                    roots.add(val);
                                }
                            }
                        })
                        .anyMatch()) {
                    cmd.throwUnexpectedArgument();
                }
            }
        }

        if (subCommand.isNull()) {
            subCommand.set("list");
        }

        // Normalize root subcommand if needed
        if ("root".equals(subCommand.get())) {
            String ro = rootOp.get();
            if (ro == null || "list".equals(ro) || "ls".equals(ro)) {
                subCommand.set("root-list");
            } else if ("add".equals(ro) || "add-root".equals(ro) || "add-folder".equals(ro)) {
                subCommand.set("add-root");
            } else if ("remove".equals(ro) || "rm".equals(ro) || "remove-root".equals(ro) || "rm-root".equals(ro)) {
                subCommand.set("remove-root");
            } else {
                NOut.println(NMsg.ofC("Unknown root sub-command: %s (expected list|add|remove)", ro));
                return 1;
            }
        }

        NPath workingDir = NPath.ofUserDirectory();

        try {
            switch (subCommand.get()) {
                case "list":
                    return doList(recent.get(), jsonOutputRef.get());
                case "path":
                    return doPath(worksetName.get(), workingDir, jsonOutputRef.get());
                case "get":
                    return doGet(worksetName.get(), workingDir, jsonOutputRef.get());
                case "set":
                    return doSet(worksetName.get(), workingDir, roots, excludes,
                            defaultIncrement.get(), snapshotSuffix.get(), cascadePolicy.get(), historyFile.get(), jsonOutputRef.get());
                case "add-root":
                    return doAddRoot(worksetName.get(), workingDir, roots, scan.get(), jsonOutputRef.get());
                case "remove-root":
                    return doRemoveRoot(worksetName.get(), workingDir, roots, scan.get(), jsonOutputRef.get());
                case "root-list":
                    return doListRoots(worksetName.get(), workingDir, jsonOutputRef.get());
                case "scan":
                    return doScan(worksetName.get(), workingDir, jsonOutputRef.get());
                case "edit":
                    return doEdit(worksetName.get(), workingDir);
                default:
                    NOut.println(NMsg.ofC("Unknown workset sub-command: %s", subCommand.get()));
                    return 1;
            }
        } catch (Exception e) {
            NOut.println(NMsg.ofStyled("Error: " + e.getMessage(), NTextStyle.danger()));
            return 1;
        }
    }

    private int doList(boolean recent, boolean jsonOutput) {
        if (recent) {
            List<NPath> recentConfigs = NMvnConfigLoader.loadRecentConfigs();
            if (jsonOutput) {
                NArrayElementBuilder arr = NElement.ofArrayBuilder();
                for (NPath p : recentConfigs) {
                    NObjectElementBuilder obj = NElement.ofObjectBuilder();
                    obj.set("path", NElement.ofString(p.toString()));
                    obj.set("exists", NElement.ofBoolean(p.exists()));
                    arr.add(obj.build());
                }
                NOut.println(NElementWriter.ofJson().formatPlain(arr.build()));
                return 0;
            }

            NOut.println(NMsg.ofStyled(String.format("Recent worksets (%d):", recentConfigs.size()), NTextStyle.primary4()));
            if (recentConfigs.isEmpty()) {
                NOut.println("  (none)");
            } else {
                for (int i = 0; i < recentConfigs.size(); i++) {
                    NPath p = recentConfigs.get(i);
                    boolean exists = p.exists();
                    NOut.println(NMsg.ofC("  %2d) %s%s", i + 1, p, exists ? "" : " (missing)"));
                }
            }
            return 0;
        }

        NPath folder = NMvnConfigLoader.getAppConfigFolder();
        List<NPath> configs = NMvnConfigLoader.listNamedConfigs();
        if (jsonOutput) {
            NArrayElementBuilder arr = NElement.ofArrayBuilder();
            for (NPath p : configs) {
                NObjectElementBuilder obj = NElement.ofObjectBuilder();
                obj.set("name", NElement.ofString(p.name()));
                obj.set("path", NElement.ofString(p.toString()));
                arr.add(obj.build());
            }
            NOut.println(NElementWriter.ofJson().formatPlain(arr.build()));
            return 0;
        }

        NOut.println(NMsg.ofStyled(String.format("Worksets in %s (%d):", folder, configs.size()), NTextStyle.primary4()));
        if (configs.isEmpty()) {
            NOut.println("  (none)");
        } else {
            for (NPath p : configs) {
                NOut.println(NMsg.ofC("  - [bold %s] -> %s", p.name(), p));
            }
        }
        return 0;
    }

    private int doPath(String name, NPath workingDir, boolean jsonOutput) {
        NPath resolved = NMvnConfigLoader.resolveConfigFile(name, workingDir);
        if (jsonOutput) {
            NObjectElementBuilder obj = NElement.ofObjectBuilder();
            obj.set("name", NElement.ofString(name != null ? name : ""));
            obj.set("path", NElement.ofString(resolved.toString()));
            obj.set("exists", NElement.ofBoolean(resolved.exists()));
            NOut.println(NElementWriter.ofJson().formatPlain(obj.build()));
            return 0;
        }
        NOut.println(resolved.toString());
        return 0;
    }

    private int doGet(String name, NPath workingDir, boolean jsonOutput) throws IOException {
        NPath resolved = NMvnConfigLoader.resolveConfigFile(name, workingDir);
        if (!resolved.isRegularFile()) {
            NOut.println(NMsg.ofStyled("Workset file not found: " + resolved, NTextStyle.danger()));
            return 1;
        }
        NElement elem = NElementReader.ofTson().read(resolved);
        if (elem == null) {
            NOut.println(NMsg.ofStyled("Empty or invalid workset file: " + resolved, NTextStyle.warn()));
            return 1;
        }
        NMvnConfigLoader.recordRecentConfig(resolved);
        if (jsonOutput) {
            NOut.println(NElementWriter.ofJson().formatPlain(elem));
        } else {
            NOut.println(NElementWriter.ofTson().formatter(NElementFormatter.ofPretty()).format(elem));
        }
        return 0;
    }

    private int doSet(String name, NPath workingDir, List<String> roots, List<String> excludes,
                      String defaultIncrement, String snapshotSuffix, String cascadePolicy,
                      String historyFile, boolean jsonOutput) throws IOException {
        NPath targetFile = resolveTargetForSave(name, workingDir);
        NMvnConfig config = targetFile.isRegularFile() ? NMvnConfigLoader.load(targetFile) : new NMvnConfig();

        if (!roots.isEmpty()) {
            config.setRoots(roots);
        }
        if (!excludes.isEmpty()) {
            config.getExcludes().addAll(excludes);
        }
        if (defaultIncrement != null && !defaultIncrement.isEmpty()) {
            config.getBumpPolicy().setDefaultIncrement(BumpPolicy.IncrementType.parse(defaultIncrement));
        }
        if (snapshotSuffix != null && !snapshotSuffix.isEmpty()) {
            config.getBumpPolicy().setSnapshotSuffix(snapshotSuffix);
        }
        if (cascadePolicy != null && !cascadePolicy.isEmpty()) {
            config.getBumpPolicy().setCascadePolicy(BumpPolicy.CascadePolicy.parse(cascadePolicy));
        }
        if (historyFile != null && !historyFile.isEmpty()) {
            config.setHistoryFile(historyFile);
        }

        if (targetFile.parent() != null) {
            targetFile.parent().mkdirs();
        }
        NMvnConfigLoader.save(config, targetFile);

        if (jsonOutput) {
            NObjectElementBuilder obj = NElement.ofObjectBuilder();
            obj.set("status", NElement.ofString("saved"));
            obj.set("path", NElement.ofString(targetFile.toString()));
            NOut.println(NElementWriter.ofJson().formatPlain(obj.build()));
        } else {
            NOut.println(NMsg.ofStyled("Saved workset to: " + targetFile, NTextStyle.success()));
        }
        return 0;
    }

    private int doAddRoot(String worksetName, NPath workingDir, List<String> folders, boolean scan, boolean jsonOutput) throws IOException {
        if (folders.isEmpty()) {
            NOut.println(NMsg.ofStyled("No folders specified to add. Usage: nmvn workset add-root <folder>... [--scan]", NTextStyle.warn()));
            return 1;
        }

        NPath configFile = resolveTargetForSave(worksetName, workingDir);
        NMvnConfig config = configFile.isRegularFile() ? NMvnConfigLoader.load(configFile) : new NMvnConfig();
        List<String> currentRoots = config.getRoots();
        if (currentRoots == null) {
            currentRoots = new ArrayList<>();
            config.setRoots(currentRoots);
        }

        List<String> added = new ArrayList<>();
        List<String> alreadyPresent = new ArrayList<>();

        for (String folderStr : folders) {
            NPath folderPath = workingDir.resolve(folderStr).normalize();
            String stored;
            if (!folderPath.isAbsolute() || folderPath.toString().startsWith(workingDir.toString())) {
                try {
                    stored = workingDir.relativize(folderPath).toString();
                    if (stored.isEmpty()) {
                        stored = ".";
                    }
                } catch (Exception e) {
                    stored = folderStr;
                }
            } else {
                stored = folderStr;
            }

            boolean existsInRoots = false;
            for (String r : currentRoots) {
                if (r.equals(stored) || r.equals(folderStr) || workingDir.resolve(r).normalize().equals(folderPath)) {
                    existsInRoots = true;
                    break;
                }
            }

            if (existsInRoots) {
                alreadyPresent.add(stored);
            } else {
                currentRoots.add(stored);
                added.add(stored);
            }
        }

        if (!added.isEmpty()) {
            if (configFile.parent() != null) {
                configFile.parent().mkdirs();
            }
            NMvnConfigLoader.save(config, configFile);
            NMvnConfigLoader.recordRecentConfig(configFile);
        }

        ScanResult scanResult = null;
        if (scan) {
            scanResult = versionService.scan(config, workingDir);
        }

        if (jsonOutput) {
            NObjectElementBuilder res = NElement.ofObjectBuilder();
            res.set("status", NElement.ofString("success"));
            res.set("workset", NElement.ofString(configFile.toString()));
            NArrayElementBuilder addedArr = NElement.ofArrayBuilder();
            for (String s : added) addedArr.add(NElement.ofString(s));
            res.set("addedRoots", addedArr.build());

            NArrayElementBuilder presentArr = NElement.ofArrayBuilder();
            for (String s : alreadyPresent) presentArr.add(NElement.ofString(s));
            res.set("alreadyPresentRoots", presentArr.build());

            NArrayElementBuilder allRootsArr = NElement.ofArrayBuilder();
            for (String s : currentRoots) allRootsArr.add(NElement.ofString(s));
            res.set("roots", allRootsArr.build());

            if (scanResult != null) {
                NArrayElementBuilder artsArr = NElement.ofArrayBuilder();
                for (PomArtifact a : scanResult.getArtifacts().values()) {
                    NObjectElementBuilder aObj = NElement.ofObjectBuilder();
                    aObj.set("groupId", NElement.ofString(a.getGroupId()));
                    aObj.set("artifactId", NElement.ofString(a.getArtifactId()));
                    aObj.set("version", NElement.ofString(a.getResolvedVersion()));
                    aObj.set("path", NElement.ofString(a.getPath().toString()));
                    artsArr.add(aObj.build());
                }
                res.set("artifacts", artsArr.build());
            }
            NOut.println(NElementWriter.ofJson().formatPlain(res.build()));
            return 0;
        }

        if (!added.isEmpty()) {
            NOut.println(NMsg.ofStyled(String.format("Added %d root(s) to workset (%s):", added.size(), configFile), NTextStyle.success()));
            for (String s : added) {
                NPath p = workingDir.resolve(s);
                boolean exists = p.isDirectory();
                NOut.println(NMsg.ofC("  + [bold %s]%s", s, exists ? "" : " (warning: directory not found)"));
            }
        } else {
            NOut.println(NMsg.ofStyled("No new roots added. All specified folders are already in the workset.", NTextStyle.warn()));
        }

        if (!alreadyPresent.isEmpty() && !added.isEmpty()) {
            for (String s : alreadyPresent) {
                NOut.println(NMsg.ofC("  = %s (already in workset)", s));
            }
        }

        if (scan && scanResult != null) {
            NOut.println();
            renderScanSummary(scanResult);
        }
        return 0;
    }

    private int doRemoveRoot(String worksetName, NPath workingDir, List<String> folders, boolean scan, boolean jsonOutput) throws IOException {
        if (folders.isEmpty()) {
            NOut.println(NMsg.ofStyled("No folders specified to remove. Usage: nmvn workset remove-root <folder>... [--scan]", NTextStyle.warn()));
            return 1;
        }

        NPath configFile = NMvnConfigLoader.resolveConfigFile(worksetName, workingDir);
        if (!configFile.isRegularFile()) {
            NOut.println(NMsg.ofStyled("Workset file not found: " + configFile, NTextStyle.danger()));
            return 1;
        }
        NMvnConfig config = NMvnConfigLoader.load(configFile);
        List<String> currentRoots = config.getRoots();
        if (currentRoots == null || currentRoots.isEmpty()) {
            NOut.println(NMsg.ofStyled("Workset has no configured roots.", NTextStyle.warn()));
            return 0;
        }

        List<String> removed = new ArrayList<>();
        List<String> notFound = new ArrayList<>();

        for (String folderStr : folders) {
            NPath folderPath = workingDir.resolve(folderStr).normalize();
            String matched = null;
            for (String r : currentRoots) {
                if (r.equals(folderStr) || workingDir.resolve(r).normalize().equals(folderPath)) {
                    matched = r;
                    break;
                }
            }
            if (matched != null) {
                currentRoots.remove(matched);
                removed.add(matched);
            } else {
                notFound.add(folderStr);
            }
        }

        if (!removed.isEmpty()) {
            NMvnConfigLoader.save(config, configFile);
            NMvnConfigLoader.recordRecentConfig(configFile);
        }

        ScanResult scanResult = null;
        if (scan) {
            scanResult = versionService.scan(config, workingDir);
        }

        if (jsonOutput) {
            NObjectElementBuilder res = NElement.ofObjectBuilder();
            res.set("status", NElement.ofString("success"));
            res.set("workset", NElement.ofString(configFile.toString()));
            NArrayElementBuilder remArr = NElement.ofArrayBuilder();
            for (String s : removed) remArr.add(NElement.ofString(s));
            res.set("removedRoots", remArr.build());

            NArrayElementBuilder notFoundArr = NElement.ofArrayBuilder();
            for (String s : notFound) notFoundArr.add(NElement.ofString(s));
            res.set("notFoundRoots", notFoundArr.build());

            NArrayElementBuilder allRootsArr = NElement.ofArrayBuilder();
            for (String s : currentRoots) allRootsArr.add(NElement.ofString(s));
            res.set("roots", allRootsArr.build());

            if (scanResult != null) {
                NArrayElementBuilder artsArr = NElement.ofArrayBuilder();
                for (PomArtifact a : scanResult.getArtifacts().values()) {
                    NObjectElementBuilder aObj = NElement.ofObjectBuilder();
                    aObj.set("groupId", NElement.ofString(a.getGroupId()));
                    aObj.set("artifactId", NElement.ofString(a.getArtifactId()));
                    aObj.set("version", NElement.ofString(a.getResolvedVersion()));
                    aObj.set("path", NElement.ofString(a.getPath().toString()));
                    artsArr.add(aObj.build());
                }
                res.set("artifacts", artsArr.build());
            }
            NOut.println(NElementWriter.ofJson().formatPlain(res.build()));
            return 0;
        }

        if (!removed.isEmpty()) {
            NOut.println(NMsg.ofStyled(String.format("Removed %d root(s) from workset (%s):", removed.size(), configFile), NTextStyle.success()));
            for (String s : removed) {
                NOut.println(NMsg.ofC("  - [bold %s]", s));
            }
        }
        if (!notFound.isEmpty()) {
            for (String s : notFound) {
                NOut.println(NMsg.ofC("  ? %s (not found in workset roots)", s));
            }
        }

        if (scan && scanResult != null) {
            NOut.println();
            renderScanSummary(scanResult);
        }
        return 0;
    }

    private int doListRoots(String worksetName, NPath workingDir, boolean jsonOutput) throws IOException {
        NPath configFile = NMvnConfigLoader.resolveConfigFile(worksetName, workingDir);
        if (!configFile.isRegularFile()) {
            NOut.println(NMsg.ofStyled("Workset file not found: " + configFile, NTextStyle.danger()));
            return 1;
        }
        NMvnConfig config = NMvnConfigLoader.load(configFile);
        List<String> roots = config.getRoots();
        if (roots == null) roots = Collections.emptyList();

        if (jsonOutput) {
            NArrayElementBuilder arr = NElement.ofArrayBuilder();
            for (String r : roots) {
                NObjectElementBuilder obj = NElement.ofObjectBuilder();
                obj.set("root", NElement.ofString(r));
                NPath p = workingDir.resolve(r);
                obj.set("path", NElement.ofString(p.toAbsolute().toString()));
                obj.set("exists", NElement.ofBoolean(p.isDirectory()));
                arr.add(obj.build());
            }
            NOut.println(NElementWriter.ofJson().formatPlain(arr.build()));
            return 0;
        }

        NOut.println(NMsg.ofStyled(String.format("Roots in workset (%s) [%d]:", configFile, roots.size()), NTextStyle.primary4()));
        if (roots.isEmpty()) {
            NOut.println("  (none)");
        } else {
            for (int i = 0; i < roots.size(); i++) {
                String r = roots.get(i);
                NPath p = workingDir.resolve(r);
                boolean exists = p.isDirectory();
                NOut.println(NMsg.ofC("  %2d) [bold %s]%s", i + 1, r, exists ? "" : " (missing)"));
            }
        }
        return 0;
    }

    private int doScan(String worksetName, NPath workingDir, boolean jsonOutput) throws IOException {
        NPath configFile = NMvnConfigLoader.resolveConfigFile(worksetName, workingDir);
        MvnVersionCli versionCli = new MvnVersionCli(session);
        return versionCli.run(new String[]{"scan", "--workset", configFile.toString()}, jsonOutput);
    }

    private void renderScanSummary(ScanResult scan) {
        Map<NId, PomArtifact> artifacts = scan.getArtifacts();
        NOut.println(NMsg.ofStyled(String.format("Workset scan: %d Maven artifact(s) discovered:", artifacts.size()), NTextStyle.primary4()));
        for (PomArtifact a : artifacts.values()) {
            NOut.println(NMsg.ofC("  * [bold %s:%s] %s -> %s",
                    a.getGroupId(), a.getArtifactId(), a.getResolvedVersion(), a.getPath()));
        }
    }

    private int doEdit(String name, NPath workingDir) throws IOException {
        NPath targetFile = resolveTargetForSave(name, workingDir);

        if (!targetFile.exists()) {
            if (targetFile.parent() != null) {
                targetFile.parent().mkdirs();
            }
            NMvnConfig config = new NMvnConfig();
            NMvnConfigLoader.save(config, targetFile);
        }

        String editor = System.getenv("EDITOR");
        if (editor == null || editor.trim().isEmpty()) {
            editor = System.getenv("VISUAL");
        }

        if (editor != null && !editor.trim().isEmpty()) {
            try {
                Process p = new ProcessBuilder(editor, targetFile.toString()).inheritIO().start();
                return p.waitFor();
            } catch (Exception e) {
                NOut.println(NMsg.ofStyled("Failed to launch editor " + editor + ": " + e.getMessage(), NTextStyle.danger()));
            }
        }

        NOut.println(NMsg.ofC("Workset file: [bold %s]", targetFile));
        return 0;
    }

    private NPath resolveTargetForSave(String name, NPath workingDir) {
        if (name == null || name.isEmpty()) {
            NPath localWs = workingDir.resolve(NMvnConfigLoader.WORKSET_CONFIG_FILE);
            if (localWs.isRegularFile()) {
                return localWs;
            }
            NPath localNmvn = workingDir.resolve(NMvnConfigLoader.DEFAULT_CONFIG_FILE);
            if (localNmvn.isRegularFile()) {
                return localNmvn;
            }
            return localWs; // Default new workset to workset.tson
        } else if (NPath.of(name).isName()) {
            String fileName = name.endsWith(".tson") ? name : name + ".tson";
            return NMvnConfigLoader.getAppConfigFolder().resolve(fileName);
        } else {
            return NPath.of(name).isAbsolute() ? NPath.of(name) : workingDir.resolve(name);
        }
    }
}
