package net.thevpc.nmvn.lib.model;

import net.thevpc.nuts.io.NPath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PomChange {
    private final NPath pomFile;
    private final String originalContent;
    private final String newContent;
    private final List<String> descriptions = new ArrayList<>();
    private List<String> diffLines;

    public PomChange(NPath pomFile, String originalContent, String newContent) {
        this.pomFile = pomFile;
        this.originalContent = originalContent;
        this.newContent = newContent;
    }

    public NPath getPomFile() {
        return pomFile;
    }

    public String getOriginalContent() {
        return originalContent;
    }

    public String getNewContent() {
        return newContent;
    }

    public List<String> getDescriptions() {
        return descriptions;
    }

    public void addDescription(String desc) {
        if (desc != null && !desc.trim().isEmpty()) {
            descriptions.add(desc);
        }
    }

    public boolean hasChanges() {
        return !originalContent.equals(newContent);
    }

    public List<String> getDiffLines() {
        if (diffLines == null) {
            diffLines = computeSimpleUnifiedDiff(pomFile.toString(), originalContent, newContent);
        }
        return diffLines;
    }

    public static List<String> computeSimpleUnifiedDiff(String filename, String original, String revised) {
        if (original.equals(revised)) {
            return Collections.emptyList();
        }
        List<String> origLines = Arrays.asList(original.split("\\r?\\n", -1));
        List<String> revLines = Arrays.asList(revised.split("\\r?\\n", -1));
        List<String> diff = new ArrayList<>();
        diff.add("--- a/" + filename);
        diff.add("+++ b/" + filename);

        int max = Math.max(origLines.size(), revLines.size());
        int i = 0, j = 0;
        while (i < origLines.size() || j < revLines.size()) {
            if (i < origLines.size() && j < revLines.size() && origLines.get(i).equals(revLines.get(j))) {
                i++;
                j++;
            } else {
                int startI = Math.max(0, i - 1);
                int endI = Math.min(origLines.size(), i + 2);
                int startJ = Math.max(0, j - 1);
                int endJ = Math.min(revLines.size(), j + 2);

                diff.add(String.format("@@ -%d,%d +%d,%d @@", (startI + 1), (endI - startI), (startJ + 1), (endJ - startJ)));
                if (startI < i && startI < origLines.size()) {
                    diff.add(" " + origLines.get(startI));
                }
                while (i < origLines.size() && (j >= revLines.size() || !origLines.get(i).equals(revLines.get(j)))) {
                    diff.add("-" + origLines.get(i));
                    i++;
                }
                while (j < revLines.size() && (i >= origLines.size() || !revLines.get(j).equals(origLines.get(i)))) {
                    diff.add("+" + revLines.get(j));
                    j++;
                }
                if (i < origLines.size()) {
                    diff.add(" " + origLines.get(i));
                    i++;
                    j++;
                }
            }
        }
        return diff;
    }
}
