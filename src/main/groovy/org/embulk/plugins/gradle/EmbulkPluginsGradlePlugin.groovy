package org.embulk.plugins.gradle

import org.embulk.plugins.gradle.tasks.EmbulkPluginJar
import org.embulk.plugins.gradle.tasks.MavenUploadEmbulkPluginJar
import org.gradle.api.Plugin
import org.gradle.api.Project

class EmbulkPluginsGradlePlugin implements Plugin<Project> {
    @Override
    void apply(final Project project) {
        configureEmbulkPluginJarTask(project)
        configureUploadEmbulkPluginJarTask(project)
    }

    protected void configureEmbulkPluginJarTask(final Project project) {
        EmbulkPluginJar embulkPluginJarTask = project.tasks.create("embulkPluginJar", EmbulkPluginJar)
        embulkPluginJarTask.group = "org.embulk.gradle"
        embulkPluginJarTask.description = 'Create an Embulk plugin JAR file'
    }

    private void configureUploadEmbulkPluginJarTask(final Project project) {
        final MavenUploadEmbulkPluginJar uploadEmbulkPluginJarTask =
            project.tasks.create("uploadEmbulkPluginJar", MavenUploadEmbulkPluginJar)
        uploadEmbulkPluginJarTask.group = "org.embulk.plugins.gradle"
        uploadEmbulkPluginJarTask.description = 'Upload an Embulk plugin JAR file to a Maven repository'
    }
}
