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
 * See the License for the specific language governing permissions anda
 * limitations under the License.
 */

package org.embulk.gradle.embulk_plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.LibraryBinaryIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.logging.Logger;

/**
 * A prospective set of dependencies that {@code pom.xml} should finally morph into.
 */
final class ProspectiveDependencies implements Iterable<ScopedDependency> {
    private ProspectiveDependencies(final LinkedHashMap<VersionlessDependency, VersionScope> dependencies) {
        this.dependencies = Collections.unmodifiableMap(dependencies);

        final ArrayList<ScopedDependency> scopedDependencies = new ArrayList<>();
        for (final Map.Entry<VersionlessDependency, VersionScope> entry : dependencies.entrySet()) {
            scopedDependencies.add(ScopedDependency.of(entry.getKey(), entry.getValue().getVersion(), entry.getValue().getScope()));
        }
        this.scopedDependencies = Collections.unmodifiableList(scopedDependencies);
    }

    /**
     * Builds a set of dependencies from {@code compileClasspath} and {@code runtimeClasspath}.
     *
     * <p>If a dependency is :
     *
     * <ul>
     * <li>Included both in {@code compileClasspath} and {@code runtimeClasspath}: {@code compile} scope in Maven
     * <li>Included only in {@code runtimeClasspath}, not in {@code compileClasspath}: {@code runtime} scope in Maven
     * <li>Included only in {@code compileClasspath}, not in {@code runtimeClasspath}: {@code provided} scope in Maven (compileOnly)
     * </ul>
     */
    static ProspectiveDependencies build(
            final Set<ResolvedArtifact> compileClasspath,
            final Set<ResolvedArtifact> runtimeClasspath,
            final Logger logger) {
        logger.lifecycle("compileClasspath:");
        for (final ResolvedArtifact compileArtifact : compileClasspath) {
            logger.lifecycle(
                    "    => {}:{}{}:{}",
                    compileArtifact.getModuleVersion().getId().getGroup(),
                    compileArtifact.getModuleVersion().getId().getName(),
                    compileArtifact.getClassifier() == null ? "" : (":" + compileArtifact.getClassifier()),
                    compileArtifact.getModuleVersion().getId().getVersion());
        }
        logger.lifecycle("");
        logger.lifecycle("runtimeClasspath:");
        for (final ResolvedArtifact runtimeArtifact : runtimeClasspath) {
            logger.lifecycle(
                    "    => {}:{}{}:{}",
                    runtimeArtifact.getModuleVersion().getId().getGroup(),
                    runtimeArtifact.getModuleVersion().getId().getName(),
                    runtimeArtifact.getClassifier() == null ? "" : (":" + runtimeArtifact.getClassifier()),
                    runtimeArtifact.getModuleVersion().getId().getVersion());
        }
        logger.lifecycle("");
        return new Builder().addCompileClasspath(compileClasspath).addRuntimeClasspath(runtimeClasspath).build();
    }

    /**
     * Builds a set of dependencies from version maps.
     *
     * <p>A version map mean {@code Map<VersionlessDependency, String>}, whose key is a tuple of
     * group, module (artifact name), and classifier, and whose value is a version.
     *
     * <pre>{@code {
     *   {"org.embulk", "embulk-spi", null} : "0.10.35",
     *   {"org.msgpack", "msgpack-core", null} : "0.8.24",
     *   {"com.github.jnr", "jffi", null} : "1.2.23",
     *   {"com.github.jnr", "jffi", "native"} : "1.2.23",
     *   {"org.apache.commons", "commons-text", null} : "1.7"
     * }}</pre>
     */
    private static ProspectiveDependencies buildFromVersionMaps(
            final LinkedHashMap<VersionlessDependency, String> compileVersionMap,
            final LinkedHashMap<VersionlessDependency, String> runtimeVersionMap) {
        final LinkedHashMap<VersionlessDependency, String> compileOnlyVersionMap =
                calculateCompileOnly(compileVersionMap, runtimeVersionMap);
        final LinkedHashMap<VersionlessDependency, String> overlappingVersionMap =
                calculateOverlapping(compileVersionMap, runtimeVersionMap);
        final LinkedHashMap<VersionlessDependency, String> runtimeOnlyVersionMap =
                calculateRuntimeOnly(compileVersionMap, runtimeVersionMap);

        final LinkedHashMap<VersionlessDependency, VersionScope> prospectiveDependencies = new LinkedHashMap<>();
        for (final Map.Entry<VersionlessDependency, String> compileOnly : compileOnlyVersionMap.entrySet()) {
            if (null != prospectiveDependencies.put(compileOnly.getKey(), VersionScope.provided(compileOnly.getValue()))) {
                throw new GradleException("A tuple of [" + compileOnly.getKey().toString() + "] is duplicated unexpectedly.");
            }
        }
        for (final Map.Entry<VersionlessDependency, String> overlapping : overlappingVersionMap.entrySet()) {
            if (null != prospectiveDependencies.put(overlapping.getKey(), VersionScope.compile(overlapping.getValue()))) {
                throw new GradleException("A tuple of [" + overlapping.getKey().toString() + "] is duplicated unexpectedly.");
            }
        }
        for (final Map.Entry<VersionlessDependency, String> runtimeOnly : runtimeOnlyVersionMap.entrySet()) {
            if (null != prospectiveDependencies.put(runtimeOnly.getKey(), VersionScope.runtime(runtimeOnly.getValue()))) {
                throw new GradleException("A tuple of [" + runtimeOnly.getKey().toString() + "] is duplicated unexpectedly.");
            }
        }
        return new ProspectiveDependencies(prospectiveDependencies);
    }

    @Override
    public Iterator<ScopedDependency> iterator() {
        return this.scopedDependencies.iterator();
    }

    @Override
    public String toString() {
        return this.dependencies.toString();
    }

    private static class Builder {
        Builder() {
            this.compileVersionMap = null;
            this.runtimeVersionMap = null;
            this.compileException = null;
            this.runtimeException = null;
        }

        Builder addCompileClasspath(final Set<ResolvedArtifact> compileClasspath) {
            if (this.compileVersionMap != null || this.compileException != null) {
                throw new IllegalStateException(
                        "ProspectiveDependencies.Builder.addCompileClasspath is called twice unexpectedly.");
            }
            try {
                this.compileVersionMap = buildVersionMapFromResolvedArtifacts(compileClasspath);
            } catch (final UnexpectedDependencyException ex) {
                this.compileException = ex;
            }
            return this;
        }

        Builder addRuntimeClasspath(final Set<ResolvedArtifact> runtimeClasspath) {
            if (this.runtimeVersionMap != null || this.runtimeException != null) {
                throw new IllegalStateException(
                        "ProspectiveDependencies.Builder.addRuntimeClasspath is called twice unexpectedly.");
            }
            try {
                this.runtimeVersionMap = buildVersionMapFromResolvedArtifacts(runtimeClasspath);
            } catch (final UnexpectedDependencyException ex) {
                this.runtimeException = ex;
            }
            return this;
        }

        ProspectiveDependencies build() throws GradleException {
            if (this.compileException != null || this.runtimeException != null) {
                throw new GradleException();  // TODO:
            }
            if (this.compileVersionMap == null || this.runtimeVersionMap == null) {
                throw new NullPointerException(
                        "ProspectiveDependencies is built without compileClasspath or runtimeClasspath unexpectedly.");
            }
            return ProspectiveDependencies.buildFromVersionMaps(this.compileVersionMap, this.runtimeVersionMap);
        }

        private LinkedHashMap<VersionlessDependency, String> compileVersionMap;
        private LinkedHashMap<VersionlessDependency, String> runtimeVersionMap;

        private UnexpectedDependencyException compileException;
        private UnexpectedDependencyException runtimeException;
    }

    private static class UnexpectedDependencyException extends Exception {
        UnexpectedDependencyException(
                final LinkedHashSet<VersionlessDependency> duplicates,
                final ArrayList<LibraryBinaryIdentifier> libs,
                final ArrayList<ComponentIdentifier> others) {
            super(buildMessage(duplicates, libs, others));
            this.duplicates = duplicates;
            this.libs = libs;
            this.others = others;
        }

        private static String buildMessage(
                final LinkedHashSet<VersionlessDependency> duplicates,
                final ArrayList<LibraryBinaryIdentifier> libs,
                final ArrayList<ComponentIdentifier> others) {
            final StringBuilder exceptionMessage = new StringBuilder();

            if (!duplicates.isEmpty()) {
                exceptionMessage.append("Dependency duplicates are found: ");
                exceptionMessage.append(
                        duplicates.stream().map(VersionlessDependency::toString).collect(Collectors.joining(", ", "[", "]")));
            }

            if (!libs.isEmpty()) {
                if (exceptionMessage.length() > 0) {
                    exceptionMessage.append(" ");
                }
                exceptionMessage.append("Native library binary dependencies are not supported: ");
                exceptionMessage.append(
                        libs.stream().map(LibraryBinaryIdentifier::getDisplayName).collect(Collectors.joining(", ", "[", "]")));
            }

            if (!others.isEmpty()) {
                if (exceptionMessage.length() > 0) {
                    exceptionMessage.append(" ");
                }
                exceptionMessage.append("Unknown type of artifacts: ");
                exceptionMessage.append(
                        others.stream().map(ComponentIdentifier::getDisplayName).collect(Collectors.joining(", ", "[", "]")));
            }

            return exceptionMessage.toString();
        }

        private final LinkedHashSet<VersionlessDependency> duplicates;
        private final ArrayList<LibraryBinaryIdentifier> libs;
        private final ArrayList<ComponentIdentifier> others;
    }

    private static LinkedHashMap<VersionlessDependency, String> buildVersionMapFromResolvedArtifacts(
            final Set<ResolvedArtifact> artifacts)
            throws UnexpectedDependencyException {
        final LinkedHashMap<VersionlessDependency, String> versionMap = new LinkedHashMap<>();

        final LinkedHashSet<VersionlessDependency> duplicates = new LinkedHashSet<>();
        final ArrayList<LibraryBinaryIdentifier> libs = new ArrayList<>();
        final ArrayList<ComponentIdentifier> others = new ArrayList<>();

        for (final ResolvedArtifact artifact : artifacts) {
            final ComponentIdentifier componentIdentifier = artifact.getId().getComponentIdentifier();

            if (componentIdentifier instanceof ProjectComponentIdentifier) {
                // @@@@
            } else if (componentIdentifier instanceof ModuleComponentIdentifier) {
                final ModuleComponentIdentifier moduleIdentifier = (ModuleComponentIdentifier) componentIdentifier;
                final VersionlessDependency module = VersionlessDependency.fromModule(moduleIdentifier, artifact);
                if (versionMap.containsKey(module)) {
                    duplicates.add(module);
                } else {
                    versionMap.put(module, moduleIdentifier.getVersion());
                }
            } else if (componentIdentifier instanceof LibraryBinaryIdentifier) {
                libs.add((LibraryBinaryIdentifier) componentIdentifier);
            } else {
                others.add(componentIdentifier);
            }
        }

        if ((!duplicates.isEmpty()) || (!libs.isEmpty()) || (!others.isEmpty())) {
            throw new UnexpectedDependencyException(duplicates, libs, others);
        }

        return versionMap;
    }

    private static final class VersionScope {
        private VersionScope(final String version, final MavenScope scope) {
            this.version = version;
            this.scope = scope;
        }

        static VersionScope compile(final String version) {
            return new VersionScope(version, MavenScope.COMPILE);
        }

        static VersionScope runtime(final String version) {
            return new VersionScope(version, MavenScope.RUNTIME);
        }

        static VersionScope provided(final String version) {
            return new VersionScope(version, MavenScope.PROVIDED);
        }

        String getVersion() {
            return this.version;
        }

        MavenScope getScope() {
            return this.scope;
        }

        @Override
        public String toString() {
            return this.version + "@" + this.scope.toString();
        }

        private final String version;
        private final MavenScope scope;
    }

    private static LinkedHashMap<VersionlessDependency, String> calculateCompileOnly(
            final Map<VersionlessDependency, String> compileVersionMap,
            final Map<VersionlessDependency, String> runtimeVersionMap) {
        final LinkedHashMap<VersionlessDependency, String> compileOnlyMap = new LinkedHashMap<>(compileVersionMap);
        compileOnlyMap.keySet().removeAll(runtimeVersionMap.keySet());
        return compileOnlyMap;
    }

    private static LinkedHashMap<VersionlessDependency, String> calculateRuntimeOnly(
            final Map<VersionlessDependency, String> compileVersionMap,
            final Map<VersionlessDependency, String> runtimeVersionMap) {
        final LinkedHashMap<VersionlessDependency, String> runtimeOnlyMap = new LinkedHashMap<>(runtimeVersionMap);
        runtimeOnlyMap.keySet().removeAll(compileVersionMap.keySet());
        return runtimeOnlyMap;
    }

    private static LinkedHashMap<VersionlessDependency, String> calculateOverlapping(
            final Map<VersionlessDependency, String> compileVersionMap,
            final Map<VersionlessDependency, String> runtimeVersionMap) {
        final LinkedHashMap<VersionlessDependency, String> overlappingMap = new LinkedHashMap<>(compileVersionMap);
        // TODO: Check duplication with version mismatch.
        overlappingMap.keySet().retainAll(runtimeVersionMap.keySet());
        return overlappingMap;
    }

    private Map<VersionlessDependency, String> extract(final MavenScope scope) {
        final LinkedHashMap<VersionlessDependency, String> extracted = new LinkedHashMap<>();
        for (final Map.Entry<VersionlessDependency, VersionScope> dependency : this.dependencies.entrySet()) {
            if (scope == dependency.getValue().getScope()) {
                extracted.put(dependency.getKey(), dependency.getValue().getVersion());
            }
        }
        return Collections.unmodifiableMap(extracted);
    }

    String toStringForLogging() {
        final StringBuilder builder = new StringBuilder();
        builder.append("\n");
        for (final Map.Entry<VersionlessDependency, VersionScope> dependency : this.dependencies.entrySet()) {
            builder.append("    => ");
            builder.append(dependency.getKey().toString(
                    dependency.getValue().getVersion(),
                    dependency.getValue().getScope()));
            builder.append("\n");
        }
        builder.append("");
        return builder.toString();

    }

    // The primary data of dependencies.
    private final Map<VersionlessDependency, VersionScope> dependencies;

    // A cached representation of dependencies.
    private final List<ScopedDependency> scopedDependencies;
}
