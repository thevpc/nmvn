package net.thevpc.nmvn.lib.modifier;

import net.thevpc.nmvn.lib.model.PomChange;
import net.thevpc.nuts.io.NPath;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PomModifier {

    public PomChange createPomChange(NPath pomFile, String updatedContent) {
        String originalContent = pomFile.readString();
        return new PomChange(pomFile, originalContent, updatedContent);
    }

    /**
     * Replaces only the version string inside the tag body, preserving any inner
     * comments (<!-- ... -->), newlines, and surrounding indentation.
     */
    public static String replaceVersionInsideTag(String tagBody, String newVersion) {
        if (tagBody == null) {
            return newVersion;
        }

        // Find all XML comment spans: <!-- ... -->
        Pattern commentPattern = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
        Matcher cm = commentPattern.matcher(tagBody);
        List<int[]> commentSpans = new ArrayList<>();
        while (cm.find()) {
            commentSpans.add(new int[]{cm.start(), cm.end()});
        }

        // Find the non-whitespace version token outside any comment span
        int tokenStart = -1;
        int tokenEnd = -1;
        int i = 0;
        while (i < tagBody.length()) {
            // Check if current position is within a comment
            int[] inComment = null;
            for (int[] span : commentSpans) {
                if (i >= span[0] && i < span[1]) {
                    inComment = span;
                    break;
                }
            }
            if (inComment != null) {
                i = inComment[1];
                continue;
            }

            char c = tagBody.charAt(i);
            if (!Character.isWhitespace(c)) {
                if (tokenStart == -1) {
                    tokenStart = i;
                }
                tokenEnd = i + 1;
            } else {
                if (tokenStart != -1) {
                    // Reached end of version token
                    break;
                }
            }
            i++;
        }

        if (tokenStart != -1) {
            return tagBody.substring(0, tokenStart) + newVersion + tagBody.substring(tokenEnd);
        }
        return newVersion;
    }

    public static String updateProperty(String content, String propertyName, String newVersion) {
        Pattern propSectionPattern = Pattern.compile("(?s)(<properties\\b[^>]*>)(.*?)(</properties>)");
        Matcher propSectionMatcher = propSectionPattern.matcher(content);
        if (!propSectionMatcher.find()) {
            return content;
        }

        String before = content.substring(0, propSectionMatcher.start(2));
        String propsBody = propSectionMatcher.group(2);
        String after = content.substring(propSectionMatcher.end(2));

        Pattern tagPattern = Pattern.compile("(?s)(<" + Pattern.quote(propertyName) + "\\b[^>]*>)(.*?)(</" + Pattern.quote(propertyName) + ">)");
        Matcher tagMatcher = tagPattern.matcher(propsBody);
        if (tagMatcher.find()) {
            String updatedBody = tagMatcher.replaceFirst(Matcher.quoteReplacement(tagMatcher.group(1))
                    + Matcher.quoteReplacement(replaceVersionInsideTag(tagMatcher.group(2), newVersion))
                    + Matcher.quoteReplacement(tagMatcher.group(3)));
            return before + updatedBody + after;
        }

        return content;
    }

    public static String updateProjectVersion(String content, String newVersion) {
        Pattern versionPattern = Pattern.compile("(?s)(<version\\b[^>]*>)(.*?)(</version>)");
        Matcher matcher = versionPattern.matcher(content);

        // Find parent block bounds if any
        int parentStart = -1, parentEnd = -1;
        Matcher parentMatcher = Pattern.compile("(?s)<parent\\b[^>]*>.*?</parent>").matcher(content);
        if (parentMatcher.find()) {
            parentStart = parentMatcher.start();
            parentEnd = parentMatcher.end();
        }

        while (matcher.find()) {
            int start = matcher.start();
            // Skip if inside parent
            if (parentStart != -1 && start >= parentStart && start <= parentEnd) {
                continue;
            }
            // Skip if inside dependency or plugin or build
            String preceding = content.substring(0, start);
            int lastDep = preceding.lastIndexOf("<dependency");
            int lastDepEnd = preceding.lastIndexOf("</dependency>");
            if (lastDep != -1 && lastDep > lastDepEnd) {
                continue;
            }
            int lastPlugin = preceding.lastIndexOf("<plugin");
            int lastPluginEnd = preceding.lastIndexOf("</plugin>");
            if (lastPlugin != -1 && lastPlugin > lastPluginEnd) {
                continue;
            }

            // This is the project's own version!
            String updatedInner = replaceVersionInsideTag(matcher.group(2), newVersion);
            return content.substring(0, matcher.start(2)) + updatedInner + content.substring(matcher.end(2));
        }

        return content;
    }

    public static String updateParentVersion(String content, String newVersion) {
        Pattern parentPattern = Pattern.compile("(?s)(<parent\\b[^>]*>)(.*?)(</parent>)");
        Matcher parentMatcher = parentPattern.matcher(content);
        if (parentMatcher.find()) {
            String before = content.substring(0, parentMatcher.start(2));
            String parentBody = parentMatcher.group(2);
            String after = content.substring(parentMatcher.end(2));

            Pattern verPattern = Pattern.compile("(?s)(<version\\b[^>]*>)(.*?)(</version>)");
            Matcher verMatcher = verPattern.matcher(parentBody);
            if (verMatcher.find()) {
                String updatedInner = replaceVersionInsideTag(verMatcher.group(2), newVersion);
                String updatedParent = verMatcher.replaceFirst(Matcher.quoteReplacement(verMatcher.group(1))
                        + Matcher.quoteReplacement(updatedInner)
                        + Matcher.quoteReplacement(verMatcher.group(3)));
                return before + updatedParent + after;
            }
        }
        return content;
    }

    public static String updateDependencyVersion(String content, String groupId, String artifactId, String newVersion) {
        Pattern depPattern = Pattern.compile("(?s)<dependency\\b[^>]*>.*?</dependency>");
        Matcher depMatcher = depPattern.matcher(content);

        StringBuffer sb = new StringBuffer();
        while (depMatcher.find()) {
            String depBlock = depMatcher.group();
            if (containsCoordinates(depBlock, groupId, artifactId)) {
                Pattern verPattern = Pattern.compile("(?s)(<version\\b[^>]*>)(.*?)(</version>)");
                Matcher verMatcher = verPattern.matcher(depBlock);
                if (verMatcher.find()) {
                    String updatedInner = replaceVersionInsideTag(verMatcher.group(2), newVersion);
                    String updatedBlock = verMatcher.replaceFirst(Matcher.quoteReplacement(verMatcher.group(1))
                            + Matcher.quoteReplacement(updatedInner)
                            + Matcher.quoteReplacement(verMatcher.group(3)));
                    depMatcher.appendReplacement(sb, Matcher.quoteReplacement(updatedBlock));
                    continue;
                }
            }
            depMatcher.appendReplacement(sb, Matcher.quoteReplacement(depBlock));
        }
        depMatcher.appendTail(sb);
        return sb.toString();
    }

    public static String updatePluginVersion(String content, String groupId, String artifactId, String newVersion) {
        Pattern pluginPattern = Pattern.compile("(?s)<plugin\\b[^>]*>.*?</plugin>");
        Matcher pluginMatcher = pluginPattern.matcher(content);

        StringBuffer sb = new StringBuffer();
        while (pluginMatcher.find()) {
            String pluginBlock = pluginMatcher.group();
            boolean matchesGroup = groupId == null || "org.apache.maven.plugins".equals(groupId)
                    || pluginBlock.contains("<groupId>" + groupId + "</groupId>");
            boolean matchesArtifact = pluginBlock.contains("<artifactId>" + artifactId + "</artifactId>");

            if (matchesGroup && matchesArtifact) {
                // Replace outer plugin version (not inside an inner dependency)
                int innerDepIdx = pluginBlock.indexOf("<dependencies>");
                String pluginHeader = innerDepIdx != -1 ? pluginBlock.substring(0, innerDepIdx) : pluginBlock;
                String pluginTail = innerDepIdx != -1 ? pluginBlock.substring(innerDepIdx) : "";

                Pattern verPattern = Pattern.compile("(?s)(<version\\b[^>]*>)(.*?)(</version>)");
                Matcher verMatcher = verPattern.matcher(pluginHeader);
                if (verMatcher.find()) {
                    String updatedInner = replaceVersionInsideTag(verMatcher.group(2), newVersion);
                    String updatedHeader = verMatcher.replaceFirst(Matcher.quoteReplacement(verMatcher.group(1))
                            + Matcher.quoteReplacement(updatedInner)
                            + Matcher.quoteReplacement(verMatcher.group(3)));
                    pluginMatcher.appendReplacement(sb, Matcher.quoteReplacement(updatedHeader + pluginTail));
                    continue;
                }
            }
            pluginMatcher.appendReplacement(sb, Matcher.quoteReplacement(pluginBlock));
        }
        pluginMatcher.appendTail(sb);
        return sb.toString();
    }

    private static boolean containsCoordinates(String block, String groupId, String artifactId) {
        boolean matchGroup = groupId == null || block.contains("<groupId>" + groupId + "</groupId>");
        boolean matchArtifact = artifactId == null || block.contains("<artifactId>" + artifactId + "</artifactId>");
        return matchGroup && matchArtifact;
    }
}
