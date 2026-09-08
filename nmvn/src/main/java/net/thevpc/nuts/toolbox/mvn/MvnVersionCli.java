package net.thevpc.nuts.toolbox.mvn;

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
import net.thevpc.nuts.cmdline.NArg;
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

import java.io.IOException;
import java.util.*;

public class MvnVersionCli {

    private final NSession session;
    private final VersionService versionService = new VersionService();

    public MvnVersionCli(NSession session) {
        this.session = session;
    }

    public int run(String[] args, boolean jsonOutput) {
        NSession session = this.session.copy();
        NCmdLine cmd = NCmdLine.of(args);
        NRef<String> subCommand = NRef.ofNull();
        NRef<String> configPath = NRef.ofNull();
        NRef<Boolean> strict = NRef.of(false);
        NRef<Boolean> failOnWarning = NRef.of(false);
        NRef<Boolean> cascadeVersions = NRef.ofNull();
        NRef<Boolean> jsonOutputRef = NRef.of(jsonOutput);
        NRef<BumpPolicy.IncrementType> increment = NRef.ofNull();
        NRef<Boolean> force = NRef.of(false);
        List<String> roots = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        List<BumpInstruction> cliInstructions = new ArrayList<>();
        Map<NId, String> explicitUpdates = new LinkedHashMap<>();
        Map<NId, String> explicitReleases = new LinkedHashMap<>();

        while (cmd.hasNext()) {
            if (subCommand.isNull()) {
                if (!cmd.matcher()
                        .when("scan").asArg(a -> subCommand.set("scan"))
                        .when("bump").asArg(a -> subCommand.set("bump"))
                        .when("update", "set").asArg(a -> subCommand.set("update"))
                        .when("release", "fix-snapshots").asArg(a -> subCommand.set("release"))
                        .when("check", "validate").asArg(a -> subCommand.set("check"))
                        .when("-j", "--json").asFlag(a -> jsonOutputRef.set(a.booleanValue()))
                        .anyMatch()) {
                    if (session.configureFirst(cmd)) {
                        // handled by nuts
                    } else {
                        cmd.throwUnexpectedArgument();
                    }
                }
            } else {
                if (!cmd.matcher()
                        .when("--workset", "--ws", "--config").asEntry(a -> configPath.set(a.stringValue()))
                        .when("--strict").asFlag(a -> strict.set(a.booleanValue()))
                        .when("--fail-on-warning").asFlag(a -> failOnWarning.set(a.booleanValue()))
                        .when("--patch").asFlag(a -> increment.set(BumpPolicy.IncrementType.PATCH))
                        .when("--minor").asFlag(a -> increment.set(BumpPolicy.IncrementType.MINOR))
                        .when("--major").asFlag(a -> increment.set(BumpPolicy.IncrementType.MAJOR))
                        .when("-f", "--force").asFlag(a -> force.set(a.booleanValue()))
                        .when("--cascade-versions").asFlag(a -> {
                            if (a.booleanValue()) {
                                cascadeVersions.set(true);
                            }
                        })
                        .when("--cascade-references-only").asFlag(a -> {
                            if (a.booleanValue()) {
                                cascadeVersions.set(false);
                            }
                        })
                        .when("--root").asEntry(a -> roots.add(a.stringValue()))
                        .when("--exclude").asEntry(a -> excludes.add(a.stringValue()))
                        .when("-a", "--artifact").asEntry(a -> handleArtifactArg(subCommand.get(), a.stringValue(), cliInstructions, explicitUpdates, explicitReleases))
                        .whenNonOption().asArg(a -> handleArtifactArg(subCommand.get(), a.asString().get(), cliInstructions, explicitUpdates, explicitReleases))
                        .anyMatch()) {
                    if (session.configureFirst(cmd)) {
                        // handled by nuts
                    } else {
                        cmd.throwUnexpectedArgument();
                    }
                }
            }
        }

        if (subCommand.isNull()) {
            NOut.println(NMsg.ofP("Usage: nmvn version <scan|bump|update|release|check> [options]"));
            return 1;
        }

        NPath workingDir = NPath.ofUserDirectory();
        NPath cfgFile = NMvnConfigLoader.resolveConfigFile(configPath.get(), workingDir);
        NMvnConfig config = NMvnConfigLoader.load(cfgFile);

        // Apply CLI overrides
        if (!roots.isEmpty()) {
            config.setRoots(roots);
        }
        if (!excludes.isEmpty()) {
            config.getExcludes().addAll(excludes);
        }

        boolean effectiveApply = !session.isDry();

        try {
            switch (subCommand.get()) {
                case "scan":
                    return doScan(config, workingDir, jsonOutputRef.get());
                case "bump":
                    return doBump(config, workingDir, cliInstructions, increment.get(), cascadeVersions.get(), force.get(), effectiveApply, jsonOutputRef.get());
                case "update":
                    return doUpdate(config, workingDir, explicitUpdates, effectiveApply, jsonOutputRef.get());
                case "release":
                    return doRelease(config, workingDir, explicitReleases, strict.get(), effectiveApply, jsonOutputRef.get());
                case "check":
                    return doCheck(config, workingDir, failOnWarning.get(), jsonOutputRef.get());
                default:
                    NOut.println(NMsg.ofC("Unknown sub-command: %s", subCommand.get()));
                    return 1;
            }
        } catch (Exception e) {
            NOut.println(NMsg.ofStyled("Error: " + e.getMessage(), NTextStyle.danger()));
            return 1;
        }
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

    private int doScan(NMvnConfig config, NPath workingDir, boolean jsonOutput) throws IOException {
        ScanResult scan = versionService.scan(config, workingDir);
        Map<NId, PomArtifact> artifacts = scan.getArtifacts();
        MavenDependencyGraph graph = scan.getGraph();

        if (jsonOutput) {
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
                       Boolean cascadeVersions, boolean force, boolean apply, boolean jsonOutput) throws IOException {
        BumpResult result = versionService.bump(config, workingDir, explicitBumps, increment, cascadeVersions, force, apply);
        renderChanges(result.getChanges(), apply, jsonOutput);
        return 0;
    }

    private int doUpdate(NMvnConfig config, NPath workingDir, Map<NId, String> explicitUpdates,
                         boolean apply, boolean jsonOutput) throws IOException {
        BumpResult result = versionService.update(config, workingDir, explicitUpdates, apply);
        renderChanges(result.getChanges(), apply, jsonOutput);
        return 0;
    }

    private int doRelease(NMvnConfig config, NPath workingDir, Map<NId, String> explicitReleases,
                          boolean strict, boolean apply, boolean jsonOutput) throws IOException {
        ReleaseResult result = versionService.release(config, workingDir, explicitReleases, strict, apply);
        renderChanges(result.getChanges(), apply, jsonOutput);
        return 0;
    }

    private void renderChanges(List<PomChange> changes, boolean apply, boolean jsonOutput) {
        if (changes.isEmpty()) {
            NOut.println(NMsg.ofStyled("No changes required. All POMs are up-to-date.", NTextStyle.info()));
            return;
        }

        if (jsonOutput) {
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

    private int doCheck(NMvnConfig config, NPath workingDir, boolean failOnWarning, boolean jsonOutput) throws IOException {
        DiagnosticReport report = versionService.check(config, workingDir);

        if (jsonOutput) {
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
