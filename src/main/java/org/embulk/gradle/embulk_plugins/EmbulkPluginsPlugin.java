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
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.maven.Conf2ScopeMapping;
import org.gradle.api.artifacts.maven.Conf2ScopeMappingContainer;
import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency;
import org.gradle.api.plugins.MavenPlugin;
import org.gradle.api.tasks.TaskContainer;
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
        this.applyPlugin(project, "maven");

        final EmbulkPluginExtension extension = createExtension(project);

        final Configuration runtimeConfiguration = project.getConfigurations().getByName("runtime");
        final Configuration flatRuntimeConfiguration =
                project.getConfigurations().create(extension.getFlatRuntimeConfiguration());

        this.configureFlatRuntime(project, runtimeConfiguration, flatRuntimeConfiguration);

        this.replaceConf2ScopeMappings(project, runtimeConfiguration, flatRuntimeConfiguration);

        this.configureJarTask(project, extension);
    }

    /**
     * Applies a plugin.
     *
     * <p>{@code apply plugin: "maven"}
     */
    private void applyPlugin(final Project project, final String pluginName) {
        final Map<String, String> pluginMap = new HashMap<>();
        pluginMap.put("plugin", pluginName);
        project.apply(pluginMap);
    }

    private EmbulkPluginExtension createExtension(final Project project) {
        return project.getExtensions().create(
                "embulkPlugin",
                EmbulkPluginExtension.class,
                project);
    }

    /**
     * Configures the flat runtime configuration with flattened dependencies.
     */
    private void configureFlatRuntime(
            final Project project,
            final Configuration runtimeConfiguration,
            final Configuration flatRuntimeConfiguration) {
        flatRuntimeConfiguration.withDependencies(dependencies -> {
                final Map<String, ResolvedDependency> allDependencies = new HashMap<>();
                final Set<ResolvedDependency> firstLevelDependencies =
                        runtimeConfiguration.getResolvedConfiguration().getFirstLevelModuleDependencies();
                for (final ResolvedDependency dependency : firstLevelDependencies) {
                    recurseAllDependencies(dependency, allDependencies);
                }

                for (final ResolvedDependency dependency : allDependencies.values()) {
                    dependencies.add(new DefaultExternalModuleDependency(dependency.getModuleGroup(),
                                                                         dependency.getModuleName(),
                                                                         dependency.getModuleVersion()));
                }
            });
    }

    /**
     * Replaces {@code "runtime"} to the flat runtime configuration in Gradle's {@code conf2ScopeMappings}
     * so that the flat runtime configuration is used to generate pom.xml, instead of the standard
     * {@code "runtime"} configuration.
     *
     * <p>The mappings correspond Gradle configurations (e.g. {@code "compile"}, {@code "compileOnly"}) to
     * Maven scopes (e.g. {@code "compile"}, {@code "provided"}).
     *
     * <p>See {@code MavenPlugin.java} and {@code DefaultPomDependenciesConverter.java} for how Gradle is
     * converting Gradle dependencies to Maven POM dependencies.
     *
     * @see <a href="https://github.com/gradle/gradle/blob/v5.5.1/subprojects/maven/src/main/java/org/gradle/api/publication/maven/internal/pom/DefaultPomDependenciesConverter.java">DefaultPomDependenciesConverter</a>
     * @see <a href="https://github.com/gradle/gradle/blob/v5.5.1/subprojects/maven/src/main/java/org/gradle/api/plugins/MavenPlugin.java#L171-L184">MavenPlugin#configureJavaScopeMappings</a>
     */
    private void replaceConf2ScopeMappings(
            final Project project,
            final Configuration runtimeConfiguration,
            final Configuration flatRuntimeConfiguration) {
        final Object conf2ScopeMappingsObject = project.property("conf2ScopeMappings");
        if (!(conf2ScopeMappingsObject instanceof Conf2ScopeMappingContainer)) {
            throw new GradleException("Property 'conf2ScopeMappings' is not properly configured.");
        }
        final Conf2ScopeMappingContainer conf2ScopeMappingContainer = (Conf2ScopeMappingContainer) conf2ScopeMappingsObject;
        final Map<Configuration, Conf2ScopeMapping> conf2ScopeMappings = conf2ScopeMappingContainer.getMappings();
        conf2ScopeMappings.remove(runtimeConfiguration);
        conf2ScopeMappingContainer.addMapping(MavenPlugin.RUNTIME_PRIORITY + 1,
                                              flatRuntimeConfiguration,
                                              Conf2ScopeMappingContainer.RUNTIME);
    }

    /**
     * Configures the standard {@code "jar"} task with required MANIFEST.
     */
    private void configureJarTask(final Project project, final EmbulkPluginExtension extension) {
        project.getTasks().named("jar", Jar.class, new Action<Jar>() {
            @Override
            public void execute(final Jar jarTask) {
                jarTask.manifest(UpdateManifestAction.builder()
                                 .add("Embulk-Plugin-Main-Class", extension.getMainClass())
                                 .add("Embulk-Plugin-Spi-Version", "0")
                                 .add("Implementation-Title", project.getName())
                                 .add("Implementation-Version", project.getVersion().toString())
                                 .build());
            }
        });
    }

    /**
     * Traverses the dependency tree recursively to list all the dependencies.
     */
    private static void recurseAllDependencies(
            final ResolvedDependency dependency,
            final Map<String, ResolvedDependency> allDependencies) {
        final String key = dependency.getModuleGroup() + ":" + dependency.getModuleName();
        if (allDependencies.containsKey(key)) {
            return;
        }
        allDependencies.put(key, dependency);
        if (dependency.getChildren().size() > 0) {
            for (final ResolvedDependency child : dependency.getChildren()) {
                recurseAllDependencies(child, allDependencies);
            }
        }
    }
}
