/*
 * Copyright 2022 The Embulk project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.embulk.gradle.embulk_plugins;

import groovy.util.Node;
import groovy.util.NodeList;
import groovy.xml.QName;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;

/**
 * A wrapping manipulator of {@code <dependencies>} node.
 *
 * <p>A typical use of this class is:
 *
 * <pre>{@code // DependenciesNodeManipulator implements Autocloseable.
 * try (final DependenciesNodeManipulator xml = DependenciesNodeManipulator.of(node)) {
 *     xml.insertProvidedDependency(...);  // Reserved to commit at last.
 *     xml.applyCompileRuntimeDependency(...);  // Reserved to commit at last.
 *     // ...
 *     xml.toCommit();  // Determied to commit.
 * }  // Manipulations are actually committed in XML at last in close().
 * }</pre>
 */
final class DependenciesNodeManipulator implements AutoCloseable {
    private DependenciesNodeManipulator(
            final Node pom,
            final Node dependencies,
            final NodeList dependenciesChildren,
            final LinkedHashMap<VersionlessDependency, Node> nodeMap,
            final Logger logger) {
        this.pom = pom;
        this.dependencies = dependencies;
        this.dependenciesChildren = dependenciesChildren;
        this.nodeMap = Collections.unmodifiableMap(nodeMap);
        this.dependencyManagementsToRemove = new ArrayList<>();
        this.providedDependenciesToInsert = new LinkedHashMap<>();
        this.existingDependenciesToModify = new LinkedHashMap<>();
        this.compileRuntimeDependenciesToAppend = new LinkedHashMap<>();
        this.remainingDependenciesToOverride = new LinkedHashMap<>();
        this.additionalDependencies = new LinkedHashMap<>();

        this.toCommit = false;
        this.prefixForLoggingAfterCommit = null;
        this.logger = logger;
    }

    static DependenciesNodeManipulator of(
            final Node pom,
            final Logger logger) {
        final Node dependencies = getSingleInnerNode(pom, "dependencies", "pom");

        final NodeList children;
        try {
            children = (NodeList) dependencies.children();
        } catch (final ClassCastException ex) {
            throw new GradleException("<dependencies> includes an invalid child node unexpectedly.", ex);
        }

        final LinkedHashMap<VersionlessDependency, Node> nodeMap = new LinkedHashMap<>();

        for (final Object dependencyObject : children) {
            final Node dependencyNode;
            try {
                dependencyNode = (Node) dependencyObject;
                if (dependencyNode.name() instanceof QName) {
                    final QName dependencyName = (QName) dependencyNode.name();
                    if (!"dependency".equals(dependencyName.getQualifiedName())) {
                        throw new GradleException("<dependencies> includes a non-<dependency> child node unexpectedly.");
                    }
                } else if (dependencyNode.name() instanceof String) {
                    final String dependencyName = (String) dependencyNode.name();
                    if (!"dependency".equals(dependencyName)) {
                        throw new GradleException("<dependencies> includes a non-<dependency> child node unexpectedly.");
                    }
                }
            } catch (final ClassCastException ex) {
                throw new GradleException("<dependencies> includes an invalid child node unexpectedly.", ex);
            }

            final VersionlessDependency dependency = VersionlessDependency.of(
                    getGroupId(dependencyNode, null),
                    getArtifactId(dependencyNode, null),
                    getClassifier(dependencyNode, null));
            nodeMap.put(dependency, dependencyNode);
        }

        return new DependenciesNodeManipulator(pom, dependencies, children, nodeMap, logger);
    }

    void assertScopes() {
        for (final Object dependencyObject : this.dependenciesChildren) {
            final Node dependencyNode;
            try {
                dependencyNode = (Node) dependencyObject;
                final QName dependencyName = (QName) dependencyNode.name();
                if (!"dependency".equals(dependencyName.getQualifiedName())) {
                    throw new GradleException("<dependencies> includes a non-<dependency> child node unexpectedly.");
                }
            } catch (final ClassCastException ex) {
                throw new GradleException("<dependencies> includes an invalid child node unexpectedly.", ex);
            }
            final MavenScope scope = getScope(dependencyNode);
            if (scope == MavenScope.PROVIDED) {
                throw new GradleException("<dependencies> includes a dependency with a provided scope unexpectedly.");
            }
            if (scope == MavenScope.SYSTEM) {
                throw new GradleException("<dependencies> includes a dependency with a system scope unexpectedly.");
            }
        }
    }

    /**
     * Remove the {@code <dependencyManagement>} BOM node.
     */
    void removeDependencyManagement() {
        final Node dependencyManagement = getSingleInnerNode(pom, "dependencyManagement", "pom");
        if (dependencyManagement != null) {
            this.dependencyManagementsToRemove.add(dependencyManagement);
        }
    }

    /**
     * Insert a new {@code <dependency>} node at the top, or update an existing {@code <dependency>}.
     */
    Node insertProvidedDependency(final ScopedDependency dependency) {
        final Node node = newDependencyNode(dependency);
        this.providedDependenciesToInsert.put(dependency, node);
        return node;
    }

    /**
     * Append a new {@code <dependency>} node at the bottom, or update an existing {@code <dependency>}.
     */
    Node applyCompileRuntimeDependency(final ScopedDependency dependency) {
        final Node found = this.nodeMap.get(dependency.getVersionlessDependency());
        if (found != null) {
            this.existingDependenciesToModify.put(dependency, found);
            return found;
        } else {
            final Node node = newDependencyNode(dependency);
            this.compileRuntimeDependenciesToAppend.put(dependency, node);
            return node;
        }
    }

    /**
     * Add {@code <exclusions><exclusion><groupId>*</groupId></exclusion></exclusions>} to remaining dependencies.
     *
     * <p>A typical case is a dependency introduced by subprojects, such as: {@code implementation project(":subproject")}
     */
    void gleanRemainingDependencies() {
        final LinkedHashMap<VersionlessDependency, Node> modifiedDependencies = new LinkedHashMap<>();
        for (final Map.Entry<ScopedDependency, Node> entry : this.existingDependenciesToModify.entrySet()) {
            modifiedDependencies.put(entry.getKey().getVersionlessDependency(), entry.getValue());
        }

        this.nodeMap.entrySet().stream().filter(entry -> {
            return !modifiedDependencies.containsKey(entry.getKey());
        }).forEach(entry -> {
            this.remainingDependenciesToOverride.put(entry.getKey(), entry.getValue());
        });
    }

    void addDependencyDeclarations(final List<ScopedDependency> dependencies) {
        for (final ScopedDependency dependency : dependencies) {
            this.additionalDependencies.put(dependency, newDependencyNode(dependency));
        }
    }

    void logDependencies(final String prefix) {
        final StringBuilder builder = new StringBuilder();
        builder.append("\n");
        for (final Object dependencyObject : this.dependenciesChildren) {
            final Node dependencyNode;
            try {
                dependencyNode = (Node) dependencyObject;
                if (dependencyNode.name() instanceof QName) {
                    final QName dependencyName = (QName) dependencyNode.name();
                    if (!"dependency".equals(dependencyName.getQualifiedName())) {
                        throw new GradleException("<dependencies> includes a non-<dependency> child node unexpectedly.");
                    }
                } else if (dependencyNode.name() instanceof String) {
                    final String dependencyName = (String) dependencyNode.name();
                    if (!"dependency".equals(dependencyName)) {
                        throw new GradleException("<dependencies> includes a non-<dependency> child node unexpectedly.");
                    }
                }
            } catch (final ClassCastException ex) {
                throw new GradleException("<dependencies> includes an invalid child node unexpectedly.", ex);
            }

            final VersionlessDependency dependency = VersionlessDependency.of(
                    getGroupId(dependencyNode, null),
                    getArtifactId(dependencyNode, null),
                    getClassifier(dependencyNode, null));
            builder.append("    => ");
            builder.append(dependency.toString(
                    getVersion(dependencyNode, "(empty)"),
                    getScope(dependencyNode)));
            builder.append("\n");
        }

        this.logger.lifecycle(prefix + "{}", builder.toString());
    }

    void toCommit(final String prefixForLogging) {
        this.toCommit = true;
        this.prefixForLoggingAfterCommit = prefixForLogging;
    }

    @Override
    public void close() {
        if (this.toCommit) {  // Commit reserved operations to the actual XML.
            for (final Node dependencyManagement : this.dependencyManagementsToRemove) {
                this.logger.lifecycle("<dependencyManagement> is going to be removed.");
                this.pom.remove(dependencyManagement);
            }

            this.logger.lifecycle("<dependencies> is going to be updated:");

            this.insertProvidedDependencies();

            for (final Map.Entry<ScopedDependency, Node> entry : existingDependenciesToModify.entrySet()) {
                this.logger.lifecycle("    => [MODIFY] {}", entry.getKey());
                this.modifyExistingNode(entry.getValue(), entry.getKey());
            }
            for (final Map.Entry<VersionlessDependency, Node> entry : this.remainingDependenciesToOverride.entrySet()) {
                this.logger.lifecycle("    => [MODIFY] {}", entry.getKey());
                this.overrideExclusions(entry.getValue());
            }

            this.appendCompileRuntimeDependencies();
            this.appendAdditionalDependencies();

            this.logger.lifecycle("");

            if (this.prefixForLoggingAfterCommit != null) {
                this.logDependencies(this.prefixForLoggingAfterCommit);
            }
        } else {
            this.logger.warn("<dependencies> won't change. Breaking out without committing.");
        }
    }

    @SuppressWarnings("unchecked")
    private void insertProvidedDependencies() {
        for (final Map.Entry<ScopedDependency, Node> entry : this.providedDependenciesToInsert.entrySet()) {
            this.logger.lifecycle("    => [INSERT] {}", entry.getKey());
        }
        this.dependenciesChildren.addAll(0, this.providedDependenciesToInsert.values());
    }

    private Node modifyExistingNode(final Node node, final ScopedDependency dependency) {
        boolean modified = false;
        modified |= this.modifyVersionIfDifferent(node, dependency.getVersion());
        modified |= this.modifyScopeIfDifferent(node, dependency.getScope());
        modified |= this.overrideExclusions(node);
        if (!modified) {
            this.logger.lifecycle("      => no changes");
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private void appendCompileRuntimeDependencies() {
        for (final Map.Entry<ScopedDependency, Node> entry : this.compileRuntimeDependenciesToAppend.entrySet()) {
            this.logger.lifecycle("    => [APPEND] {}", entry.getKey());
            this.dependenciesChildren.add(entry.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    private void appendAdditionalDependencies() {
        for (final Map.Entry<ScopedDependency, Node> entry : this.additionalDependencies.entrySet()) {
            this.logger.lifecycle("    => [APPEND] {}", entry.getKey());
            this.dependenciesChildren.add(entry.getValue());
        }
    }

    private boolean modifyVersionIfDifferent(final Node node, final String version) {
        return modifyTextInSingleInnerNodeIfDifferent(node, "version", "dependency", version, this.logger);
    }

    private boolean modifyScopeIfDifferent(final Node node, final MavenScope scope) {
        return modifyTextInSingleInnerNodeIfDifferent(node, "scope", "dependency", scope.toMavenString(), this.logger);
    }

    private boolean overrideExclusions(final Node node) {
        final Node exclusionsNode = getSingleInnerNode(node, "exclusions", "dependency");
        if (exclusionsNode == null) {
            this.logger.lifecycle("      => Add <exclusions><exclusion><groupId>*</groupId></exclusion></exclusions>");
            final Node newExclusionsNode = node.appendNode("exclusions");
            newExclusionsNode.append(newExclusionNode());
            return true;
        }

        return cleanupExclusions(exclusionsNode, this.logger);
    }

    private static String getGroupId(final Node dependencyNode, final String defaultValue) {
        return getTextInSingleInnerNode(dependencyNode, "groupId", "dependency", defaultValue);
    }

    private static String getArtifactId(final Node dependencyNode, final String defaultValue) {
        return getTextInSingleInnerNode(dependencyNode, "artifactId", "dependency", defaultValue);
    }

    private static String getClassifier(final Node dependencyNode, final String defaultValue) {
        return getTextInSingleInnerNode(dependencyNode, "classifier", "dependency", defaultValue);
    }

    private static String getVersion(final Node dependencyNode, final String defaultValue) {
        return getTextInSingleInnerNode(dependencyNode, "version", "dependency", defaultValue);
    }

    private static MavenScope getScope(final Node dependencyNode) {
        final String scope = getTextInSingleInnerNode(dependencyNode, "scope", "dependency", null);
        if (scope == null) {
            throw new GradleException("A <dependency> node has no <scope>.");
        }
        return MavenScope.of(scope);
    }

    private static boolean modifyTextInSingleInnerNodeIfDifferent(
            final Node node,
            final String key,
            final String parentName,
            final String prospectiveText,
            final Logger logger) {
        final Node innerNode = getSingleInnerNode(node, key, parentName);
        if (innerNode == null) {
            logger.lifecycle("      => Add <{}>: {}", key, prospectiveText);
            node.append(newTextNode(key, prospectiveText));
            return true;
        }

        final Object innerValueObject = innerNode.value();
        try {
            final String existingText;
            if (innerValueObject instanceof NodeList) {
                final NodeList innerValues = (NodeList) innerValueObject;
                if (innerValues.isEmpty()) {
                    logger.lifecycle("      => Add <{}>: {}", key, prospectiveText);
                    node.append(newTextNode(key, prospectiveText));
                    return true;
                }
                if (innerValues.size() > 1) {
                    throw new GradleException("<" + key + "> in <" + parentName + "> includes multiple values unexpectedly.");
                }
                existingText = (String) innerValues.get(0);
            } else if (innerValueObject instanceof CharSequence) {
                existingText = innerValueObject.toString();
            } else {
                existingText = (String) innerValueObject;  // throws ClassCastException
            }

            if (!prospectiveText.equals(existingText)) {
                logger.lifecycle("      => Update <{}>: {} => {}", key, existingText, prospectiveText);
                innerNode.setValue(prospectiveText);
                return true;
            }
            return false;
        } catch (final ClassCastException ex) {
            throw new GradleException("<" + key + "> in <" + parentName + "> includes an invalid child node unexpectedly.", ex);
        }
    }

    /**
     * Make {@code <exclusions>} node consist of only {@code <exclusion><groupId>*</groupId></exclusion>}.
     */
    private static boolean cleanupExclusions(final Node node, final Logger logger) {
        boolean modified = false;

        final Object exclusionNodesObject1 = node.get("exclusion");
        if (exclusionNodesObject1 instanceof NodeList) {
            final NodeList exclusionNodes = (NodeList) exclusionNodesObject1;
            if (exclusionNodes.size() >= 1) {
                // NOTE: NodeList's iterator does not support Iterator#remove, unfortunately. :(
                // https://issues.apache.org/jira/browse/GROOVY-1832
                // https://stackoverflow.com/questions/1374088/removing-dom-nodes-when-traversing-a-nodelist
                //
                // So much traditional way...
                for (int i = exclusionNodes.size() - 1; i >= 0; i--) {
                    final Node exclusionNode;
                    try {
                        exclusionNode = (Node) exclusionNodes.get(i);
                    } catch (final ClassCastException ex) {
                        throw new GradleException("<exclusion> in <exclusions> is not a Node.", ex);
                    }
                    if (!isExclusionForAny(exclusionNode)) {
                        modified = true;
                        logger.lifecycle("      => Remove {}", exclusionToString(exclusionNode));
                        node.remove(exclusionNode);
                    }
                }
            }

            // Even after some elements are removed from |exclusionNodes|,
            // the |exclusionNodes| object behaves like its element(s) were not removed
            // for example in |NodeList#isEmpty()|, |NodeList#size()|, and else.
            //
            // To work around this issue in Groovy's NodeList,
            // regetting <exclusion> nodes from <exclusions> **again** below.
        }

        final Object exclusionNodesObject2 = node.get("exclusion");
        if (exclusionNodesObject2 instanceof NodeList) {
            final NodeList exclusionNodes = (NodeList) exclusionNodesObject2;
            if (exclusionNodes.isEmpty()) {
                modified = true;
                logger.lifecycle("      => Add <exclusion><groupId>*</groupId></exclusion>");
                node.append(newExclusionNode());
            }
        }

        return modified;
    }

    private static boolean isExclusionForAny(final Node node) {
        final String groupId = getTextInSingleInnerNode(node, "groupId", "exclusion", null);
        if (!"*".equals(groupId)) {  // null is unaccepted.
            return false;
        }

        final String artifactId = getTextInSingleInnerNode(node, "artifactId", "exclusion", null);
        if (artifactId == null || "*".equals(artifactId)) {  // null is accepted.
            return true;
        }

        return false;
    }

    private static String exclusionToString(final Node node) {
        final StringBuilder builder = new StringBuilder();
        builder.append("<exclusion>");

        final String groupId = getTextInSingleInnerNode(node, "groupId", "exclusion", null);
        if (groupId != null) {
            builder.append("<groupId>");
            builder.append(groupId);
            builder.append("</groupId>");
        }

        final String artifactId = getTextInSingleInnerNode(node, "artifactId", "exclusion", null);
        if (artifactId != null) {
            builder.append("<artifactId>");
            builder.append(artifactId);
            builder.append("</artifactId>");
        }

        builder.append("</exclusion>");
        return builder.toString();
    }

    private static String getTextInSingleInnerNode(
            final Node node,
            final String key,
            final String parentName,
            final String defaultText) {
        final Node innerNode = getSingleInnerNode(node, key, parentName);
        if (innerNode == null) {
            return defaultText;
        }

        final Object innerValueObject = innerNode.value();
        try {
            if (innerValueObject instanceof NodeList) {
                final NodeList innerValues = (NodeList) innerValueObject;
                if (innerValues.isEmpty()) {
                    return defaultText;
                }
                if (innerValues.size() > 1) {
                    throw new GradleException("<" + key + "> in <" + parentName + "> includes multiple values unexpectedly.");
                }
                return (String) innerValues.get(0);
            } else if (innerValueObject instanceof String) {
                return (String) innerValueObject;
            }
            throw new GradleException("<" + key + "> in <" + parentName + "> includes an unexpected child node.");
        } catch (final ClassCastException ex) {
            throw new GradleException("<" + key + "> in <" + parentName + "> includes an invalid child node unexpectedly.", ex);
        }
    }

    private static Node getSingleInnerNode(final Node node, final String key, final String parentName) {
        final Object innerNodeObject = node.get(key);

        if (innerNodeObject instanceof NodeList) {
            final NodeList innerNodes = (NodeList) innerNodeObject;
            if (innerNodes.isEmpty()) {
                return null;
            }
            if (innerNodes.size() > 1) {
                throw new GradleException("<" + parentName + "> includes multiple <" + key + "> elements unexpectedly.");
            }
            return (Node) innerNodes.get(0);
        }

        try {
            return (Node) innerNodeObject;
        } catch (final ClassCastException ex) {
            throw new GradleException("<" + key + "> in <" + parentName + "> includes neither NodeList nor Node unexpectedly.", ex);
        }
    }

    private static Node newDependencyNode(final ScopedDependency dependency) {
        final VersionlessDependency versionless = dependency.getVersionlessDependency();
        final Node node = new Node(null, "dependency");
        node.appendNode("groupId", versionless.getGroup());
        node.appendNode("artifactId", versionless.getArtifactName());
        if (versionless.getClassifier() != null) {
            node.appendNode("classifier", versionless.getClassifier());
        }
        node.appendNode("version", dependency.getVersion());
        node.appendNode("scope", dependency.getScope().toMavenString());
        if (dependency.isOptional()) {
            node.appendNode("optional", true);
        }

        final Node exclusionsNode = node.appendNode("exclusions");
        exclusionsNode.append(newExclusionNode());

        return node;
    }

    private static Node newExclusionNode() {
        final Node exclusionNode = new Node(null, "exclusion");
        exclusionNode.appendNode("groupId", "*");
        return exclusionNode;
    }

    private static Node newTextNode(final String key, final String value) {
        return new Node(null, key, value);
    }

    // The primary target XML node.
    private final Node pom;
    private final Node dependencies;

    // Cached nodes, and cached representations of dependencies.
    private final NodeList dependenciesChildren;
    private final Map<VersionlessDependency, Node> nodeMap;  // map to find Nodes from ProspectiveDependencies

    // Operations reserved to be committed into the target XML node.
    private final ArrayList<Node> dependencyManagementsToRemove;
    private final LinkedHashMap<ScopedDependency, Node> providedDependenciesToInsert;  // key: logging, value: node to add
    private final LinkedHashMap<ScopedDependency, Node> existingDependenciesToModify;  // key: prospect, value: node to modify
    private final LinkedHashMap<ScopedDependency, Node> compileRuntimeDependenciesToAppend;  // key: logging, value: node to add
    private final LinkedHashMap<VersionlessDependency, Node> remainingDependenciesToOverride;  // key: logging, value: node to modify
    private final LinkedHashMap<ScopedDependency, Node> additionalDependencies;  // key: logging, value: node to add

    private boolean toCommit;
    private String prefixForLoggingAfterCommit;

    private final Logger logger;
}
