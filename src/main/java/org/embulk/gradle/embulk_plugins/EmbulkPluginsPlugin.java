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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.DependencyArtifact;
import org.gradle.api.artifacts.ExternalModuleDependency;
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
import org.gradle.api.provider.ListProperty;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.gradle.api.publish.tasks.GenerateModuleMetadata;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

/**
 * A Gradle plugin for building Embulk plugins.
 *
 * <p>This Gradle plugin has two main purposes to satisfy two requirements as an Embulk plugin.
 *
 * <p>One of the requirements is to get Embulk plugin's {@code pom.xml} to include all dependencies
 * as the direct first-level dependencies without any transitive dependency. This is an important
 * restriction to keep dependencies consistent between plugin development and Embulk's runtime.
 * (Indeed, Embulk's {@code PluginClassLoader} is implemented for Maven-based plugins to load only
 * the direct first-level dependencies without any transitive dependency.)
 *
 * <p>The other requirement is to add some required attributes in {@code MANIFEST.MF}.
 *
 * <p>In addition, this Gradle plugin provides some support for publishing RubyGems-based plugins.
 *
 * <p>This Gradle plugin depends on Gradle's "java-plugin" and "maven-publish-plugin".
 */
public class EmbulkPluginsPlugin implements Plugin<Project> {
    @Override
    public void apply(final Project project) {
        // The "java" plugin is applied automatically here because :
        // * An Embulk plugin is naturally expected to be a Java project.
        // * This Gradle plugin needs "compileClasspath" and "runtimeClasspath" configurations available.
        project.getPluginManager().apply(JavaPlugin.class);

        // The "maven-publish" plugin is applied automatically here because :
        // * An Embulk plugin needs its POM (pom.xml) to be manipulated.
        // * This Gradle plugin needs to interfere "publishing" to manipulate POM.
        //
        // It can be disabled in the future when the POM manipulation is realized by tweaking Gradle's "dependencies",
        // not just at the "publishing".
        project.getPluginManager().apply(MavenPublishPlugin.class);

        createExtension(project);

        project.getTasks().create("gem", Gem.class);
        project.getTasks().create("gemPush", GemPush.class);

        final Configuration compileClasspath = project.getConfigurations().getByName("compileClasspath");
        final Configuration runtimeClasspath = project.getConfigurations().getByName("runtimeClasspath");

        project.afterEvaluate(projectAfterEvaluate -> {
            final EmbulkPluginExtension extension = project.getExtensions().getByType(EmbulkPluginExtension.class);
            extension.checkValidity();

            if (extension.getDirectPomManipulation().getOrElse(false)) {
                initializeForPomModifications(projectAfterEvaluate, extension, compileClasspath, runtimeClasspath);
            } else {
                final Configuration runtimeConfiguration = project.getConfigurations().getByName("runtime");
                initializeForAlternativeRuntimeConfiguration(projectAfterEvaluate, extension, runtimeConfiguration);
            }
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

    private static void initializeForPomModifications(
            final Project project,
            final EmbulkPluginExtension extension,
            final Configuration compileClasspath,
            final Configuration runtimeClasspath) {
        if (extension.getMainJar().isPresent()) {
            throw new GradleException(
                    "mainJar is not supported with direct pom.xml manipulation."
                    + " Contact at https://github.com/embulk/gradle-embulk-plugins/issues for your case.");
        }

        if (!extension.getGeneratesModuleMetadata().getOrElse(false)) {
            project.getTasks().withType(GenerateModuleMetadata.class, configureGenerateModuleMetadata -> {
                configureGenerateModuleMetadata.setEnabled(false);
            });
        }

        // The "compileClasspath" and "runtimeClasspath" configurations have dependency locking activated by default.
        // https://docs.gradle.org/current/userguide/dependency_locking.html
        compileClasspath.getResolutionStrategy().activateDependencyLocking();
        runtimeClasspath.getResolutionStrategy().activateDependencyLocking();

        configureJarTask(project, extension);

        final PublishingExtension publishing = getPublishingExtension(project);

        publishing.getPublications().withType(MavenPublication.class, configureMavenPublication -> {
            // Embulk plugin's pom.xml should be on "Resolved versions", not "Declared versions".
            // https://docs.gradle.org/6.4.1/userguide/publishing_maven.html#publishing_maven:resolved_dependencies
            //
            // It is to satisfy Embulk plugin's requirement for pom.xml to include all dependencies
            // as the direct first-level dependencies without any transitive dependency.
            configureMavenPublication.versionMapping(configureVersionMappingStrategy -> {
                configureVersionMappingStrategy.usage("java-api", configureVariantVersionMappingStrategy -> {
                    configureVariantVersionMappingStrategy.fromResolutionOf(runtimeClasspath);
                });
                configureVersionMappingStrategy.usage("java-runtime", configureVariantVersionMappingStrategy -> {
                    configureVariantVersionMappingStrategy.fromResolutionResult();
                });
            });

            if (project.getRepositories().isEmpty()) {
                throw new GradleException(
                        "No \"repositories {}\" are declared. Set at least one repository."
                        + " For example: \"repositories { mavenCentral() }\"");
            }

            // NOTE: This "Action<? super XmlProvider> action" runs only when corresponding pom.xml is generated.
            //
            // In other words, this action does not run only when "./gradlew compileJava".
            // It runs only when "./gradlew generatePomFileFor..." or "./gradlew publish..." runs.
            //
            // @see https://docs.gradle.org/6.4.1/javadoc/org/gradle/api/publish/maven/MavenPom.html#withXml-org.gradle.api.Action-
            //
            // """
            // Each action/closure passed to this method will be stored as a callback, and executed
            // when the publication that this descriptor is attached to is published.
            // """
            configureMavenPublication.getPom().withXml(configureXml -> {
                final Logger logger = project.getLogger();

                final ProspectiveDependencies prospectiveDependencies = ProspectiveDependencies.build(
                        compileClasspath.getResolvedConfiguration().getResolvedArtifacts(),
                        runtimeClasspath.getResolvedConfiguration().getResolvedArtifacts(),
                        logger);

                // TODO: Use XmlProvider#asElement (org.w3c.dom.Element) instead of XmlProvider#asNode (groovy.util.Node).
                // https://docs.gradle.org/6.4.1/javadoc/org/gradle/api/XmlProvider.html
                //
                // groovy.util.Node is Groovy's. Gradle is migrating to Kotlin, instead of Groovy.
                // There is a risk that XmlProvider#asNode (groovy.util.Node) is deprecated / removed in the future.
                //
                // But, XmlProvider does not have a good way to create a new org.w3c.dom.Element without org.w3c.dom.Document.
                try (final DependenciesNodeManipulator xml = DependenciesNodeManipulator.of(configureXml.asNode(), logger)) {
                    xml.logDependencies("<dependencies> in pom.xml before manipulation:");
                    xml.assertScopes();

                    logger.lifecycle(
                            "<dependencies> should be as follows, from compileClasspath and runtimeClasspath:{}",
                            prospectiveDependencies.toStringForLogging());

                    xml.removeDependencyManagement();

                    for (final ScopedDependency dependency : prospectiveDependencies) {
                        switch (dependency.getScope()) {
                            case PROVIDED:
                                xml.insertProvidedDependency(dependency);
                                break;
                            case COMPILE:
                            case RUNTIME:
                                xml.applyCompileRuntimeDependency(dependency);
                                break;
                            default:
                                break;
                        }
                    }

                    xml.toCommit("<dependencies> in pom.xml after manipulation:");
                }
            });
        });

        configureGemTasks(project, extension, runtimeClasspath);
    }

    private static PublishingExtension getPublishingExtension(final Project project) {
        final Object publishingExtensionObject = project.getExtensions().findByName("publishing");
        if (publishingExtensionObject == null) {
            throw new GradleException("The plugin \"maven-publish\" is not applied.");
        }
        try {
            return (PublishingExtension) publishingExtensionObject;
        } catch (final ClassCastException ex) {
            throw new GradleException("Failed unexpectedly to cast \"project.publishing\" to \"PublishingExtension\".", ex);
        }
    }

    private static void initializeForAlternativeRuntimeConfiguration(
            final Project project,
            final EmbulkPluginExtension extension,
            final Configuration runtimeConfiguration) {
        if (!extension.getGeneratesModuleMetadata().getOrElse(false)) {
            project.getTasks().withType(GenerateModuleMetadata.class, configureAction -> {
                configureAction.setEnabled(false);
            });
        }

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

        warnIfRuntimeHasCompileOnlyDependencies(project, alternativeRuntimeConfiguration, extension);

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

        alternativeRuntimeConfiguration.withDependencies(resolvedDependencies -> {
            logger.info("{}", resolvedDependencies.getClass());
            final Map<String, ResolvedDependency> allResolvedDependencies = new HashMap<>();
            final Set<ResolvedDependency> firstLevelResolvedDependencies =
                    runtimeConfiguration.getResolvedConfiguration().getFirstLevelModuleDependencies();
            for (final ResolvedDependency dependency : firstLevelResolvedDependencies) {
                recurseAllResolvedDependencies(dependency, allResolvedDependencies, project);
            }

            for (final ResolvedDependency resolvedDependency : allResolvedDependencies.values()) {
                final String dependencyConfiguration = resolvedDependency.getConfiguration();
                final Set<ResolvedArtifact> resolvedModuleArtifacts = resolvedDependency.getModuleArtifacts();

                final HashSet<String> types = new HashSet<>();
                final HashSet<ProjectComponentIdentifier> projects = new HashSet<>();
                boolean hasNonStandardModuleArtifact = false;
                final HashSet<ResolvedArtifact> revisitedModuleArtifacts = new HashSet<>();
                logger.info("Dependency: {}", resolvedDependency);

                for (final ResolvedArtifact resolvedModuleArtifact : resolvedModuleArtifacts) {
                    final ComponentIdentifier componentIdentifier = resolvedModuleArtifact.getId().getComponentIdentifier();
                    if (componentIdentifier instanceof ProjectComponentIdentifier) {
                        // Project: such as `compile project(":subproject")`
                        types.add("project");
                        projects.add((ProjectComponentIdentifier) componentIdentifier);
                    } else if (componentIdentifier instanceof ModuleComponentIdentifier) {
                        // Other Java library: such as `compile "com.google.guava:guava:20.0"`
                        logger.info("    Resolved Module Artifact: {}", resolvedModuleArtifact);
                        types.add("module");
                        revisitedModuleArtifacts.add(resolvedModuleArtifact);
                        if (!Objects.equals(resolvedDependency.getModuleName(), resolvedModuleArtifact.getName())
                                || !Objects.equals(resolvedDependency.getModuleName(), resolvedModuleArtifact.getModuleVersion().getId().getName())
                                || !Objects.equals(resolvedDependency.getModuleGroup(), resolvedModuleArtifact.getModuleVersion().getId().getGroup())
                                || !Objects.equals(resolvedDependency.getModuleVersion(), resolvedModuleArtifact.getModuleVersion().getId().getVersion())
                                || (resolvedModuleArtifact.getClassifier() != null && !resolvedModuleArtifact.getClassifier().isEmpty())) {
                            hasNonStandardModuleArtifact = true;
                            logger.info("        {}", resolvedModuleArtifact.getModuleVersion());
                            logger.info("        {} : {}", resolvedDependency.getModuleName(), resolvedModuleArtifact.getName());
                        }
                    } else if (componentIdentifier instanceof LibraryBinaryIdentifier) {
                        // Native library (?): not very sure
                        logger.warn("Library module artifact type: \"" + resolvedModuleArtifact.toString() + "\"");
                        types.add("library");
                    } else {
                        logger.warn("Unknown module artifact type: \"" + resolvedModuleArtifact.toString() + "\"");
                        types.add("other");
                    }
                }
                if (types.size() > 1) {
                    throw new GradleException(
                            "Multiple types (" + types.stream().collect(Collectors.joining(", ")) + ") of module artifacts"
                            + " are found in dependency: " + resolvedDependency.toString());
                }

                if (types.contains("project")) {
                    if (projects.size() > 1) {
                        throw new GradleException("Multiple projects are found in dependency: " + resolvedDependency.toString());
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
                    resolvedDependencies.add(project.getDependencies().create(project.getDependencies().project(notation)));
                } else if (types.contains("module")) {
                    final HashMap<String, String> notation = new HashMap<>();
                    notation.put("group", resolvedDependency.getModuleGroup());
                    notation.put("name", resolvedDependency.getModuleName());
                    notation.put("version", resolvedDependency.getModuleVersion());

                    final ExternalModuleDependency moduleDependency;
                    try {
                        moduleDependency = (ExternalModuleDependency) project.getDependencies().create(notation);
                    } catch (final ClassCastException ex) {
                        throw new GradleException("Expected ExternalModuleDependency, but not.", ex);
                    }

                    if (hasNonStandardModuleArtifact) {
                        // Once a ModuleDependency has an additional artifact by ModuleDependency#artifact(...),
                        // the ModuleDependency's default artifact is overridden. Then, in that case, all artifacts
                        // need to be added explicitly, including the default.
                        for (final ResolvedArtifact resolvedModuleArtifact : revisitedModuleArtifacts) {
                            // https://discuss.gradle.org/t/how-can-i-specify-a-dependency-classifier-dynamically/5943
                            moduleDependency.artifact(dependencyArtifact -> {
                                dependencyArtifact.setName(resolvedDependency.getModuleName());
                                dependencyArtifact.setClassifier(resolvedModuleArtifact.getClassifier());
                                dependencyArtifact.setType(DependencyArtifact.DEFAULT_TYPE);  // "jar"
                                dependencyArtifact.setExtension(DependencyArtifact.DEFAULT_TYPE);  // "jar"
                            });
                        }
                        for (final DependencyArtifact dependencyArtifact : moduleDependency.getArtifacts()) {
                            logger.info("    Dependency Module Artifact: {}:{}",
                                        dependencyArtifact.getName(),
                                        dependencyArtifact.getClassifier());
                        }
                    }
                    resolvedDependencies.add(moduleDependency);
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
            final Configuration alternativeRuntimeConfiguration,
            final EmbulkPluginExtension extension) {
        final Set<String> ignoreConflicts = setOfIgnoreConflicts(extension.getIgnoreConflicts());

        final Configuration compileOnlyConfiguration = project.getConfigurations().getByName("compileOnly");

        final Map<String, ResolvedDependency> compileOnlyDependencies =
                collectAllResolvedDependencies(compileOnlyConfiguration, project);
        final Map<String, ResolvedDependency> alternativeRuntimeDependencies =
                collectAllResolvedDependencies(alternativeRuntimeConfiguration, project);

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
                        if (ignoreConflicts.contains(dependency.getModuleGroup() + ":" + dependency.getModuleName())) {
                            return "  ([IGNORE IT] \"" + dependency.getModule().toString() + "\")\n";
                        } else {
                            return "  \"" + dependency.getModule().toString() + "\"\n";
                        }
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
            final Configuration runtimeClasspath) {
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
            task.from(runtimeClasspath, copySpec -> {
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

    private static Set<String> setOfIgnoreConflicts(final ListProperty<Map<String, String>> ignoreConflicts) {
        final HashSet<String> result = new HashSet<String>();
        if (ignoreConflicts.isPresent() && !ignoreConflicts.get().isEmpty()) {
            for (final Map<String, String> module : ignoreConflicts.get()) {
                result.add(module.get("group") + ":" + module.get("module"));
            }
        }
        return Collections.unmodifiableSet(result);
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

    private static Map<String, ResolvedDependency> collectAllResolvedDependencies(
            final Configuration configuration, final Project project) {
        final HashMap<String, ResolvedDependency> allResolvedDependencies = new HashMap<>();

        final Set<ResolvedDependency> firstLevel = configuration.getResolvedConfiguration().getFirstLevelModuleDependencies();
        for (final ResolvedDependency dependency : firstLevel) {
            recurseAllResolvedDependencies(dependency, allResolvedDependencies, project);
        }

        return Collections.unmodifiableMap(allResolvedDependencies);
    }

    /**
     * Traverses the dependency tree recursively to list all the dependencies.
     */
    private static void recurseAllResolvedDependencies(
            final ResolvedDependency dependency,
            final Map<String, ResolvedDependency> allResolvedDependencies,
            final Project project) {
        final String key = dependency.getModuleGroup() + ":" + dependency.getModuleName();
        if (allResolvedDependencies.containsKey(key)) {
            final ResolvedDependency found = allResolvedDependencies.get(key);
            if (!found.equals(dependency)) {
                project.getLogger().warn(String.format(
                        "ResolvedDependency conflicts: \"%s\" : \"%s\"", found.toString(), dependency.toString()));
            }
            return;
        }
        allResolvedDependencies.put(key, dependency);
        if (dependency.getChildren().size() > 0) {
            for (final ResolvedDependency child : dependency.getChildren()) {
                recurseAllResolvedDependencies(child, allResolvedDependencies, project);
            }
        }
    }

    static final String DEFAULT_JRUBY = "org.jruby:jruby-complete:9.2.7.0";
}
