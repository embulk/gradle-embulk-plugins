/*
 * Copyright 2019 The Embulk project
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

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.LibraryBinaryIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.attributes.Usage;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.SoftwareComponent;
import org.gradle.api.logging.Logger;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

/**
 * A plugin for building Embulk plugins.
 *
 * <p>This implementation draws on Gradle's java-gradle-plugin plugin.
 *
 * @see <a href="https://github.com/gradle/gradle/blob/v5.5.1/subprojects/plugin-development/src/main/java/org/gradle/plugin/devel/plugins/JavaGradlePluginPlugin.java">JavaGradlePluginPlugin</a>
 */
public class EmbulkPluginsPlugin implements Plugin<Project> {
    @Override
    public void apply(final Project project) {
        // The "java" plugin is required to be applied so that the "runtime" configuration will be available.
        project.getPluginManager().apply(JavaPlugin.class);

        createExtension(project);

        project.getTasks().create("gem", Gem.class);
        project.getTasks().create("gemPush", GemPush.class);

        final Configuration runtimeConfiguration = project.getConfigurations().getByName("runtime");

        project.afterEvaluate(projectAfterEvaluate -> {
            initializeAfterEvaluate(projectAfterEvaluate, runtimeConfiguration);
        });
    }

    /**
     * Creates an extension of "embulkPlugin { ... }".
     *
     * <p>This method does not return the instance of {@code EmbulkPluginExtension} created because
     * it does not contain meaningful information before evaluate.
     */
    private static void createExtension(final Project project) {
        project.getExtensions().create(
                "embulkPlugin",
                EmbulkPluginExtension.class,
                project);
    }

    private static void initializeAfterEvaluate(
            final Project project,
            final Configuration runtimeConfiguration) {
        final EmbulkPluginExtension extension = project.getExtensions().getByType(EmbulkPluginExtension.class);

        extension.checkValidity();

        // TODO: Reconsider a possibility that it can be a detached configuration.
        // It must have been a non-detached configuration to be mapped into Maven scopes by Conf2ScopeMapping,
        // but we no longer support Conf2ScopeMapping with uploadArchives.
        final Configuration alternativeRuntimeConfiguration = project.getConfigurations().maybeCreate("embulkPluginRuntime");

        configureAlternativeRuntimeBasics(alternativeRuntimeConfiguration, project.getObjects());

        // Dependencies of "embulkPluginRuntime" will be set only when "mainJar" is not configured.
        // If "mainJar" is set (ex. to "shadowJar"), developers need to configure "embulkPluginRuntime" by themselves.
        if (!extension.getMainJar().isPresent()) {
            configureAlternativeRuntimeDependencies(project, runtimeConfiguration, alternativeRuntimeConfiguration);
        }

        configureComponentsJava(project, alternativeRuntimeConfiguration);

        configureJarTask(project, extension);

        warnIfRuntimeHasCompileOnlyDependencies(project, alternativeRuntimeConfiguration);

        configureGemTasks(project, extension, alternativeRuntimeConfiguration);

        // Configuration#getResolvedConfiguration here so that the dependency lock state is checked.
        alternativeRuntimeConfiguration.getResolvedConfiguration();
    }

    /**
     * Configures the basics of the alternative (flattened) runtime configuration.
     */
    private static void configureAlternativeRuntimeBasics(
            final Configuration alternativeRuntimeConfiguration,
            final ObjectFactory objectFactory) {
        // The "embulkPluginRuntime" configuration has dependency locking activated by default.
        // https://docs.gradle.org/current/userguide/dependency_locking.html
        alternativeRuntimeConfiguration.getResolutionStrategy().activateDependencyLocking();

        // The "embulkPluginRuntime" configuration do not need to be transitive, and must be non-transitive.
        //
        // It contains all transitive dependencies of "runtime" flattened. It does not need to be transitive, then.
        // Moreover, setting it transitive troubles the warning shown by |warnIfRuntimeHasCompileOnlyDependencies|.
        //
        // The Embulk plugin developer may explicitly exclude some transitive dependencies as below :
        //
        //   dependencies {
        //       compile("org.glassfish.jersey.core:jersey-client:2.25.1") {
        //           exclude group: "javax.inject", module: "javax.inject"
        //       }
        //   }
        //
        // If "embulkPluginRuntime" is still transitive, it would finally contain "javax.inject:javax.inject".
        // The behavior is unintended. So, "embulkPluginRuntime" must be non-transitive.
        alternativeRuntimeConfiguration.setTransitive(false);

        // Since Gradle 6.0.1, all variants for components need to have at least one Attribute.
        // https://github.com/gradle/gradle/issues/11700
        //
        // Rather than setting a random Attribute, the Usage attribute would be the most reasonable to set.
        // https://docs.gradle.org/6.1.1/userguide/variant_attributes.html#sec:standard_attributes
        alternativeRuntimeConfiguration.attributes(attributes -> {
            attributes.attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, Usage.JAVA_RUNTIME));
        });
    }

    /**
     * Configures the alternative (flattened) runtime configuration with flattened dependencies.
     */
    private static void configureAlternativeRuntimeDependencies(
            final Project project,
            final Configuration runtimeConfiguration,
            final Configuration alternativeRuntimeConfiguration) {
        final Logger logger = project.getLogger();

        alternativeRuntimeConfiguration.withDependencies(dependencies -> {
            final Map<String, ResolvedDependency> allDependencies = new HashMap<>();
            final Set<ResolvedDependency> firstLevelDependencies =
                    runtimeConfiguration.getResolvedConfiguration().getFirstLevelModuleDependencies();
            for (final ResolvedDependency dependency : firstLevelDependencies) {
                recurseAllDependencies(dependency, allDependencies, project);
            }

            for (final ResolvedDependency dependency : allDependencies.values()) {
                final String dependencyConfiguration = dependency.getConfiguration();
                final Set<ResolvedArtifact> moduleArtifacts = dependency.getModuleArtifacts();

                final HashSet<String> types = new HashSet<>();
                final HashSet<ProjectComponentIdentifier> projects = new HashSet<>();
                for (final ResolvedArtifact moduleArtifact : moduleArtifacts) {
                    final ComponentIdentifier componentIdentifier = moduleArtifact.getId().getComponentIdentifier();
                    if (componentIdentifier instanceof ProjectComponentIdentifier) {
                        // Project: such as `compile project(":subproject")`
                        types.add("project");
                        projects.add((ProjectComponentIdentifier) componentIdentifier);
                    } else if (componentIdentifier instanceof ModuleComponentIdentifier) {
                        // Other Java library: such as `compile "com.google.guava:guava:20.0"`
                        types.add("module");
                    } else if (componentIdentifier instanceof LibraryBinaryIdentifier) {
                        // Native library (?): not very sure
                        logger.warn("Library module artifact type: \"" + moduleArtifact.toString() + "\"");
                        types.add("library");
                    } else {
                        logger.warn("Unknown module artifact type: \"" + moduleArtifact.toString() + "\"");
                        types.add("other");
                    }
                }
                if (types.size() > 1) {
                    throw new GradleException(
                            "Multiple types (" + types.stream().collect(Collectors.joining(", ")) + ") of module artifacts"
                            + " are found in dependency: " + dependency.toString());
                }

                if (types.contains("project")) {
                    if (projects.size() > 1) {
                        throw new GradleException("Multiple projects are found in dependency: " + dependency.toString());
                    }
                    if (projects.isEmpty()) {
                        throw new IllegalStateException(
                                "Internal error which must not happen: projects is empty while tasks has \"project\".");
                    }
                    final ProjectComponentIdentifier projectIdentifier = projects.iterator().next();

                    final HashMap<String, String> notation = new HashMap<>();
                    notation.put("path", projectIdentifier.getProjectPath());
                    // TODO: Find a better way to decide whether to add "configuration" or not.
                    if (dependencyConfiguration != null && !dependencyConfiguration.isEmpty()) {
                        notation.put("configuration", dependencyConfiguration);
                    }
                    dependencies.add(project.getDependencies().create(project.getDependencies().project(notation)));
                } else if (types.contains("module")) {
                    final HashMap<String, String> notation = new HashMap<>();
                    notation.put("group", dependency.getModuleGroup());
                    notation.put("name", dependency.getModuleName());
                    notation.put("version", dependency.getModuleVersion());
                    dependencies.add(project.getDependencies().create(notation));
                }
            }
        });
    }

    /**
     * Configures "components.java" (SoftwareComponent used for "from components.java" in MavenPublication)
     * to include "embulkPluginRuntime" as the "runtime" scope of Maven.
     *
     * https://docs.gradle.org/6.1.1/userguide/publishing_customization.html#sec:adding-variants-to-existing-components
     * https://docs.gradle.org/6.1.1/dsl/org.gradle.api.publish.maven.MavenPublication.html#N1BF06
     * https://github.com/gradle/gradle/blob/v6.1.1/subprojects/plugins/src/main/java/org/gradle/api/plugins/JavaPlugin.java#L358-L365
     *
     * This SoftwareComponent configuration is used to in maven-publish.
     * https://github.com/gradle/gradle/blob/v6.1.1/subprojects/maven/src/main/java/org/gradle/api/publish/maven/internal/publication/DefaultMavenPublication.java#L382-L390
     * https://github.com/gradle/gradle/blob/v6.1.1/subprojects/maven/src/main/java/org/gradle/api/publish/maven/internal/publication/DefaultMavenPublication.java#L274-L286
     * https://github.com/gradle/gradle/blob/v6.1.1/subprojects/maven/src/main/java/org/gradle/api/publish/maven/internal/publication/DefaultMavenPublication.java#L392-L412
     */
    private static void configureComponentsJava(
            final Project project,
            final Configuration alternativeRuntimeConfiguration) {
        final SoftwareComponent component = project.getComponents().getByName("java");
        if (component instanceof AdhocComponentWithVariants) {
            ((AdhocComponentWithVariants) component).addVariantsFromConfiguration(alternativeRuntimeConfiguration, details -> {
                details.mapToMavenScope("runtime");
            });
        } else {
            throw new GradleException("Failed to configure components.java because it is not AdhocComponentWithVariants.");
        }
    }

    /**
     * Configures the standard {@code "jar"} task with required MANIFEST.
     */
    private static void configureJarTask(final Project project, final EmbulkPluginExtension extension) {
        final String mainJarTaskName;
        if (extension.getMainJar().isPresent()) {
            mainJarTaskName = extension.getMainJar().get();
        } else {
            mainJarTaskName = "jar";
        }
        project.getTasks().named(mainJarTaskName, Jar.class, jarTask -> {
            jarTask.manifest(UpdateManifestAction.builder()
                             .add("Embulk-Plugin-Main-Class", extension.getMainClass().get())
                             .add("Embulk-Plugin-Category", extension.getCategory().get())
                             .add("Embulk-Plugin-Type", extension.getType().get())
                             .add("Embulk-Plugin-Spi-Version", "0")
                             .add("Implementation-Title", project.getName())
                             .add("Implementation-Version", project.getVersion().toString())
                             .build());
        });
    }

    private static void warnIfRuntimeHasCompileOnlyDependencies(
            final Project project,
            final Configuration alternativeRuntimeConfiguration) {
        final Configuration compileOnlyConfiguration = project.getConfigurations().getByName("compileOnly");

        final Map<String, ResolvedDependency> compileOnlyDependencies =
                collectAllDependencies(compileOnlyConfiguration, project);
        final Map<String, ResolvedDependency> alternativeRuntimeDependencies =
                collectAllDependencies(alternativeRuntimeConfiguration, project);

        final Set<String> intersects = new HashSet<>();
        intersects.addAll(alternativeRuntimeDependencies.keySet());
        intersects.retainAll(compileOnlyDependencies.keySet());
        if (!intersects.isEmpty()) {
            final Logger logger = project.getLogger();

            // Logging in the "error" loglevel to show the severity, but not to fail with GradleException.
            logger.error(
                    "============================================ WARNING ============================================\n"
                    + "Following \"runtime\" dependencies are included also in \"compileOnly\" dependencies.\n"
                    + "\n"
                    + intersects.stream().map(key -> {
                        final ResolvedDependency dependency = alternativeRuntimeDependencies.get(key);
                        return "  \"" + dependency.getModule().toString() + "\"\n";
                    }).collect(Collectors.joining(""))
                    + "\n"
                    + "  \"compileOnly\" dependencies are used to represent Embulk's core to be \"provided\" at runtime.\n"
                    + "  They should be excluded from \"compile\" or \"runtime\" dependencies like the example below.\n"
                    + "\n"
                    + "  dependencies {\n"
                    + "    compile(\"org.glassfish.jersey.core:jersey-client:2.25.1\") {\n"
                    + "      exclude group: \"javax.inject\", module: \"javax.inject\"\n"
                    + "    }\n"
                    + "  }\n"
                    + "=================================================================================================");
        }
    }

    private static void configureGemTasks(
            final Project project,
            final EmbulkPluginExtension extension,
            final Configuration alternativeRuntimeConfiguration) {
        final TaskProvider<Gem> gemTask = project.getTasks().named("gem", Gem.class, task -> {
            final String mainJarTaskName;
            if (extension.getMainJar().isPresent()) {
                mainJarTaskName = extension.getMainJar().get();
            } else {
                mainJarTaskName = "jar";
            }
            task.dependsOn(mainJarTaskName);

            task.setEmbulkPluginMainClass(extension.getMainClass().get());
            task.setEmbulkPluginCategory(extension.getCategory().get());
            task.setEmbulkPluginType(extension.getType().get());

            if ((!task.getArchiveBaseName().isPresent())) {
                // project.getName() never returns null.
                // https://docs.gradle.org/5.5.1/javadoc/org/gradle/api/Project.html#getName--
                task.getArchiveBaseName().set(project.getName());
            }
            if ((!task.getArchiveClassifier().isPresent()) || task.getArchiveClassifier().get().isEmpty()) {
                task.getArchiveClassifier().set("java");
            }
            // summary is kept empty -- mandatory.
            if ((!task.getArchiveVersion().isPresent()) && (!project.getVersion().toString().equals("unspecified"))) {
                // project.getVersion() never returns null.
                // https://docs.gradle.org/5.5.1/javadoc/org/gradle/api/Project.html#getVersion--
                task.getArchiveVersion().set(buildGemVersionFromMavenVersion(project.getVersion().toString()));
            }

            task.getDestinationDirectory().set(((File) project.property("buildDir")).toPath().resolve("gems").toFile());
            task.from(alternativeRuntimeConfiguration, copySpec -> {
                copySpec.into("classpath");
            });
            task.from(((Jar) project.getTasks().getByName(mainJarTaskName)).getArchiveFile(), copySpec -> {
                copySpec.into("classpath");
            });
        });

        project.getTasks().named("gemPush", GemPush.class, task -> {
            task.dependsOn("gem");
            if (!task.getGem().isPresent()) {
                task.getGem().set(gemTask.get().getArchiveFile());
            }
        });
    }

    private static String buildGemVersionFromMavenVersion(final String mavenVersion) {
        if (mavenVersion.contains("-")) {
            final List<String> versionTokens = Arrays.asList(mavenVersion.split("-"));
            if (versionTokens.size() != 2) {
                throw new GradleException("Failed to convert the version \"" + mavenVersion + "\" to Gem-style.");
            }
            return versionTokens.get(0) + '.' + versionTokens.get(1).toLowerCase();
        } else {
            return mavenVersion;
        }
    }

    private static Map<String, ResolvedDependency> collectAllDependencies(
            final Configuration configuration, final Project project) {
        final HashMap<String, ResolvedDependency> allDependencies = new HashMap<>();

        final Set<ResolvedDependency> firstLevel = configuration.getResolvedConfiguration().getFirstLevelModuleDependencies();
        for (final ResolvedDependency dependency : firstLevel) {
            recurseAllDependencies(dependency, allDependencies, project);
        }

        return Collections.unmodifiableMap(allDependencies);
    }

    /**
     * Traverses the dependency tree recursively to list all the dependencies.
     */
    private static void recurseAllDependencies(
            final ResolvedDependency dependency,
            final Map<String, ResolvedDependency> allDependencies,
            final Project project) {
        final String key = dependency.getModuleGroup() + ":" + dependency.getModuleName();
        if (allDependencies.containsKey(key)) {
            final ResolvedDependency found = allDependencies.get(key);
            if (!found.equals(dependency)) {
                project.getLogger().warn(String.format(
                        "ResolvedDependency conflicts: \"%s\" : \"%s\"", found.toString(), dependency.toString()));
            }
            return;
        }
        allDependencies.put(key, dependency);
        if (dependency.getChildren().size() > 0) {
            for (final ResolvedDependency child : dependency.getChildren()) {
                recurseAllDependencies(child, allDependencies, project);
            }
        }
    }

    static final String DEFAULT_JRUBY = "org.jruby:jruby-complete:9.2.7.0";
}
