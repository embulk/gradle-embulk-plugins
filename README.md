Gradle plugin for Embulk plugins
=================================

Quick Guide
------------

```
apply plugin: 'org.embulk.plugins.gradle'

embulkPluginJar {
    mainClass = "org.embulk.input.example.ExampleInputPlugin"  // Mandatory
    // configurationForProvidedDependencies = configurations.provided  // Default: "configurations.provided"
    // destinationDir = "pkg"  // Default: "pkg"
}

uploadEmbulkPluginJar {
    configuration = embulkPluginJar.artifacts
    mavenDeployer {
        repository(url: "file:///path/to/maven/repository")
    }
}

task myEmbulkPluginJar(type: org.embulk.plugins.gradle.EmbulkPluginJar) {
    mainClass = "org.embulk.output.example.ExampleOutputPlugin"  // Mandatory
    configurationForProvidedDependencies = configurations.myProvided
    destinationDir = "my_pkg"
}

task myUploadEmbulkPluginJar(type: org.embulk.plugins.gradle.tasks.MavenUploadEmbulkPluginJar) {
    configuration = myEmbulkPluginJar.artifacts
    mavenDeployer {
        repository(url: "file:///path/to/another/maven/repository")
    }
}
```
