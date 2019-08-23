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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.maven.Conf2ScopeMapping;
import org.gradle.api.artifacts.maven.Conf2ScopeMappingContainer;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.SoftwareComponent;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.MavenPlugin;
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
        project.getPluginManager().apply(MavenPlugin.class);

        final EmbulkPluginExtension extension = createExtension(project);

        final Configuration runtimeConfiguration = project.getConfigurations().getByName("runtime");

        // It must be a non-detached configuration to be mapped into Maven scopes by Conf2ScopeMapping.
        final Configuration alternativeRuntimeConfiguration = project.getConfigurations().maybeCreate("embulkPluginRuntime");

        // The "embulkPluginRuntime" configuration has dependency locking activated by default.
        // https://docs.gradle.org/current/userguide/dependency_locking.html
        alternativeRuntimeConfiguration.getResolutionStrategy().activateDependencyLocking();

        configureAlternativeRuntime(project, runtimeConfiguration, alternativeRuntimeConfiguration);

        // Configure "components.java" (SoftwareComponent used for "from components.java" in MavenPublication)
        // to include "embulkPluginRuntime" as the "runtime" scope of Maven.
        // https://docs.gradle.org/5.5.1/dsl/org.gradle.api.publish.maven.MavenPublication.html#N1C095
        // https://github.com/gradle/gradle/blob/v5.5.1/subprojects/plugins/src/main/java/org/gradle/api/plugins/JavaPlugin.java#L347-L354
        //
        // This SoftwareComponent configuration is used to in maven-publish.
        // https://github.com/gradle/gradle/blob/v5.5.1/subprojects/maven/src/main/java/org/gradle/api/publish/maven/internal/publication/DefaultMavenPublication.java#L344-L352
        // https://github.com/gradle/gradle/blob/v5.5.1/subprojects/maven/src/main/java/org/gradle/api/publish/maven/internal/publication/DefaultMavenPublication.java#L266-L277
        // https://github.com/gradle/gradle/blob/v5.5.1/subprojects/maven/src/main/java/org/gradle/api/publish/maven/internal/publication/DefaultMavenPublication.java#L354-L374
        final SoftwareComponent component = project.getComponents().getByName("java");
        if (component instanceof AdhocComponentWithVariants) {
            ((AdhocComponentWithVariants) component).addVariantsFromConfiguration(alternativeRuntimeConfiguration, details -> {
                details.mapToMavenScope("runtime");
            });
        } else {
            throw new GradleException("Failed to configure components.java because it is not AdhocComponentWithVariants.");
        }

        // It must be configured before evaluation (not in afterEvaluate).
        replaceConf2ScopeMappings(project, runtimeConfiguration, alternativeRuntimeConfiguration);

        project.afterEvaluate(projectAfterEvaluate -> {
            initializeAfterEvaluate(projectAfterEvaluate, runtimeConfiguration, alternativeRuntimeConfiguration);

            // Configuration#getResolvedConfiguration here so that the dependency lock state is checked.
            alternativeRuntimeConfiguration.getResolvedConfiguration();
        });
    }

    private static EmbulkPluginExtension createExtension(final Project project) {
        return project.getExtensions().create(
                "embulkPlugin",
                EmbulkPluginExtension.class,
                project);
    }

    private static void initializeAfterEvaluate(
            final Project project,
            final Configuration runtimeConfiguration,
            final Configuration alternativeRuntimeConfiguration) {
        final EmbulkPluginExtension extension = project.getExtensions().getByType(EmbulkPluginExtension.class);

        if (!extension.isValidEmbulkPluginDefined()) {
            return;
        }

        project.getTasks().create("configureJarForEmbulkPlugin", ConfigureJarForEmbulkPlugin.class, task -> {
            task.setGroup(EMBULK_PLUGIN_GROUP);
            task.setDescription("Configures the 'jar' task to be available as an Embulk plugin.");
        });

        final WarnIfRuntimeHasCompileOnlyDependencies warnTask = project.getTasks().create(
                "warnIfRuntimeHasCompileOnlyDependencies",
                WarnIfRuntimeHasCompileOnlyDependencies.class,
                alternativeRuntimeConfiguration);
        warnTask.setGroup(EMBULK_PLUGIN_GROUP);
        warnTask.setDescription("Checks if compileOnly's transitive dependencies are included in runtime.");

        project.getTasks().named("jar", Jar.class, task -> {
            task.dependsOn("configureJarForEmbulkPlugin");
            task.dependsOn("warnIfRuntimeHasCompileOnlyDependencies");
        });

        project.getTasks().create("configureGemForEmbulkPlugin", ConfigureGemForEmbulkPlugin.class, task -> {
            task.setDescription("Configures the 'gem' task before executing it.");
        });
        project.getTasks().create("gem", Gem.class, task -> {
            task.dependsOn("jar");
            task.dependsOn("configureGemForEmbulkPlugin");
            task.setGroup(EMBULK_PLUGIN_GEM_GROUP);
            task.setDescription("Assembles a gem archive as an Embulk plugin.");
        });
        project.getTasks().create("gemPush", GemPush.class, task -> {
            task.dependsOn("gem");
            task.setGroup(EMBULK_PLUGIN_GEM_GROUP);
            task.setDescription("Pushes the gem archive.");
        });
    }

    /**
     * Configures the alternative (flattened) runtime configuration with flattened dependencies.
     */
    private static void configureAlternativeRuntime(
            final Project project,
            final Configuration runtimeConfiguration,
            final Configuration alternativeRuntimeConfiguration) {
        alternativeRuntimeConfiguration.withDependencies(dependencies -> {
            final Map<String, ResolvedDependency> allDependencies = new HashMap<>();
            final Set<ResolvedDependency> firstLevelDependencies =
                    runtimeConfiguration.getResolvedConfiguration().getFirstLevelModuleDependencies();
            for (final ResolvedDependency dependency : firstLevelDependencies) {
                if (dependency.getConfiguration().equals("runtimeElements")) {
                    // The target project may contain a non-"group:module:version" dependency. A subproject, for example,
                    // "compile project(':subproject-a')" should not be resolved as "group:module:version", nor be flattened.
                    // See also: https://discuss.gradle.org/t/determining-external-vs-sub-project-dependencies/12321
                    //
                    // Such a dependency is under the configuration "runtimeElements".
                    // https://docs.gradle.org/current/userguide/java_library_plugin.html#sec:java_library_configurations_graph
                    for (final ResolvedArtifact artifact : dependency.getModuleArtifacts()) {
                        // TODO: Consider nested subproject dependencies. See #54.
                        final ComponentIdentifier componentIdentifier = artifact.getId().getComponentIdentifier();
                        if (componentIdentifier instanceof ProjectComponentIdentifier) {
                            final Project dependencyProject =
                                    project.project(((ProjectComponentIdentifier) componentIdentifier).getProjectPath());
                            dependencies.add(project.getDependencies().create(dependencyProject));
                        }
                    }
                } else {
                    Util.recurseAllDependencies(dependency, allDependencies);
                }
            }

            for (final ResolvedDependency dependency : allDependencies.values()) {
                final HashMap<String, String> notation = new HashMap<>();
                notation.put("group", dependency.getModuleGroup());
                notation.put("name", dependency.getModuleName());
                notation.put("version", dependency.getModuleVersion());
                dependencies.add(project.getDependencies().create(notation));
            }
        });
    }

    /**
     * Replaces {@code "runtime"} to the alternative runtime configuration in Gradle's {@code conf2ScopeMappings}
     * so that the alternative runtime configuration is used to generate pom.xml, instead of the standard
     * {@code "runtime"} configuration.
     *
     * <p>The mappings correspond Gradle configurations (e.g. {@code "compile"}, {@code "compileOnly"}) to
     * Maven scopes (e.g. {@code "compile"}, {@code "provided"}).
     *
     * <p>See {@code MavenPlugin.java} and {@code DefaultPomDependenciesConverter.java} for how Gradle is
     * converting Gradle dependencies to Maven POM dependencies.
     *
     * <p>Note that {@code conf2ScopeMappings} must be configured before evaluation (not in {@code afterEvaluate}).
     * See <a href="https://github.com/gradle/gradle/issues/1373">https://github.com/gradle/gradle/issues/1373</a>.
     *
     * @see <a href="https://github.com/gradle/gradle/blob/v5.5.1/subprojects/maven/src/main/java/org/gradle/api/publication/maven/internal/pom/DefaultPomDependenciesConverter.java">DefaultPomDependenciesConverter</a>
     * @see <a href="https://github.com/gradle/gradle/blob/v5.5.1/subprojects/maven/src/main/java/org/gradle/api/plugins/MavenPlugin.java#L171-L184">MavenPlugin#configureJavaScopeMappings</a>
     */
    private static void replaceConf2ScopeMappings(
            final Project project,
            final Configuration runtimeConfiguration,
            final Configuration alternativeRuntimeConfiguration) {
        final Object conf2ScopeMappingsObject = project.property("conf2ScopeMappings");
        if (!(conf2ScopeMappingsObject instanceof Conf2ScopeMappingContainer)) {
            throw new GradleException("Unexpected with \"conf2ScopeMappings\" not configured properly.");
        }
        final Conf2ScopeMappingContainer conf2ScopeMappingContainer = (Conf2ScopeMappingContainer) conf2ScopeMappingsObject;
        final Map<Configuration, Conf2ScopeMapping> conf2ScopeMappings = conf2ScopeMappingContainer.getMappings();
        conf2ScopeMappings.remove(runtimeConfiguration);
        conf2ScopeMappingContainer.addMapping(MavenPlugin.RUNTIME_PRIORITY + 1,
                                              alternativeRuntimeConfiguration,
                                              Conf2ScopeMappingContainer.RUNTIME);
    }

    static final String DEFAULT_JRUBY = "org.jruby:jruby-complete:9.2.7.0";

    private static final String EMBULK_PLUGIN_GROUP = "Embulk plugin";
    private static final String EMBULK_PLUGIN_GEM_GROUP = "Embulk plugin Gem";
}
