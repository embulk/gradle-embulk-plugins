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
import java.util.List;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.logging.Logger;
import org.gradle.api.plugins.JavaPlugin;
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

            initializeForPomModifications(projectAfterEvaluate, extension, compileClasspath, runtimeClasspath);
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

    static final String DEFAULT_JRUBY = "org.jruby:jruby-complete:9.2.7.0";
}
