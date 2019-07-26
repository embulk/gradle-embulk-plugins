Gradle plugin for Embulk plugins
=================================

Quick Guide
------------

```
buildscript {
    repositories {
        maven {
            url "https://plugins.gradle.org/m2/"
        }
    }
    dependencies {
        classpath "gradle.plugin.org.embulk:gradle-embulk-plugin:0.2.0"
    }
}

apply plugin: "java"
apply plugin: "maven"

// Once this Gradle plugin is applied, its dependencies are automatically updated to be flattened.
// The update affects the default "jar" task, and default Maven uploading mechanisms as well.
apply plugin: "org.embulk.embulk-plugins"

embulkPlugin {
    mainClass = "org.embulk.input.example.ExampleInputPlugin"  // Mandatory.
}

uploadArchives {  // You can use any uploading mechanism as you like.
    repositories {
        mavenDeployer {
            repository(url: "file:${project.buildDir}/mavenLocal")
            snapshotRepository(url: "file:${project.buildDir}/mavenLocalSnapshot")
        }
    }
}
```
