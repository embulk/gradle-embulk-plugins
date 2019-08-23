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

import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.bundling.Jar;

/**
 * Configure a {@code "jar"} task to be available as an Embulk plugin.
 *
 * <p>It configures the {@code "jar"} task with required MANIFEST.
 */
public class ConfigureJarForEmbulkPlugin extends DefaultTask {
    @Inject
    public ConfigureJarForEmbulkPlugin() {
        super();

        final ObjectFactory objectFactory = this.getProject().getObjects();

        this.jar = objectFactory.property(String.class);
        this.jar.set("jar");
    }

    @TaskAction
    public void configure() {
        final Project project = this.getProject();

        final EmbulkPluginExtension extension = project.getExtensions().getByType(EmbulkPluginExtension.class);

        project.getTasks().named(this.jar.get(), Jar.class, jarTask -> {
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

    public Property<String> getJar() {
        return this.jar;
    }

    private final Property<String> jar;
}
