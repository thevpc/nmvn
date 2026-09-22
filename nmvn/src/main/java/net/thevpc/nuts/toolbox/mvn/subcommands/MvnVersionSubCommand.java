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
import net.thevpc.nuts.core.NSession;
import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.io.NOut;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.util.NRef;

import java.util.*;

public class MvnVersionSubCommand {

    private final VersionService versionService = new VersionService();

    public MvnVersionSubCommand() {
    }

    private static class SharedOptions {
        NRef<String> subCommand = NRef.ofNull();
        NRef<String> configPath = NRef.ofNull();
        NRef<Boolean> strict = NRef.of(false);
        NRef<Boolean> failOnWarning = NRef.of(false);
        NRef<BumpPolicy.CascadePolicy> cascadePolicy = NRef.ofNull();
        NRef<BumpPolicy.IncrementType> increment = NRef.ofNull();
        NRef<Boolean> force = NRef.of(false);
        List<String> roots = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        List<BumpInstruction> cliInstructions = new ArrayList<>();
        Map<NId, String> explicitUpdates = new LinkedHashMap<>();
        Map<NId, String> explicitReleases = new LinkedHashMap<>();

    }

    public int run(String[] args) {
        SharedOptions oo = new SharedOptions();
        NCmdLine cmd = NCmdLine.of(args);
        boolean commandConsumed = false;
        while (cmd.hasNext() && !commandConsumed) {
            if (oo.subCommand.isNull()) {
                if (!cmd.matcher()
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
                        .anyMatch()) {
                    if (NSession.of().configureFirst(cmd)) {
                        // handled by nuts
                    } else {
                        cmd.throwUnexpectedArgument();
                    }
                }
            }
        }
        if (oo.subCommand.isNull()) {
            NOut.println(NMsg.ofP("Usage: nmvn version <scan|bump|update|release|check> [options]"));
            return 1;
        }

        // Extract canonical values from NRefs after parsing is complete
        String configPathVal = oo.configPath.get();
        Boolean strictVal = oo.strict.get();
        Boolean failOnWarningVal = oo.failOnWarning.get();
        BumpPolicy.CascadePolicy cascadePolicyVal = oo.cascadePolicy.get();
        BumpPolicy.IncrementType incrementVal = oo.increment.get();
        Boolean forceVal = oo.force.get();
        List<BumpInstruction> cliInstructionsVal = oo.cliInstructions; // Already a List, not an NRef
        Map<NId, String> explicitUpdatesVal = oo.explicitUpdates; // Already a Map, not an NRef
        Map<NId, String> explicitReleasesVal = oo.explicitReleases; // Already a Map, not an NRef

        NPath workingDir = NPath.ofUserDirectory();
        NPath cfgFile = NMvnConfigLoader.resolveConfigFile(configPathVal, workingDir);
        NMvnConfig config = NMvnConfigLoader.load(cfgFile);

        // Apply CLI overrides
        if (!oo.roots.isEmpty()) {
            config.setRoots(oo.roots);
        }
        if (!oo.excludes.isEmpty()) {
            config.getExcludes().addAll(oo.excludes);
        }

        boolean effectiveApply = !NSession.of().isDry();

        try {
            switch (oo.subCommand.get()) {
                case "scan":
                    return doScan(config, workingDir);
                case "bump":
                    return doBump(config, workingDir, cliInstructionsVal, incrementVal, cascadePolicyVal, forceVal, effectiveApply);
                case "update":
                    return doUpdate(config, workingDir, explicitUpdatesVal, cascadePolicyVal, effectiveApply);
                case "release":
                    return doRelease(config, workingDir, explicitReleasesVal, strictVal, effectiveApply);
                case "check":
                    return doCheck(config, workingDir, failOnWarningVal);
                case "compare":
                    return doCompare(config, workingDir);
                default:
                    NOut.println(NMsg.ofC("Unknown sub-command: %s", oo.subCommand.get()));
                    return 1;
            }
        } catch (Exception e) {
            NOut.println(NMsg.ofStyled("Error: " + e.getMessage(), NTextStyle.danger()));
            return 1;
        }
    }

    private void matchScanOptions(NCmdLine cmd, SharedOptions oo) {
        oo.subCommand.set("scan");
        cmd.matcher()
                .when("--workset").asEntry(a -> oo.configPath.set(a.stringValue()))
                .when("--strict").asFlag(a -> oo.strict.set(a.booleanValue()))
                .when("--fail-on-warning").asFlag(a -> oo.failOnWarning.set(a.booleanValue()))
                .when("--patch").asFlag(a -> oo.increment.set(BumpPolicy.IncrementType.PATCH))
                .when("--minor").asFlag(a -> oo.increment.set(BumpPolicy.IncrementType.MINOR))
                .when("--major").asFlag(a -> oo.increment.set(BumpPolicy.IncrementType.MAJOR))
                .when("-f", "--force").asFlag(a -> oo.force.set(a.booleanValue()))
                .when("-c", "--cascade", "--cascade-policy").asEntry(a -> oo.cascadePolicy.set(BumpPolicy.CascadePolicy.parse(a.stringValue())))
                .when("--cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy.set(BumpPolicy.CascadePolicy.CASCADE_VERSIONS);
                    }
                })
                .when("--cascade-references-only", "--no-cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy.set(BumpPolicy.CascadePolicy.CASCADE_REFERENCES_ONLY);
                    }
                })
                .when("--no-cascade").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy.set(BumpPolicy.CascadePolicy.NONE);
                    }
                })
                .when("--root").asEntry(a -> oo.roots.add(a.stringValue()))
                .when("--exclude").asEntry(a -> oo.excludes.add(a.stringValue()))
                .when("-a", "--artifact").asEntry(a -> handleArtifactArg(oo.subCommand.get(), a.stringValue(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
                .whenNonOption().asArg(a -> handleArtifactArg(oo.subCommand.get(), a.asString().get(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
                .withDefaults()
                .requireAll();
    }

    private void matchBumpOptions(NCmdLine cmd, SharedOptions oo) {
        oo.subCommand.set("bump");
        cmd.matcher()
                .when("--workset").asEntry(a -> oo.configPath.set(a.stringValue()))
                .when("--strict").asFlag(a -> oo.strict.set(a.booleanValue()))
                .when("--fail-on-warning").asFlag(a -> oo.failOnWarning.set(a.booleanValue()))
                .when("--patch").asFlag(a -> oo.increment.set(BumpPolicy.IncrementType.PATCH))
                .when("--minor").asFlag(a -> oo.increment.set(BumpPolicy.IncrementType.MINOR))
                .when("--major").asFlag(a -> oo.increment.set(BumpPolicy.IncrementType.MAJOR))
                .when("-f", "--force").asFlag(a -> oo.force.set(a.booleanValue()))
                .when("-c", "--cascade", "--cascade-policy").asEntry(a -> oo.cascadePolicy.set(BumpPolicy.CascadePolicy.parse(a.stringValue())))
                .when("--cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy.set(BumpPolicy.CascadePolicy.CASCADE_VERSIONS);
                    }
                })
                .when("--cascade-references-only", "--no-cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy.set(BumpPolicy.CascadePolicy.CASCADE_REFERENCES_ONLY);
                    }
                })
                .when("--no-cascade").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy.set(BumpPolicy.CascadePolicy.NONE);
                    }
                })
                .when("--root").asEntry(a -> oo.roots.add(a.stringValue()))
                .when("--exclude").asEntry(a -> oo.excludes.add(a.stringValue()))
                .when("-a", "--artifact").asEntry(a -> handleArtifactArg(oo.subCommand.get(), a.stringValue(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
                .whenNonOption().asArg(a -> handleArtifactArg(oo.subCommand.get(), a.asString().get(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
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
        oo.subCommand.set("update");
        cmd.matcher()
                .when("--workset").asEntry(a -> oo.configPath.set(a.stringValue()))
                .when("--strict").asFlag(a -> oo.strict.set(a.booleanValue()))
                .when("--fail-on-warning").asFlag(a -> oo.failOnWarning.set(a.booleanValue()))
                .when("--patch").asFlag(a -> oo.increment.set(BumpPolicy.IncrementType.PATCH))
                .when("--minor").asFlag(a -> oo.increment.set(BumpPolicy.IncrementType.MINOR))
                .when("--major").asFlag(a -> oo.increment.set(BumpPolicy.IncrementType.MAJOR))
                .when("-f", "--force").asFlag(a -> oo.force.set(a.booleanValue()))
                .when("-c", "--cascade", "--cascade-policy").asEntry(a -> oo.cascadePolicy.set(BumpPolicy.CascadePolicy.parse(a.stringValue())))
                .when("--cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy.set(BumpPolicy.CascadePolicy.CASCADE_VERSIONS);
                    }
                })
                .when("--cascade-references-only", "--no-cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy.set(BumpPolicy.CascadePolicy.CASCADE_REFERENCES_ONLY);
                    }
                })
                .when("--no-cascade").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy.set(BumpPolicy.CascadePolicy.NONE);
                    }
                })
                .when("--root").asEntry(a -> oo.roots.add(a.stringValue()))
                .when("--exclude").asEntry(a -> oo.excludes.add(a.stringValue()))
                .when("-a", "--artifact").asEntry(a -> handleArtifactArg(oo.subCommand.get(), a.stringValue(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
                .whenNonOption().asArg(a -> handleArtifactArg(oo.subCommand.get(), a.asString().get(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
                .withDefaults()
                .requireAll();
    }

    private int doScan(NMvnConfig config, NPath workingDir)  {
        ScanResult scan = versionService.scan(config, workingDir);
        Map<NId, PomArtifact> artifacts = scan.getArtifacts();
        MavenDependencyGraph graph = scan.getGraph();

        if (!NOut.isPlain()) {
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
            NOut.println(NElementWriter.ofJson().formatPlain(arr.build()));
            return 0;
        }

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
                       BumpPolicy.IncrementType increment,
                       BumpPolicy.CascadePolicy cascadePolicy, boolean force, boolean apply)  {
        BumpResult result = versionService.bump(config, workingDir, explicitBumps, increment, cascadePolicy, force, apply);
        renderChanges(result.getChanges(), apply);
        return 0;
    }

    private int doUpdate(NMvnConfig config, NPath workingDir, Map<NId, String> explicitUpdates,
                         BumpPolicy.CascadePolicy cascadePolicy,
                         boolean apply)  {
        BumpResult result = versionService.update(config, workingDir, explicitUpdates, cascadePolicy, apply);
        renderChanges(result.getChanges(), apply);
        return 0;
    }

    private int doRelease(NMvnConfig config, NPath workingDir, Map<NId, String> explicitReleases,
                          boolean strict, boolean apply)  {
        ReleaseResult result = versionService.release(config, workingDir, explicitReleases, strict, apply);
        renderChanges(result.getChanges(), apply);
        return 0;
    }

    private void renderChanges(List<PomChange> changes, boolean apply) {
        if (changes.isEmpty()) {
            NOut.println(NMsg.ofStyled("No changes required. All POMs are up-to-date.", NTextStyle.info()));
            return;
        }

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
            NOut.println(NElementWriter.ofJson().formatPlain(arr.build()));
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
        oo.subCommand.set("release");
        cmd.matcher()
                .when("--workset").asEntry(a -> oo.configPath.set(a.stringValue()))
                .when("--strict").asFlag(a -> oo.strict.set(a.booleanValue()))
                .when("--fail-on-warning").asFlag(a -> oo.failOnWarning.set(a.booleanValue()))
                .when("--patch").asFlag(a -> oo.increment.set(BumpPolicy.IncrementType.PATCH))
                .when("--minor").asFlag(a -> oo.increment.set(BumpPolicy.IncrementType.MINOR))
                .when("--major").asFlag(a -> oo.increment.set(BumpPolicy.IncrementType.MAJOR))
                .when("-f", "--force").asFlag(a -> oo.force.set(a.booleanValue()))
                .when("-c", "--cascade", "--cascade-policy").asEntry(a -> oo.cascadePolicy.set(BumpPolicy.CascadePolicy.parse(a.stringValue())))
                .when("--cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy.set(BumpPolicy.CascadePolicy.CASCADE_VERSIONS);
                    }
                })
                .when("--cascade-references-only", "--no-cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy.set(BumpPolicy.CascadePolicy.CASCADE_REFERENCES_ONLY);
                    }
                })
                .when("--no-cascade").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy.set(BumpPolicy.CascadePolicy.NONE);
                    }
                })
                .when("--root").asEntry(a -> oo.roots.add(a.stringValue()))
                .when("--exclude").asEntry(a -> oo.excludes.add(a.stringValue()))
                .when("-a", "--artifact").asEntry(a -> handleArtifactArg(oo.subCommand.get(), a.stringValue(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
                .whenNonOption().asArg(a -> handleArtifactArg(oo.subCommand.get(), a.asString().get(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
                .withDefaults()
                .requireAll();
    }

    private void matchCheckOptions(NCmdLine cmd, SharedOptions oo) {
        oo.subCommand.set("check");
        cmd.matcher()
                .when("--workset").asEntry(a -> oo.configPath.set(a.stringValue()))
                .when("--strict").asFlag(a -> oo.strict.set(a.booleanValue()))
                .when("--fail-on-warning").asFlag(a -> oo.failOnWarning.set(a.booleanValue()))
                .when("--patch").asFlag(a -> oo.increment.set(BumpPolicy.IncrementType.PATCH))
                .when("--minor").asFlag(a -> oo.increment.set(BumpPolicy.IncrementType.MINOR))
                .when("--major").asFlag(a -> oo.increment.set(BumpPolicy.IncrementType.MAJOR))
                .when("-f", "--force").asFlag(a -> oo.force.set(a.booleanValue()))
                .when("-c", "--cascade", "--cascade-policy").asEntry(a -> oo.cascadePolicy.set(BumpPolicy.CascadePolicy.parse(a.stringValue())))
                .when("--cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy.set(BumpPolicy.CascadePolicy.CASCADE_VERSIONS);
                    }
                })
                .when("--cascade-references-only", "--no-cascade-versions").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy.set(BumpPolicy.CascadePolicy.CASCADE_REFERENCES_ONLY);
                    }
                })
                .when("--no-cascade").asFlag(a -> {
                    if (a.booleanValue()) {
                        oo.cascadePolicy.set(BumpPolicy.CascadePolicy.NONE);
                    }
                })
                .when("--root").asEntry(a -> oo.roots.add(a.stringValue()))
                .when("--exclude").asEntry(a -> oo.excludes.add(a.stringValue()))
                .when("-a", "--artifact").asEntry(a -> handleArtifactArg(oo.subCommand.get(), a.stringValue(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
                .whenNonOption().asArg(a -> handleArtifactArg(oo.subCommand.get(), a.asString().get(), oo.cliInstructions, oo.explicitUpdates, oo.explicitReleases))
                .withDefaults()
                .requireAll();
    }

    private void matchCompareOptions(NCmdLine cmd, SharedOptions oo) {
        oo.subCommand.set("compare");
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
        DiagnosticReport report = versionService.check(config, workingDir);

        if (!NOut.isPlain()) {
            NObjectElementBuilder rootBuilder = NElement.ofObjectBuilder();
            rootBuilder.set("hasErrors", report.hasErrors());
            rootBuilder.set("hasWarnings", report.hasWarnings());
            rootBuilder.set("issueCount", report.size());
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
            return 0;
        } else {
            if (report.isEmpty()) {
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
            if (report.hasErrors()) {
                return 1;
            }
            if (failOnWarning && report.hasWarnings()) {
                return 1;
            }
            return 0;
        }
    }
}