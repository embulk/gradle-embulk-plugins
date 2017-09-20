package org.embulk.plugins.gradle.tasks

import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Jar

class EmbulkPluginJar extends Jar {
    EmbulkPluginJar() {
        super()

        // Needs everything to be in |afterEvaluate| because:
        // - Properties are not configured yet just in the constructor.
        // - Child specs do not work in Jar/Copy's execution step since Gradle 4.
        // |afterEvaluate| runs after configuration step before execution step.
        project.afterEvaluate {
            if (configurationForProvidedDependencies == null) {
                configurationForProvidedDependencies =
                    project.configurations.getByName(defaultNameOfConfigurationForProvidedDependencies)
            }

            // "provided" dependencies are excluded as they are provided at runtime by the Embulk core.
            def embedded = project.configurations.runtime - configurationForProvidedDependencies

            if (extractsDependencies) {
                from {
                    // Dependencies are picked up with extracting ".jar" files.
                    embedded.collect { ( it.isFile() && it.name.endsWith(".jar") ) ? project.zipTree(it) : it }
                }
                manifest {
                    attributes 'Embulk-Plugin-Spi-Version': "0",
                               'Implementation-Title': project.name,
                               'Implementation-Version': project.version
                }
            } else {
                String normalizedPluginClassPathDir = (pluginClassPathDir == null ? '' : pluginClassPathDir)
                while (pluginClassPathDir.endsWith('/')) {
                    normalizedPluginClassPathDir =
                        normalizedPluginClassPathDir.substring(0, normalizedPluginClassPathDir.length() - 1)
                }
                into normalizedPluginClassPathDir, {
                    from {
                        embedded
                    }
                }
                def dependencies = []
                embedded.each {
                    if ( it.isFile() && it.name.endsWith(".jar") ) {
                        if (normalizedPluginClassPathDir.equals('')) {
                            dependencies.add(it.name)
                        } else {
                            dependencies.add(normalizedPluginClassPathDir + '/' + it.name)
                        }
                    }
                }
                manifest {
                    attributes 'Embulk-Plugin-Spi-Version': "0",
                               'Embulk-Plugin-Class-Path': dependencies.join(' '),
                               'Implementation-Title': project.name,
                               'Implementation-Version': project.version
                }
            }

            // Signature files of dependencies are excluded as they cause SecurityException.
            exclude("META-INF/*.DSA")
            exclude("META-INF/*.RSA")
            exclude("META-INF/*.SF")

            with project.tasks.jar
        }
    }

    @TaskAction
    @Override
    void copy() {
        manifest {  // Existence of `mainClass` should be checked at execution time, not afterEvaluate.
            attributes 'Embulk-Plugin-Main-Class': mainClass
        }
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

    boolean getExtractsDependencies() {
        return this.extractsDependencies
    }

    void setExtractsDependencies(boolean extractsDependencies) {
        this.extractsDependencies = extractsDependencies
    }

    String getPluginClassPathDir() {
        return this.pluginClassPathDir
    }

    void setPluginClassPathDir(String pluginClassPathDir) {
        this.pluginClassPathDir = pluginClassPathDir
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
    private boolean extractsDependencies = true
    private String pluginClassPathDir = ''
    private String defaultNameOfConfigurationForProvidedDependencies = "provided"
    private Configuration configurationForProvidedDependencies = null
    private File overriddenDestinationDir = null
}
