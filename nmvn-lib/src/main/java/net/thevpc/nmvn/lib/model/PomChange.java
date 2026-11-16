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
        String[] a = original.split("\\r?\\n", -1);
        String[] b = revised.split("\\r?\\n", -1);

        // edit script over the region between the common prefix and suffix (LCS)
        int pre = 0;
        while (pre < a.length && pre < b.length && a[pre].equals(b[pre])) {
            pre++;
        }
        int suf = 0;
        while (suf < a.length - pre && suf < b.length - pre
                && a[a.length - 1 - suf].equals(b[b.length - 1 - suf])) {
            suf++;
        }
        int n = a.length - pre - suf;
        int m = b.length - pre - suf;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a[pre + i].equals(b[pre + j])
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        // ops: ' ' kept, '-' removed, '+' added ; aIdx/bIdx are the 0-based line positions before the op
        StringBuilder ops = new StringBuilder();
        List<int[]> pos = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int k = 0; k < pre; k++) {
            ops.append(' ');
            pos.add(new int[]{k, k});
            texts.add(a[k]);
        }
        int i = 0, j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a[pre + i].equals(b[pre + j])) {
                ops.append(' ');
                pos.add(new int[]{pre + i, pre + j});
                texts.add(a[pre + i]);
                i++;
                j++;
            } else if (i < n && (j >= m || lcs[i + 1][j] >= lcs[i][j + 1])) {
                ops.append('-');
                pos.add(new int[]{pre + i, pre + j});
                texts.add(a[pre + i]);
                i++;
            } else {
                ops.append('+');
                pos.add(new int[]{pre + i, pre + j});
                texts.add(b[pre + j]);
                j++;
            }
        }
        for (int k = 0; k < suf; k++) {
            ops.append(' ');
            pos.add(new int[]{a.length - suf + k, b.length - suf + k});
            texts.add(a[a.length - suf + k]);
        }

        List<String> diff = new ArrayList<>();
        diff.add("--- a/" + filename);
        diff.add("+++ b/" + filename);
        int context = 1;
        int k = 0;
        while (k < ops.length()) {
            if (ops.charAt(k) == ' ') {
                k++;
                continue;
            }
            int hunkStart = Math.max(0, k - context);
            int hunkEnd = k;
            // extend the hunk while changes are within 2*context kept lines of each other
            while (hunkEnd < ops.length()) {
                if (ops.charAt(hunkEnd) != ' ') {
                    hunkEnd++;
                    continue;
                }
                int next = hunkEnd;
                while (next < ops.length() && ops.charAt(next) == ' ') {
                    next++;
                }
                if (next < ops.length() && next - hunkEnd <= 2 * context) {
                    hunkEnd = next;
                } else {
                    hunkEnd = Math.min(ops.length(), hunkEnd + context);
                    break;
                }
            }
            int aCount = 0, bCount = 0;
            for (int x = hunkStart; x < hunkEnd; x++) {
                char op = ops.charAt(x);
                if (op != '+') {
                    aCount++;
                }
                if (op != '-') {
                    bCount++;
                }
            }
            diff.add(String.format("@@ -%d,%d +%d,%d @@",
                    pos.get(hunkStart)[0] + 1, aCount, pos.get(hunkStart)[1] + 1, bCount));
            for (int x = hunkStart; x < hunkEnd; x++) {
                diff.add(ops.charAt(x) + texts.get(x));
            }
            k = hunkEnd;
        }
        return diff;
    }
}
