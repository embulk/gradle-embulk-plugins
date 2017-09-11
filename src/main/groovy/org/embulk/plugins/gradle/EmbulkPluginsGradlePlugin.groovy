package org.embulk.plugins.gradle

import org.embulk.plugins.gradle.tasks.EmbulkPluginJar
import org.gradle.api.Plugin
import org.gradle.api.Project

class EmbulkPluginsGradlePlugin implements Plugin<Project> {
    @Override
    void apply(final Project project) {
        configureEmbulkPluginJarTask(project)
    }

    protected void configureEmbulkPluginJarTask(final Project project) {
        EmbulkPluginJar embulkPluginJarTask = project.tasks.create("embulkPluginJar", EmbulkPluginJar)
        embulkPluginJarTask.group = "org.embulk.gradle"
        embulkPluginJarTask.description = 'Create an Embulk plugin JAR file'
    }
}
