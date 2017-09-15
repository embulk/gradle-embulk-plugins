package org.embulk.plugins.gradle.tasks

import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Jar

class EmbulkPluginJar extends Jar {
    EmbulkPluginJar() {
        super()
        project.afterEvaluate {
            with project.tasks.jar
        }
    }

    @TaskAction
    @Override
    void copy() {
        manifest {
            attributes 'Embulk-Plugin-Spi-Version': "0",
                       'Embulk-Plugin-Main-Class': mainClass,
                       'Implementation-Title': project.name,
                       'Implementation-Version': project.version
        }

        from {
            if (configurationForProvidedDependencies == null) {
                configurationForProvidedDependencies =
                    project.configurations.getByName(defaultNameOfConfigurationForProvidedDependencies)
            }

            // "provided" dependencies are excluded as they are provided at runtime by the Embulk core.
            def embedded = project.configurations.runtime - configurationForProvidedDependencies

            // Dependencies are picked up with extracting ".jar" files.
            embedded.collect { ( it.isFile() && it.name.endsWith(".jar") ) ? project.zipTree(it) : it }
        }

        // Signature files of dependencies are excluded as they cause SecurityException.
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
        exclude("META-INF/*.SF")

        super.copy()
    }

    Configuration getArtifacts() {
        Configuration artifactConfiguration = project.configurations.detachedConfiguration()
        org.gradle.api.artifacts.ConfigurationPublications publications = artifactConfiguration.getOutgoing()
        publications.artifact(this)
        return artifactConfiguration
    }

    String getMainClass() {
        return this.mainClass
    }

    void setMainClass(String mainClass) {
        this.mainClass = mainClass
    }

    String getDefaultNameOfConfigurationForProvidedDependencies() {
        return this.defaultNameOfConfigurationForProvidedDependencies
    }

    void setDefaultNameOfConfigurationForProvidedDependencies(
            String defaultNameOfConfigurationForProvidedDependencies) {
        this.defaultNameOfConfigurationForProvidedDependencies =
            defaultNameOfConfigurationForProvidedDependencies;
    }

    Configuration getConfigurationForProvidedDependencies() {
        return this.configurationForProvidedDependencies
    }

    void setConfigurationForProvidedDependencies(Configuration configurationForProvidedDependencies) {
        this.configurationForProvidedDependencies = configurationForProvidedDependencies
    }

    @Override  // org.gradle.api.tasks.bundling.Jar > org.gradle.api.tasks.bundling.AbstractArchiveTask
    File getDestinationDir() {
        if (this.overriddenDestinationDir == null) {
            this.setDestinationDir(project.file("pkg"))
        }
        return this.overriddenDestinationDir
    }

    @Override  // org.gradle.api.tasks.bundling.Jar > org.gradle.api.tasks.bundling.AbstractArchiveTask
    void setDestinationDir(File overriddenDestinationDir) {
        this.overriddenDestinationDir = overriddenDestinationDir;
        super.setDestinationDir(overriddenDestinationDir);
    }

    private String mainClass
    private String defaultNameOfConfigurationForProvidedDependencies = "provided"
    private Configuration configurationForProvidedDependencies = null
    private File overriddenDestinationDir = null
}
