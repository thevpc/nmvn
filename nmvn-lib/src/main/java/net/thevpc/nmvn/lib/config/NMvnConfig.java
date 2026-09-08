package net.thevpc.nmvn.lib.config;

import net.thevpc.nmvn.lib.model.BumpInstruction;
import net.thevpc.nmvn.lib.model.BumpPolicy;

import java.util.*;

public class NMvnConfig {
    private List<String> roots = new ArrayList<>();
    private List<String> excludes = new ArrayList<>();
    private Map<String, List<String>> rootExcludes = new LinkedHashMap<>();
    private BumpPolicy bumpPolicy = new BumpPolicy();
    private List<BumpInstruction> instructions = new ArrayList<>();
    private String historyFile = ".nmvn/version-history.tson";

    public NMvnConfig() {
        // Sensible default global excludes
        excludes.add("**/target/**");
        excludes.add("**/build/**");
        excludes.add("**/.git/**");
        excludes.add("**/.svn/**");
        excludes.add("**/.idea/**");
    }

    public List<String> getRoots() {
        return roots;
    }

    public void setRoots(List<String> roots) {
        this.roots = roots != null ? new ArrayList<>(roots) : new ArrayList<>();
    }

    public List<String> getExcludes() {
        return excludes;
    }

    public void setExcludes(List<String> excludes) {
        this.excludes = excludes != null ? new ArrayList<>(excludes) : new ArrayList<>();
    }

    public Map<String, List<String>> getRootExcludes() {
        return rootExcludes;
    }

    public void setRootExcludes(Map<String, List<String>> rootExcludes) {
        this.rootExcludes = rootExcludes != null ? new LinkedHashMap<>(rootExcludes) : new LinkedHashMap<>();
    }

    public BumpPolicy getBumpPolicy() {
        return bumpPolicy;
    }

    public void setBumpPolicy(BumpPolicy bumpPolicy) {
        this.bumpPolicy = bumpPolicy != null ? bumpPolicy : new BumpPolicy();
    }

    public List<BumpInstruction> getInstructions() {
        return instructions;
    }

    public void setInstructions(List<BumpInstruction> instructions) {
        this.instructions = instructions != null ? new ArrayList<>(instructions) : new ArrayList<>();
    }

    public String getHistoryFile() {
        return historyFile;
    }

    public void setHistoryFile(String historyFile) {
        this.historyFile = historyFile != null ? historyFile : ".nmvn/version-history.tson";
    }
}
