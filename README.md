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

task myEmbulkPluginJar(type: org.embulk.plugins.gradle.EmbulkPluginJar) {
    mainClass = "org.embulk.output.example.ExampleOutputPlugin"  // Mandatory
    configurationForProvidedDependencies = configurations.myProvided
    destinationDir = "my_pkg"
}
```
