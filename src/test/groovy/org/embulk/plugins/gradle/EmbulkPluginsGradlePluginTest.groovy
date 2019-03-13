package org.embulk.plugins.gradle

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification

class EmbulkPluginsGradlePluginTest extends Specification {

    def "run embulkPluginJar task then JAR file created"() {
        when:
        build("embulkPluginJar")
        then:
        // TODO: Check contents of the file
        new File("${PROJECT_DIR}/pkg/test-project-0.1.0.jar").exists()
    }

    def "run uploadEmbulkPluginJar task then the artifact uploaded"() {
        when:
        build("uploadEmbulkPluginJar")
        then:
        // TODO: Check other contents (e.g. pom.xml) of the artifact
        new File("${PROJECT_DIR}/maven/repository/test-project/0.1.0/test-project-0.1.0.jar").exists()
    }

    ////////////////////////////////////////////////////////////
    // Helper methods
    ////////////////////////////////////////////////////////////

    def setup() {
        projectDir = new File(PROJECT_DIR).with {
            deleteDir()
            mkdir()
            return delegate
        }
        // Generate build.gradle for test-project
        new File("${PROJECT_DIR}/build.gradle").write("""
            plugins {
              id "org.embulk.plugins.gradle"
              id "java"
              id "maven"
            }

            version = "0.1.0"

            configurations {
              provided
            }

            repositories {
              jcenter()
            }

            dependencies {
              compile "org.embulk:embulk-core:0.9.16"
              provided "org.embulk:embulk-core:0.9.16"
            }

            embulkPluginJar {
                mainClass = "org.embulk.input.example.ExampleInputPlugin"
            }

            uploadEmbulkPluginJar {
                configuration = embulkPluginJar.artifacts
                mavenDeployer {
                    repository(url: "file://${projectDir.absolutePath}/maven/repository")
                }
            }
        """.stripIndent())
    }

    private def build(String... args) {
        def result = newGradleRunner(*args, "--stacktrace").build()
        println "============================================================"
        println "// Output of 'gradle ${args.join(" ")}'"
        println result.output
        println "============================================================"
        result
    }

    private def newGradleRunner(String... args) {
        GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments(args)
                .withDebug(true)
                .withPluginClasspath()
    }

    private static String PROJECT_DIR = "build/tmp/test-project"
    private File projectDir
}
