package net.thevpc.nmvn.lib.parser;

import net.thevpc.nmvn.lib.model.DependencyEdgeType;
import net.thevpc.nmvn.lib.model.MavenCoord;
import net.thevpc.nmvn.lib.model.PomArtifact;
import net.thevpc.nmvn.lib.model.PomDependency;
import net.thevpc.nuts.artifact.NId;
import org.w3c.dom.*;

import net.thevpc.nuts.io.NPath;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

public class PomParser {

    public PomArtifact parse(NPath pomPath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();

        Document doc;
        try (InputStream in = pomPath.getInputStream()) {
            doc = builder.parse(in);
        }

        Element projectElem = doc.getDocumentElement();
        if (!"project".equals(projectElem.getNodeName())) {
            throw new IllegalArgumentException("Root element is not <project> in: " + pomPath);
        }

        // Parse parent
        Element parentElem = getDirectChild(projectElem, "parent");
        NId parentCoord = null;
        if (parentElem != null) {
            String pGroup = getChildText(parentElem, "groupId");
            String pArtifact = getChildText(parentElem, "artifactId");
            String pVersion = getChildText(parentElem, "version");
            if (pGroup != null && pArtifact != null) {
                parentCoord = (pVersion == null || pVersion.trim().isEmpty())
                        ? NId.of(pGroup, pArtifact)
                        : NId.of(pGroup, pArtifact, pVersion.trim());
            }
        }

        // Parse coordinates
        String groupId = getChildText(projectElem, "groupId");
        if (groupId == null && parentCoord != null) {
            groupId = parentCoord.groupId();
        }
        String artifactId = getChildText(projectElem, "artifactId");
        String version = getChildText(projectElem, "version");
        if (version == null && parentCoord != null && !parentCoord.version().isBlank()) {
            version = parentCoord.version().value();
        }

        if (groupId == null || artifactId == null) {
            throw new IllegalArgumentException("Missing groupId or artifactId in " + pomPath);
        }

        NId coord = (version == null || version.trim().isEmpty())
                ? NId.of(groupId, artifactId)
                : NId.of(groupId, artifactId, version.trim());
        PomArtifact artifact = new PomArtifact(coord, parentCoord, pomPath);

        // Parse properties
        Element propertiesElem = getDirectChild(projectElem, "properties");
        if (propertiesElem != null) {
            NodeList propNodes = propertiesElem.getChildNodes();
            for (int i = 0; i < propNodes.getLength(); i++) {
                Node n = propNodes.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE) {
                    String propName = n.getNodeName();
                    String propVal = n.getTextContent();
                    artifact.getDeclaredProperties().put(propName, propVal != null ? propVal.trim() : "");
                }
            }
        }

        // Parse direct dependencies
        Element dependenciesElem = getDirectChild(projectElem, "dependencies");
        if (dependenciesElem != null) {
            parseDependencyList(dependenciesElem, artifact, false, DependencyEdgeType.DIRECT_DEPENDENCY);
        }

        // Parse dependencyManagement
        Element depMgmtElem = getDirectChild(projectElem, "dependencyManagement");
        if (depMgmtElem != null) {
            Element mgmtDeps = getDirectChild(depMgmtElem, "dependencies");
            if (mgmtDeps != null) {
                parseDependencyList(mgmtDeps, artifact, true, DependencyEdgeType.DIRECT_DEPENDENCY);
            }
        }

        // Parse build -> plugins
        Element buildElem = getDirectChild(projectElem, "build");
        if (buildElem != null) {
            Element pluginsElem = getDirectChild(buildElem, "plugins");
            if (pluginsElem != null) {
                parsePluginList(pluginsElem, artifact);
            }
            Element pluginMgmtElem = getDirectChild(buildElem, "pluginManagement");
            if (pluginMgmtElem != null) {
                Element mgmtPlugins = getDirectChild(pluginMgmtElem, "plugins");
                if (mgmtPlugins != null) {
                    parsePluginList(mgmtPlugins, artifact);
                }
            }
        }

        // Parse modules
        Element modulesElem = getDirectChild(projectElem, "modules");
        if (modulesElem != null) {
            NodeList moduleNodes = modulesElem.getElementsByTagName("module");
            for (int i = 0; i < moduleNodes.getLength(); i++) {
                String mName = moduleNodes.item(i).getTextContent();
                if (mName != null && !mName.trim().isEmpty()) {
                    artifact.getModules().add(mName.trim());
                }
            }
        }

        return artifact;
    }

    private void parseDependencyList(Element container, PomArtifact artifact, boolean isManagement, DependencyEdgeType defaultEdge) {
        NodeList depNodes = container.getChildNodes();
        for (int i = 0; i < depNodes.getLength(); i++) {
            Node n = depNodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "dependency".equals(n.getNodeName())) {
                Element depElem = (Element) n;
                String g = getChildText(depElem, "groupId");
                String a = getChildText(depElem, "artifactId");
                String v = getChildText(depElem, "version");
                String scope = getChildText(depElem, "scope");
                String type = getChildText(depElem, "type");

                if (g != null && a != null) {
                    boolean isBom = "pom".equalsIgnoreCase(type) && "import".equalsIgnoreCase(scope);
                    DependencyEdgeType edge = isBom ? DependencyEdgeType.BOM_IMPORT : defaultEdge;
                    PomDependency dep = new PomDependency(g, a, v, scope, type, isManagement, edge);
                    if (isManagement) {
                        artifact.getDependencyManagement().add(dep);
                    } else {
                        artifact.getDependencies().add(dep);
                    }
                }
            }
        }
    }

    private void parsePluginList(Element pluginsElem, PomArtifact artifact) {
        NodeList pluginNodes = pluginsElem.getChildNodes();
        for (int i = 0; i < pluginNodes.getLength(); i++) {
            Node n = pluginNodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "plugin".equals(n.getNodeName())) {
                Element pluginElem = (Element) n;
                String g = getChildText(pluginElem, "groupId");
                if (g == null) {
                    g = "org.apache.maven.plugins"; // Maven default plugin groupId
                }
                String a = getChildText(pluginElem, "artifactId");
                String v = getChildText(pluginElem, "version");
                if (a != null) {
                    PomDependency pluginDep = new PomDependency(g, a, v, null, "maven-plugin", false, DependencyEdgeType.PLUGIN);
                    artifact.getPluginDependencies().add(pluginDep);
                }

                // Inner dependencies of plugin
                Element innerDepsElem = getDirectChild(pluginElem, "dependencies");
                if (innerDepsElem != null) {
                    parseDependencyList(innerDepsElem, artifact, false, DependencyEdgeType.PLUGIN);
                }
            }
        }
    }

    private Element getDirectChild(Element parent, String tagName) {
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tagName.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private String getChildText(Element parent, String tagName) {
        Element child = getDirectChild(parent, tagName);
        if (child != null) {
            String text = child.getTextContent();
            return text != null ? text.trim() : null;
        }
        return null;
    }
}
