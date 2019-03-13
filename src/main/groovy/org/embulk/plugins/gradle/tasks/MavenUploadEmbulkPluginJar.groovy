package org.embulk.plugins.gradle.tasks

import org.gradle.api.GradleException
import org.gradle.api.plugins.MavenPlugin;
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Upload

/**
 * MavenUploadEmbulkPluginJar is a variety of Upload tasks to upload Embulk's plugin JAR file to a Maven repository.
 *
 * It expects only Maven repositories because Embulk expects Maven repositories in its plugin system.
 * It directly takes a "mavenDeployer" block, but its "pom" is eventually overridden in the task.
 */
class MavenUploadEmbulkPluginJar extends Upload {
    @TaskAction
    @Override
    void upload() {
        if (!project.plugins.hasPlugin(MavenPlugin)) {
            throw new GradleException("This task depends on Maven Plugin. Please apply it into your project.")
        }
        repositories {
            mavenDeployer {
                final Closure specifiedMavenDeployer = getMavenDeployerClosure().clone()
                if (specifiedMavenDeployer != null) {
                    specifiedMavenDeployer.setDelegate(owner)  // Set to run in the "mavenDeployer" block.
                    specifiedMavenDeployer()
                }

                pom cleanupPomInPluginJar  // Override the pom
            }
        }
        super.upload()
    }

    final Closure cleanupPomInPluginJar = {
        // All dependencies except for "org.embulk:embulk-core" are removed.
        whenConfigured { pom ->
            pom.dependencies = pom.dependencies.findAll { dependency ->
                ( dependency.groupId == "org.embulk" &&
                  dependency.artifactId == "embulk-core" &&
                  ( dependency.classifier == null || dependency.classifier == "" ) )
            }
        }

        // The dependency "org.embulk:embulk-core" is "provided" for plugin JARs.
        whenConfigured { pom ->
            pom.dependencies.find { dependency ->
                ( dependency.groupId == "org.embulk" &&
                  dependency.artifactId == "embulk-core" &&
                  ( dependency.classifier == null || dependency.classifier == "" ) )
            }.scope = "provided"
        }
    }

    void mavenDeployer(Closure closure) {
        this.mavenDeployerClosure = closure
    }

    Closure getMavenDeployerClosure() {
        return this.mavenDeployerClosure
    }

    private Closure mavenDeployerClosure = null
}
