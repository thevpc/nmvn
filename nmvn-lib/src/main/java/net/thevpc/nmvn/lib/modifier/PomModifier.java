package net.thevpc.nmvn.lib.modifier;

import net.thevpc.nmvn.lib.model.PomChange;
import net.thevpc.nuts.io.NPath;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PomModifier {
    private static final Pattern XML_TAGS = Pattern.compile(
            "(?s)<!--.*?-->|<!\\[CDATA\\[.*?\\]\\]>|<\\?.*?\\?>|<!DOCTYPE\\b.*?>"
                    + "|<(/?)([A-Za-z_][A-Za-z0-9_.:-]*)(?:\\s[^<>]*?)?(/?)>");

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
        TagRange project = uniqueRoot(content, "project");
        TagRange properties = uniqueChild(content, project, "properties", "properties section");
        TagRange property = uniqueChild(content, properties, propertyName, "property " + propertyName);
        return replaceBody(content, property, newVersion);
    }

    public static String updateProjectVersion(String content, String newVersion) {
        TagRange project = uniqueRoot(content, "project");
        return replaceBody(content, uniqueChild(content, project, "version", "project version"), newVersion);
    }

    public static String updateParentVersion(String content, String newVersion) {
        TagRange project = uniqueRoot(content, "project");
        TagRange parent = uniqueChild(content, project, "parent", "parent section");
        return replaceBody(content, uniqueChild(content, parent, "version", "parent version"), newVersion);
    }

    public static String updateDependencyVersion(String content, String groupId, String artifactId, String newVersion) {
        return updateCoordinateBlocks(content, "dependency", groupId, artifactId, newVersion);
    }

    public static String updatePluginVersion(String content, String groupId, String artifactId, String newVersion) {
        return updateCoordinateBlocks(content, "plugin", groupId, artifactId, newVersion);
    }

    private static String updateCoordinateBlocks(String content, String elementName, String groupId,
                                                 String artifactId, String newVersion) {
        List<TagRange> projects = findElements(content, 0, content.length(), "project");
        if (projects.size() != 1) {
            throw new IllegalArgumentException("Expected one <project> element while updating " + elementName);
        }
        List<TagRange> matches = new ArrayList<>();
        for (TagRange block : findDescendants(content, projects.get(0).bodyStart, projects.get(0).bodyEnd, elementName)) {
            String g = childText(content, block, "groupId");
            String a = childText(content, block, "artifactId");
            if (artifactId.equals(a) && (groupId == null || groupId.equals(g)
                    || ("org.apache.maven.plugins".equals(groupId) && g == null))) {
                matches.add(block);
            }
        }
        String updated = content;
        int versionCount = 0;
        for (int i = matches.size() - 1; i >= 0; i--) {
            TagRange block = matches.get(i);
            List<TagRange> versions = findElements(content, block.bodyStart, block.bodyEnd, "version");
            if (versions.size() > 1) {
                throw new IllegalArgumentException("Ambiguous <version> in " + elementName + " "
                        + groupId + ":" + artifactId);
            }
            if (versions.size() == 1) {
                versionCount++;
                TagRange version = versions.get(0);
                if (content.substring(version.start, version.end).trim().endsWith("/>")) {
                    throw new IllegalArgumentException("Cannot update a self-closing <version> in " + elementName);
                }
                updated = updated.substring(0, version.bodyStart)
                        + replaceVersionInsideTag(content.substring(version.bodyStart, version.bodyEnd), newVersion)
                        + updated.substring(version.bodyEnd);
            }
        }
        if (!matches.isEmpty() && versionCount == 0) {
            throw new IllegalArgumentException("Matched " + elementName + " " + groupId + ":" + artifactId
                    + " has no explicit <version> to update");
        }
        return updated;
    }

    private static String childText(String content, TagRange parent, String name) {
        List<TagRange> children = findElements(content, parent.bodyStart, parent.bodyEnd, name);
        if (children.size() > 1) {
            throw new IllegalArgumentException("Ambiguous <" + name + "> in Maven coordinates");
        }
        if (children.isEmpty()) {
            return null;
        }
        String text = content.substring(children.get(0).bodyStart, children.get(0).bodyEnd);
        return text.replaceAll("(?s)<!--.*?-->", "").trim();
    }

    private static TagRange uniqueRoot(String content, String name) {
        List<TagRange> found = findElements(content, 0, content.length(), name);
        if (found.size() != 1) {
            throw new IllegalArgumentException("Expected one <" + name + "> element");
        }
        return found.get(0);
    }

    private static TagRange uniqueChild(String content, TagRange parent, String name, String description) {
        List<TagRange> found = findElements(content, parent.bodyStart, parent.bodyEnd, name);
        if (found.size() != 1) {
            throw new IllegalArgumentException("Expected exactly one " + description + ", found " + found.size());
        }
        return found.get(0);
    }

    private static String replaceBody(String content, TagRange tag, String newVersion) {
        if (content.substring(tag.start, tag.end).trim().endsWith("/>")) {
            throw new IllegalArgumentException("Cannot replace the body of a self-closing XML element");
        }
        String body = content.substring(tag.bodyStart, tag.bodyEnd);
        return content.substring(0, tag.bodyStart) + replaceVersionInsideTag(body, newVersion)
                + content.substring(tag.bodyEnd);
    }

    private static List<TagRange> findElements(String content, int start, int end, String wantedName) {
        List<TagRange> result = new ArrayList<>();
        Matcher matcher = XML_TAGS.matcher(content);
        matcher.region(start, end);
        List<String> stack = new ArrayList<>();
        int directStart = -1;
        int directBodyStart = -1;
        String directName = null;
        while (matcher.find()) {
            if (matcher.group(2) == null) {
                continue;
            }
            String name = matcher.group(2);
            boolean closing = !matcher.group(1).isEmpty();
            boolean selfClosing = !matcher.group(3).isEmpty();
            if (closing) {
                if (!stack.isEmpty()) {
                    String popped = stack.remove(stack.size() - 1);
                    if (stack.isEmpty() && wantedName.equals(directName) && wantedName.equals(popped)) {
                        result.add(new TagRange(directStart, matcher.end(), directBodyStart, matcher.start()));
                        directStart = -1;
                        directBodyStart = -1;
                        directName = null;
                    }
                }
            } else {
                if (stack.isEmpty() && wantedName.equals(name)) {
                    directStart = matcher.start();
                    directBodyStart = matcher.end();
                    directName = name;
                    if (selfClosing) {
                        result.add(new TagRange(directStart, matcher.end(), matcher.end(), matcher.end()));
                        directStart = -1;
                        directBodyStart = -1;
                        directName = null;
                    }
                }
                if (!selfClosing) {
                    stack.add(name);
                }
            }
        }
        return result;
    }

    private static List<TagRange> findDescendants(String content, int start, int end, String wantedName) {
        List<TagRange> result = new ArrayList<>();
        Matcher matcher = XML_TAGS.matcher(content);
        matcher.region(start, end);
        List<TagRange> stack = new ArrayList<>();
        while (matcher.find()) {
            if (matcher.group(2) == null) {
                continue;
            }
            String name = matcher.group(2);
            boolean closing = !matcher.group(1).isEmpty();
            boolean selfClosing = !matcher.group(3).isEmpty();
            if (closing) {
                if (!stack.isEmpty()) {
                    TagRange opened = stack.remove(stack.size() - 1);
                    if (wantedName.equals(opened.tagName)) {
                        result.add(new TagRange(opened.start, matcher.end(), opened.bodyStart, matcher.start(), opened.tagName));
                    }
                }
            } else {
                TagRange opened = new TagRange(matcher.start(), matcher.end(), matcher.end(), matcher.end(), name);
                if (selfClosing) {
                    if (wantedName.equals(name)) {
                        result.add(opened);
                    }
                } else {
                    stack.add(opened);
                }
            }
        }
        return result;
    }

    private static final class TagRange {
        final int start;
        final int end;
        final int bodyStart;
        final int bodyEnd;
        final String tagName;

        TagRange(int start, int end, int bodyStart, int bodyEnd) {
            this(start, end, bodyStart, bodyEnd, null);
        }

        TagRange(int start, int end, int bodyStart, int bodyEnd, String tagName) {
            this.start = start;
            this.end = end;
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyEnd;
            this.tagName = tagName;
        }
    }
}
