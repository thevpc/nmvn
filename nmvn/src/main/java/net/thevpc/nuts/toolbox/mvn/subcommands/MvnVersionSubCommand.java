package net.thevpc.nuts.toolbox.mvn.subcommands;

import net.thevpc.nmvn.lib.config.NMvnConfig;
import net.thevpc.nmvn.lib.config.NMvnConfigLoader;
import net.thevpc.nmvn.lib.diagnostic.DiagnosticIssue;
import net.thevpc.nmvn.lib.diagnostic.DiagnosticReport;
import net.thevpc.nmvn.lib.diagnostic.DiagnosticSeverity;
import net.thevpc.nmvn.lib.graph.DependencyEdge;
import net.thevpc.nmvn.lib.graph.MavenDependencyGraph;
import net.thevpc.nmvn.lib.model.*;
import net.thevpc.nmvn.lib.service.BumpResult;
import net.thevpc.nmvn.lib.service.ReleaseResult;
import net.thevpc.nmvn.lib.service.ScanResult;
import net.thevpc.nmvn.lib.service.VersionService;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.concurrent.NCallable;
import net.thevpc.nuts.core.NSession;
import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.io.NOut;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NTextStyle;

import java.util.*;

public class MvnVersionSubCommand {

    private final VersionService versionService = new VersionService();

    public MvnVersionSubCommand() {
    }

    private static class SharedOptions {
        String subCommand = null;
        String configPath = null;
        boolean strict = false;
        boolean failOnWarning = false;
        boolean noIgnore = false;
        boolean allArtifacts = false;
        BumpPolicy.CascadePolicy cascadePolicy = null;
        BumpPolicy.IncrementType increment = null;
        List<String> roots = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        List<BumpInstruction> cliInstructions = new ArrayList<>();
        Map<NId, String> explicitUpdates = new LinkedHashMap<>();
        Map<NId, String> explicitReleases = new LinkedHashMap<>();

    }

    public int run(String[] args) {
        NSession scoped = NSession.of().copy();
        return scoped.callWith(NCallable.of(() -> run0(args)));
    }

    private int run0(String[] args) {
        SharedOptions oo = new SharedOptions();
        NCmdLine cmd = NCmdLine.of(args);
        boolean commandConsumed = false;
        while (cmd.hasNext() && !commandConsumed) {
            if (oo.subCommand == null) {
                cmd.matcher()
                        .when("scan").asRaw(c -> {
                            c.next();
                            matchScanOptions(c, oo);
                        })
                        .when("bump").asRaw(c -> {
                            c.next();
                            matchBumpOptions(c, oo);
                        })
                        .when("update", "set").asRaw(c -> {
                            c.next();
                            matchUpdateOptions(c, oo);
                        })
                        .when("release", "fix-snapshots").asRaw(c -> {
                            c.next();
                            matchReleaseOptions(c, oo);
                        })
                        .when("check", "validate").asRaw(c -> {
                            c.next();
                            matchCheckOptions(c, oo);
                        })
                        .when("compare").asRaw(c -> {
                            c.next();
                            matchCompareOptions(c, oo);
                        })
                        .withDefaults()
                        .require();
            }
        }
        if (oo.subCommand == null) {
            NOut.println(NMsg.ofP("Usage: nmvn version <scan|bump|update|release|check> [options]"));
            return 1;
        }

        NPath workingDir = NPath.ofUserDirectory();
        NPath cfgFile = NMvnConfigLoader.resolveConfigFile(oo.configPath, workingDir);
        NMvnConfig config = NMvnConfigLoader.load(cfgFile);

        // Apply CLI overrides
        if (oo.noIgnore) {
            config.setUseDefaultIgnores(false);
        }
        if (!oo.roots.isEmpty()) {
            config.setRoots(oo.roots);
        }
        if (!oo.excludes.isEmpty()) {
            config.getExcludes().addAll(oo.excludes);
        }

        boolean effectiveApply = !NSession.of().isDry();

        try {
            switch (oo.subCommand) {
                case "scan":
                    return doScan(config, workingDir);
                case "bump":
                    return doBump(config, workingDir, oo.cliInstructions, oo.allArtifacts, oo.increment,
                            oo.cascadePolicy, NSession.of().isForce(), effectiveApply);
                case "update":
                    return doUpdate(config, workingDir, oo.explicitUpdates, oo.cascadePolicy, effectiveApply);
                case "release":
                    return doRelease(config, workingDir, oo.explicitReleases, oo.strict, effectiveApply);
                case "check":
                    return doCheck(config, workingDir, oo.failOnWarning);
                case "compare":
                    return doCompare(config, workingDir);
                default:
                    NOut.println(NMsg.ofC("Unknown sub-command: %s", oo.subCommand));
                    return 1;
            }
        } catch (Exception e) {
            NOut.println(NMsg.ofStyled("Error: " + e.getMessage(), NTextStyle.danger()));
            return 1;
        }
    }

    private void matchScanOptions(NCmdLine cmd, SharedOptions oo) {
        oo.subCommand = "scan";
        cmd.matcher()
                .when("-w", "--workset", "--config").asEntry(a -> oo.configPath = a.stringValue())
                .when("--strict").asFlag(a -> oo.strict = a.booleanValue())
                .when("--fail-on-warning").asFlag(a -> oo.failOnWarning = a.booleanValue())
                .when("--no-ignore").asFlag(a -> oo.noIgnore = a.booleanValue())
                .when("-c", "--cascade", "--cascade-policy").asEntry(a -> oo.cascadePolicy = BumpPolicy.CascadePolicy.parse(a.stringValue()))
                .when("--cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy = BumpPolicy.CascadePolicy.CASCADE_VERSIONS;
                    }
                })
                .when("--cascade-references-only", "--no-cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy = BumpPolicy.CascadePolicy.CASCADE_REFERENCES_ONLY;
                    }
                })
                .when("--no-cascade").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy = BumpPolicy.CascadePolicy.NONE;
                    }
                })
                .when("-r", "--root").asEntry(a -> oo.roots.add(a.stringValue()))
                .when("--exclude").asEntry(a -> oo.excludes.add(a.stringValue()))
                .when("-a", "--artifact").asEntry(a -> handleArtifactArg(oo.subCommand, a.stringValue(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
                .whenNonOption().asArg(a -> handleArtifactArg(oo.subCommand, a.asString().get(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
                .withDefaults()
                .requireAll();
    }

    private void matchBumpOptions(NCmdLine cmd, SharedOptions oo) {
        oo.subCommand = "bump";
        cmd.matcher()
                .when("-w", "--workset", "--config").asEntry(a -> oo.configPath = a.stringValue())
                .when("-p", "--patch").asFlag(a -> oo.increment = BumpPolicy.IncrementType.PATCH)
                .when("-m", "--minor").asFlag(a -> oo.increment = BumpPolicy.IncrementType.MINOR)
                .when("-M", "--major").asFlag(a -> oo.increment = BumpPolicy.IncrementType.MAJOR)
                .when("--all").asFlag(a -> oo.allArtifacts = a.booleanValue())
                .when("--strict").asFlag(a -> oo.strict = a.booleanValue())
                .when("--fail-on-warning").asFlag(a -> oo.failOnWarning = a.booleanValue())
                .when("--no-ignore").asFlag(a -> oo.noIgnore = a.booleanValue())
                .when("-c", "--cascade", "--cascade-policy").asEntry(a -> oo.cascadePolicy = BumpPolicy.CascadePolicy.parse(a.stringValue()))
                .when("--cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy = BumpPolicy.CascadePolicy.CASCADE_VERSIONS;
                    }
                })
                .when("--cascade-references-only", "--no-cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy = BumpPolicy.CascadePolicy.CASCADE_REFERENCES_ONLY;
                    }
                })
                .when("--no-cascade").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy = BumpPolicy.CascadePolicy.NONE;
                    }
                })
                .when("-r", "--root").asEntry(a -> oo.roots.add(a.stringValue()))
                .when("--exclude").asEntry(a -> oo.excludes.add(a.stringValue()))
                .when("-a", "--artifact").asEntry(a -> handleArtifactArg(oo.subCommand, a.stringValue(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
                .whenNonOption().asArg(a -> handleArtifactArg(oo.subCommand, a.asString().get(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
                .withDefaults()
                .requireAll();
    }

    private void handleArtifactArg(String subCommand, String val, List<BumpInstruction> cliInstructions,
                                   Map<NId, String> explicitUpdates, Map<NId, String> explicitReleases) {
        if ("bump".equals(subCommand)) {
            cliInstructions.add(BumpInstruction.parse(val));
        } else if ("update".equals(subCommand)) {
            if (val.contains("=")) {
                int eq = val.indexOf('=');
                NId ga = MavenCoord.parse(val.substring(0, eq).trim()).shortId();
                explicitUpdates.put(ga, val.substring(eq + 1).trim());
            } else {
                NId id = MavenCoord.parse(val);
                if (id.version() == null || id.version().value().isEmpty()) {
                    throw new IllegalArgumentException("Version must be specified for update: " + val);
                }
                explicitUpdates.put(id.shortId(), id.version().value());
            }
        } else if ("release".equals(subCommand)) {
            if (val.contains("=")) {
                int eq = val.indexOf('=');
                NId ga = MavenCoord.parse(val.substring(0, eq).trim()).shortId();
                explicitReleases.put(ga, val.substring(eq + 1).trim());
            } else {
                NId id = MavenCoord.parse(val);
                if (id.version() != null && !id.version().value().isEmpty()) {
                    explicitReleases.put(id.shortId(), id.version().value());
                } else {
                    explicitReleases.put(id.shortId(), null);
                }
            }
        }
    }

    private void matchUpdateOptions(NCmdLine cmd, SharedOptions oo) {
        oo.subCommand = "update";
        cmd.matcher()
                .when("-w", "--workset", "--config").asEntry(a -> oo.configPath = a.stringValue())
                .when("--strict").asFlag(a -> oo.strict = a.booleanValue())
                .when("--fail-on-warning").asFlag(a -> oo.failOnWarning = a.booleanValue())
                .when("--no-ignore").asFlag(a -> oo.noIgnore = a.booleanValue())
                .when("-p", "--patch").asFlag(a -> oo.increment = BumpPolicy.IncrementType.PATCH)
                .when("-m", "--minor").asFlag(a -> oo.increment = BumpPolicy.IncrementType.MINOR)
                .when("-M", "--major").asFlag(a -> oo.increment = BumpPolicy.IncrementType.MAJOR)
                .when("-c", "--cascade", "--cascade-policy").asEntry(a -> oo.cascadePolicy = BumpPolicy.CascadePolicy.parse(a.stringValue()))
                .when("--cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy = BumpPolicy.CascadePolicy.CASCADE_VERSIONS;
                    }
                })
                .when("--cascade-references-only", "--no-cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy = BumpPolicy.CascadePolicy.CASCADE_REFERENCES_ONLY;
                    }
                })
                .when("--no-cascade").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy = BumpPolicy.CascadePolicy.NONE;
                    }
                })
                .when("-r", "--root").asEntry(a -> oo.roots.add(a.stringValue()))
                .when("--exclude").asEntry(a -> oo.excludes.add(a.stringValue()))
                .when("-a", "--artifact").asEntry(a -> handleArtifactArg(oo.subCommand, a.stringValue(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
                .whenNonOption().asArg(a -> handleArtifactArg(oo.subCommand, a.asString().get(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
                .withDefaults()
                .requireAll();
    }

    private int doScan(NMvnConfig config, NPath workingDir)  {
        ScanResult scan = versionService.scan(config, workingDir);
        Map<NId, PomArtifact> artifacts = scan.getArtifacts();
        MavenDependencyGraph graph = scan.getGraph();

        if (!NOut.isPlain()) {
            NObjectElementBuilder root = NElement.ofObjectBuilder();
            NArrayElementBuilder warnings = NElement.ofArrayBuilder();
            for (String warning : scan.getWarnings()) {
                warnings.add(NElement.ofString(warning));
            }
            root.set("warnings", warnings.build());
            NArrayElementBuilder arr = NElement.ofArrayBuilder();
            for (PomArtifact a : artifacts.values()) {
                NObjectElementBuilder obj = NElement.ofObjectBuilder();
                obj.set("groupId", NElement.ofString(a.getGroupId()));
                obj.set("artifactId", NElement.ofString(a.getArtifactId()));
                obj.set("version", NElement.ofString(a.getResolvedVersion()));
                obj.set("path", NElement.ofString(a.getPath().toString()));

                NArrayElementBuilder depsArr = NElement.ofArrayBuilder();
                for (PomDependency dep : a.getAllReferences()) {
                    NObjectElementBuilder dObj = NElement.ofObjectBuilder();
                    dObj.set("groupId", NElement.ofString(dep.getGroupId()));
                    dObj.set("artifactId", NElement.ofString(dep.getArtifactId()));
                    dObj.set("version", NElement.ofString(dep.getResolvedVersion() != null ? dep.getResolvedVersion() : dep.getRawVersion()));
                    dObj.set("edgeType", NElement.ofString(dep.getEdgeType().name()));
                    depsArr.add(dObj.build());
                }
                obj.set("references", depsArr.build());
                arr.add(obj.build());
            }
            root.set("artifacts", arr.build());
            NOut.println(NElementWriter.ofJson().formatPlain(root.build()));
            return 0;
        }

        printScanWarnings(scan);
        NOut.println(NMsg.ofStyled(String.format("Discovered %d Maven artifact(s):", artifacts.size()), NTextStyle.primary4()));
        for (PomArtifact a : artifacts.values()) {
            NOut.println(NMsg.ofC("  [bold %s:%s] -> %s", a.getGroupId(), a.getArtifactId(), a.getResolvedVersion()));
            NOut.println(NMsg.ofC("    Path: %s", a.getPath()));

            List<DependencyEdge> outgoing = graph.getOutgoingEdges(a.toGa());
            if (!outgoing.isEmpty()) {
                NOut.println("    References:");
                for (DependencyEdge edge : outgoing) {
                    boolean internal = artifacts.containsKey(edge.getTarget());
                    String targetLabel = edge.getTarget().shortName() + " [" + edge.getEdgeType() + "]" + (internal ? " (internal)" : " (external)");
                    NOut.println(NMsg.ofC("      -> %s", targetLabel));
                }
            }

            Set<NId> dependents = graph.getDirectDependents(a.toGa());
            if (!dependents.isEmpty()) {
                NOut.println("    Dependents (referenced by):");
                for (NId d : dependents) {
                    NOut.println(NMsg.ofC("      <- %s", d.shortName()));
                }
            }
        }
        return 0;
    }

    private int doBump(NMvnConfig config, NPath workingDir, List<BumpInstruction> explicitBumps,
                       boolean allArtifacts,
                       BumpPolicy.IncrementType increment,
                       BumpPolicy.CascadePolicy cascadePolicy, boolean force, boolean apply)  {
        if (!allArtifacts && (explicitBumps == null || explicitBumps.isEmpty())
                && (config.getInstructions() == null || config.getInstructions().isEmpty())) {
            NOut.println(NMsg.ofStyled(
                    "No artifacts selected. Specify an artifact or use --all to bump the whole workset.",
                    NTextStyle.danger()));
            return 1;
        }
        List<BumpInstruction> targets = explicitBumps;
        if (allArtifacts) {
            Map<NId, BumpInstruction> all = new LinkedHashMap<>();
            for (NId ga : versionService.scan(config, workingDir).getArtifacts().keySet()) {
                all.put(ga, new BumpInstruction(ga.groupId(), ga.artifactId(), null));
            }
            if (config.getInstructions() != null) {
                for (BumpInstruction instruction : config.getInstructions()) {
                    all.put(instruction.toGa(), instruction);
                }
            }
            if (explicitBumps != null) {
                for (BumpInstruction instruction : explicitBumps) {
                    all.put(instruction.toGa(), instruction);
                }
            }
            targets = new ArrayList<>(all.values());
        }
        BumpPolicy.CascadePolicy effectiveCascade = cascadePolicy != null
                ? cascadePolicy : config.getBumpPolicy().getCascadePolicy();
        printCascadeWarning(effectiveCascade);
        BumpResult result = versionService.bump(config, workingDir, targets, increment, effectiveCascade, force, apply);
        renderChanges(result.getChanges(), apply, effectiveCascade);
        return 0;
    }

    private int doUpdate(NMvnConfig config, NPath workingDir, Map<NId, String> explicitUpdates,
                         BumpPolicy.CascadePolicy cascadePolicy,
                         boolean apply)  {
        BumpPolicy.CascadePolicy effectiveCascade = cascadePolicy != null
                ? cascadePolicy : config.getBumpPolicy().getCascadePolicy();
        if (explicitUpdates != null && !explicitUpdates.isEmpty()) {
            printCascadeWarning(effectiveCascade);
        }
        BumpResult result = versionService.update(config, workingDir, explicitUpdates, effectiveCascade, apply);
        renderChanges(result.getChanges(), apply, effectiveCascade);
        return 0;
    }

    private int doRelease(NMvnConfig config, NPath workingDir, Map<NId, String> explicitReleases,
                          boolean strict, boolean apply)  {
        ReleaseResult result = versionService.release(config, workingDir, explicitReleases, strict, apply);
        renderChanges(result.getChanges(), apply);
        return 0;
    }

    private void renderChanges(List<PomChange> changes, boolean apply) {
        renderChanges(changes, apply, null);
    }

    private void renderChanges(List<PomChange> changes, boolean apply, BumpPolicy.CascadePolicy cascadePolicy) {
        if (!NOut.isPlain()) {
            NArrayElementBuilder arr = NElement.ofArrayBuilder();
            for (PomChange c : changes) {
                NObjectElementBuilder obj = NElement.ofObjectBuilder();
                obj.set("pomFile", NElement.ofString(c.getPomFile().toString()));
                NArrayElementBuilder diffArr = NElement.ofArrayBuilder();
                for (String line : c.getDiffLines()) {
                    diffArr.add(NElement.ofString(line));
                }
                obj.set("diff", diffArr.build());
                arr.add(obj.build());
            }
            if (cascadePolicy == null) {
                NOut.println(NElementWriter.ofJson().formatPlain(arr.build()));
            } else {
                NObjectElementBuilder root = NElement.ofObjectBuilder();
                root.set("cascadePolicy", NElement.ofString(cascadePolicy.name().toLowerCase().replace("_", "-")));
                root.set("changes", arr.build());
                NOut.println(NElementWriter.ofJson().formatPlain(root.build()));
            }
            return;
        }

        if (changes.isEmpty()) {
            NOut.println(NMsg.ofStyled("No changes required. All POMs are up-to-date.", NTextStyle.info()));
            return;
        }

        if (apply) {
            NOut.println(NMsg.ofStyled(String.format("Applied changes to %d POM file(s):", changes.size()), NTextStyle.success()));
        } else {
            NOut.println(NMsg.ofStyled(String.format("DRY RUN: %d POM file(s) would be modified:", changes.size()), NTextStyle.warn()));
        }

        for (PomChange change : changes) {
            NOut.println();
            NOut.println(NMsg.ofStyled("File: " + change.getPomFile(), NTextStyle.underlined()));
            for (String line : change.getDiffLines()) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    NOut.println(NMsg.ofStyled(line, NTextStyle.success()));
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    NOut.println(NMsg.ofStyled(line, NTextStyle.danger()));
                } else if (line.startsWith("@@")) {
                    NOut.println(NMsg.ofStyled(line, NTextStyle.info()));
                } else {
                    NOut.println(NMsg.ofP(line));
                }
            }
        }
    }

    private void matchReleaseOptions(NCmdLine cmd, SharedOptions oo) {
        oo.subCommand = "release";
        cmd.matcher()
                .when("-w", "--workset", "--config").asEntry(a -> oo.configPath = a.stringValue())
                .when("--strict").asFlag(a -> oo.strict = a.booleanValue())
                .when("--fail-on-warning").asFlag(a -> oo.failOnWarning = a.booleanValue())
                .when("--no-ignore").asFlag(a -> oo.noIgnore = a.booleanValue())
                .when("-p", "--patch").asFlag(a -> oo.increment = BumpPolicy.IncrementType.PATCH)
                .when("-m", "--minor").asFlag(a -> oo.increment = BumpPolicy.IncrementType.MINOR)
                .when("-M", "--major").asFlag(a -> oo.increment = BumpPolicy.IncrementType.MAJOR)
                .when("-c", "--cascade", "--cascade-policy").asEntry(a -> oo.cascadePolicy = BumpPolicy.CascadePolicy.parse(a.stringValue()))
                .when("--cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy = BumpPolicy.CascadePolicy.CASCADE_VERSIONS;
                    }
                })
                .when("--cascade-references-only", "--no-cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy = BumpPolicy.CascadePolicy.CASCADE_REFERENCES_ONLY;
                    }
                })
                .when("--no-cascade").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy = BumpPolicy.CascadePolicy.NONE;
                    }
                })
                .when("-r", "--root").asEntry(a -> oo.roots.add(a.stringValue()))
                .when("--exclude").asEntry(a -> oo.excludes.add(a.stringValue()))
                .when("-a", "--artifact").asEntry(a -> handleArtifactArg(oo.subCommand, a.stringValue(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
                .whenNonOption().asArg(a -> handleArtifactArg(oo.subCommand, a.asString().get(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
                .withDefaults()
                .requireAll();
    }

    private void matchCheckOptions(NCmdLine cmd, SharedOptions oo) {
        oo.subCommand = "check";
        cmd.matcher()
                .when("-w", "--workset", "--config").asEntry(a -> oo.configPath = a.stringValue())
                .when("--strict").asFlag(a -> oo.strict = a.booleanValue())
                .when("--fail-on-warning").asFlag(a -> oo.failOnWarning = a.booleanValue())
                .when("--no-ignore").asFlag(a -> oo.noIgnore = a.booleanValue())
                .when("-p", "--patch").asFlag(a -> oo.increment = BumpPolicy.IncrementType.PATCH)
                .when("-m", "--minor").asFlag(a -> oo.increment = BumpPolicy.IncrementType.MINOR)
                .when("-M", "--major").asFlag(a -> oo.increment = BumpPolicy.IncrementType.MAJOR)
                .when("-c", "--cascade", "--cascade-policy").asEntry(a -> oo.cascadePolicy = BumpPolicy.CascadePolicy.parse(a.stringValue()))
                .when("--cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy = BumpPolicy.CascadePolicy.CASCADE_VERSIONS;
                    }
                })
                .when("--cascade-references-only", "--no-cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy = BumpPolicy.CascadePolicy.CASCADE_REFERENCES_ONLY;
                    }
                })
                .when("--no-cascade").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy = BumpPolicy.CascadePolicy.NONE;
                    }
                })
                .when("-r", "--root").asEntry(a -> oo.roots.add(a.stringValue()))
                .when("--exclude").asEntry(a -> oo.excludes.add(a.stringValue()))
                .when("-a", "--artifact").asEntry(a -> handleArtifactArg(oo.subCommand, a.stringValue(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
                .whenNonOption().asArg(a -> handleArtifactArg(oo.subCommand, a.asString().get(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
                .withDefaults()
                .requireAll();
    }

    private void matchCompareOptions(NCmdLine cmd, SharedOptions oo) {
        oo.subCommand = "compare";
        cmd.matcher()
                .withDefaults()
                .requireAll();
    }

    private int doCompare(NMvnConfig config, NPath workingDir)  {
        // This is a placeholder - the actual compare logic is in MvnJarCompareCli
        // For the version command, compare might not be implemented or might delegate elsewhere
        NOut.println(NMsg.ofP("Compare functionality not implemented in version command"));
        return 1;
    }


    private int doCheck(NMvnConfig config, NPath workingDir, boolean failOnWarning)  {
        ScanResult scan = versionService.scan(config, workingDir, false);
        DiagnosticReport report = versionService.check(scan, config);

        if (!NOut.isPlain()) {
            NObjectElementBuilder rootBuilder = NElement.ofObjectBuilder();
            rootBuilder.set("hasErrors", report.hasErrors());
            rootBuilder.set("hasWarnings", report.hasWarnings());
            rootBuilder.set("issueCount", report.size());
            NArrayElementBuilder scanWarnings = NElement.ofArrayBuilder();
            for (String warning : scan.getWarnings()) {
                scanWarnings.add(NElement.ofString(warning));
            }
            rootBuilder.set("scanWarnings", scanWarnings.build());
            NArrayElementBuilder issuesArr = NElement.ofArrayBuilder();
            for (DiagnosticIssue issue : report.getIssues()) {
                NObjectElementBuilder ib = NElement.ofObjectBuilder();
                ib.set("rule", issue.getRule().name());
                ib.set("severity", issue.getSeverity().name());
                if (issue.getTargetId() != null) {
                    ib.set("targetArtifact", issue.getTargetId().shortName());
                }
                if (issue.getSourcePom() != null) {
                    ib.set("sourcePom", issue.getSourcePom().toString());
                }
                ib.set("message", issue.getMessage());
                if (!issue.getDetails().isEmpty()) {
                    NObjectElementBuilder db = NElement.ofObjectBuilder();
                    for (Map.Entry<String, String> entry : issue.getDetails().entrySet()) {
                        db.set(entry.getKey(), entry.getValue());
                    }
                    ib.set("details", db.build());
                }
                issuesArr.add(ib.build());
            }
            rootBuilder.set("issues", issuesArr.build());
            NOut.println(NElementWriter.ofJson().formatPlain(rootBuilder.build()));
            if (report.hasErrors() || !scan.getWarnings().isEmpty()) {
                return 1;
            }
            if (failOnWarning && report.hasWarnings()) {
                return 1;
            }
            return 0;
        } else {
            if (report.isEmpty() && scan.getWarnings().isEmpty()) {
                NOut.println(NMsg.ofStyled("✓ All checks passed! No version discrepancies detected.", NTextStyle.success()));
            } else {
                for (DiagnosticIssue issue : report.getIssues()) {
                    NTextStyle badgeStyle = issue.getSeverity() == DiagnosticSeverity.ERROR ? NTextStyle.danger()
                            : issue.getSeverity() == DiagnosticSeverity.WARNING ? NTextStyle.warn()
                              : NTextStyle.info();
                    String badge = "[" + issue.getSeverity().name() + "]";
                    NOut.println(NMsg.ofC("%s %s: %s",
                            NMsg.ofStyled(badge, badgeStyle),
                            NMsg.ofStyled(issue.getRule().name(), NTextStyle.bold()),
                            issue.getMessage()
                    ));
                    if (issue.getSourcePom() != null) {
                        NOut.println(NMsg.ofC("    POM: %s", issue.getSourcePom()));
                    }
                    if (!issue.getDetails().isEmpty()) {
                        for (Map.Entry<String, String> e : issue.getDetails().entrySet()) {
                            NOut.println(NMsg.ofC("    - %s: %s", e.getKey(), e.getValue()));
                        }
                    }
                }
                NOut.println();
                String summary = String.format("Found %d issue(s) (%d error(s), %d warning(s)).",
                        report.size(), report.getErrors().size(), report.getWarnings().size());
                if (report.hasErrors()) {
                    NOut.println(NMsg.ofStyled(summary, NTextStyle.danger()));
                } else {
                    NOut.println(NMsg.ofStyled(summary, NTextStyle.warn()));
                }
            }
            if (report.hasErrors() || !scan.getWarnings().isEmpty()) {
                return 1;
            }
            if (failOnWarning && report.hasWarnings()) {
                return 1;
            }
            return 0;
        }
    }

    private void printScanWarnings(ScanResult scan) {
        for (String warning : scan.getWarnings()) {
            NOut.println(NMsg.ofStyled("Scan warning: " + warning, NTextStyle.warn()));
        }
    }

    private void printCascadeWarning(BumpPolicy.CascadePolicy cascadePolicy) {
        if (!NOut.isPlain()) {
            return;
        }
        String message = cascadePolicy == BumpPolicy.CascadePolicy.CASCADE_VERSIONS
                ? "Cascade policy: cascade-versions; dependent project versions may also be bumped."
                : cascadePolicy == BumpPolicy.CascadePolicy.NONE
                ? "Cascade policy: none; dependent POM references will not be updated."
                : "Cascade policy: cascade-references-only; dependent project versions will not be bumped.";
        NOut.println(NMsg.ofStyled(message, cascadePolicy == BumpPolicy.CascadePolicy.CASCADE_VERSIONS
                ? NTextStyle.warn() : NTextStyle.info()));
    }
}