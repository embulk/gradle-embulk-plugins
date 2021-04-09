Gradle plugin for Embulk plugins
=================================

Quick Guide
------------

```
plugins {
    id "java"
    id "maven-publish"  // Note that "uploadArchives" with the "maven" plugin is no longer supported.

    // Once this Gradle plugin is applied, its transitive dependencies are automatically updated to be flattened.
    // The update affects the default `jar` task, and default Maven uploading mechanisms as well.
    id "org.embulk.embulk-plugins" version "0.4.2"
}

group = "com.example"
version = "0.1.5-ALPHA"
description = "An Embulk plugin to load example data."

repositories {
    mavenCentral()
    // jcenter() is no longer needed if depending only on 0.10.29+.
}

dependencies {
    compileOnly "org.embulk:embulk-api:0.10.29"
    compileOnly "org.embulk:embulk-spi:0.10.29"

    // It should not depend on "embulk-core" if your Embulk plugin is in the new "v0.10-style".
    // Depending on "embulk-core" is allowed only in the old "v0.9-style" Embulk plugins.
    // compileOnly "org.embulk:embulk-core:0.10.29"

    // It should depend some of "embulk-util-*" librarires if your plugin is in the new "v0.10-style".
    // "embulk-util-config" is often mandatory.
    // Note that your plugin should basically work with Embulk v0.9.23 even in the "v0.10-style".
    compile "org.embulk:embulk-util-config:0.2.1"
    // ...

    // Take care that other dependencies do not have transitive dependencies to `embulk-core` and its dependencies.
    // You'll need to exclude those transitive dependencies explicitly in that case.
    //
    // For example:
    // compile("org.embulk.base.restclient:embulk-base-restclient:0.7.0") {
    //     exclude group: "org.embulk", module: "embulk-core"
    // }

    testCompile "junit:junit:4.13"

    testCompile "org.embulk:embulk-api:0.10.29"
    testCompile "org.embulk:embulk-spi:0.10.29"
    testCompile "org.embulk:embulk-core:0.10.29"

    // TODO: Remove them.
    // These `testCompile` are a tentative workaround. It will be covered in Embulk core's testing mechanism.
    testCompile "org.embulk:embulk-deps:0.10.29"
}

embulkPlugin {
    mainClass = "org.embulk.input.example.ExampleInputPlugin"
    category = "input"
    type = "example"
}

// This Gradle plugin's POM dependency modification works for "maven-publish" tasks.
//
// Note that "uploadArchives" is no longer supported. It is deprecated in Gradle 6 to be removed in Gradle 7.
// https://github.com/gradle/gradle/issues/3003#issuecomment-495025844
publishing {
    publications {
        embulkPluginMaven(MavenPublication) {  // Publish it with "publishEmbulkPluginMavenPublicationToMavenRepository".
            from components.java  // Must be "components.java". The dependency modification works only for it.
        }
    }
    repositories {
        maven {
            url = "${project.buildDir}/mavenPublishLocal"
        }
    }
}

// Enable this when you want to publish your plugin as a gem.
// Note that `gem` is a type of archive tasks such as `jar` and `zip`, with some additional properties to fulfill `.gemspec`.
//
// gem {
//     from("LICENSE")  // Optional -- if you need other files in the gem.
//     authors = [ "Somebody Somewhere" ]
//     email = [ "somebody@example.com" ]
//     // "description" of the gem is copied from "description" of your Gradle project.
//     summary = "Example input plugin for Embulk"
//     homepage = "https://example.com"
//     licenses = [ "Apache-2.0" ]
//     metadata = [  // Optional -- if you need metadata in the gem.
//         "foo": "bar"
//     ]
// }

// Enable this when you want to publish your plugin as a gem.
// Note that the `host` property is mandatory.
//
// gemPush {
//     host = "https://rubygems.org"
// }
```

### Dependency locking

The dependency configuration `embulkPluginRuntime`, which is added by this Gradle plugin for flattened dependencies, has [dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html) activated by default.

In the beginning of your Embulk plugin project, or after migrating your Embulk plugin project to use this Gradle plugin, it is recommended for you to run `./gradlew dependencies --write-locks`, and add generated `gradle/dependency-locks/embulkPluginRuntime.lockfile` in your version control system. Your Embulk plugin project will have more sensitive checks on its dependency libraries, then.

### How to migrate old-style `build.gradle` of your Embulk plugins

1. Upgrade your Gradle wrapper to `6.0.1` or later.
2. Define `group`, `version`, and `description` in your Gradle project.
    * `group` should **NOT** be `"org.embulk"` unless your project is under: https://github.com/embulk. For example:
      ```
      group = "com.example"
      version = "0.1.5-SNAPSHOT"
      description = "An Embulk plugin to load example data."
      ```
3. Replace `compile` and `provided` in your dependencies to `compileOnly`.
    * Old (without this Gradle plugin):
      ```
      compile "org.embulk:embulk-core:0.9.23"
      provided "org.embulk:embulk-core:0.9.23"
      ```
    * Newer (with this Gradle plugin, but still in the "v0.9-style"):
      ```
      compileOnly "org.embulk:embulk-core:0.9.23"

      testCompile "org.embulk:embulk-core:0.9.23"
      ```
    * New (with this Gradle plugin in the "v0.10-style"):
      ```
      compileOnly "org.embulk:embulk-api:0.10.29"
      compileOnly "org.embulk:embulk-spi:0.10.29"

      testCompile "org.embulk:embulk-api:0.10.29"
      testCompile "org.embulk:embulk-spi:0.10.29"
      testCompile "org.embulk:embulk-core:0.10.29"
      testCompile "org.embulk:embulk-deps:0.10.29"
      ```
    * Take care that **other dependencies do not have transitive dependencies to `embulk-core` and its dependencies**. You'll need to exclude it explicitly those transitive dependencies explicitly in that case. For example:
      ```
      compile("org.embulk.base.restclient:embulk-base-restclient:0.7.0") {
          exclude group: "org.embulk", module: "embulk-core"
      }
      compile("org.glassfish.jersey.core:jersey-client:2.25.1") {
          exclude group: "javax.inject", module: "javax.inject"  // embulk-core depends on javax.inject.
      }
      ```
      * If a dependency needs to be duplicated intentionally, add `ignoreConflicts` in the `embulkPlugins` block. See below.
4. Add required `testCompile` if depending on `embulk-core:0.9.22+`.
    * If tests depend on `embulk-core:0.9.22`:
      ```
      // TODO: Remove it.
      // This `testCompile` is a tentative workaround. It will be covered in Embulk core's testing mechanism.
      testCompile "org.embulk:embulk-deps-buffer:0.9.22"
      ```
    * If tests depend `embulk-core:0.9.23` (or 0.10 until `embulk-core:0.10.9`):
      ```
      // TODO: Remove them.
      // These `testCompile` are a tentative workaround. It will be covered in Embulk core's testing mechanism.
      testCompile "org.embulk:embulk-deps-buffer:0.9.23"
      testCompile "org.embulk:embulk-deps-config:0.9.23"
      ```
    * If tests depend on `embulk-core:0.10.10`+:
      ```
      // TODO: Remove them.
      // These `testCompile` are a tentative workaround. It will be covered in Embulk core's testing mechanism.
      testCompile "org.embulk:embulk-deps:0.10.10"
      ```
5. **Remove** an unnecessary configuration.
    * `provided`
    ```
    configurations {
        provided
    }
    ```
6. **Remove** unnecessary tasks.
    * `classpath`
    ```
    task classpath(type: Copy, dependsOn: ["jar"]) {
        doFirst { file("classpath").deleteDir() }
        from (configurations.runtime - configurations.provided + files(jar.archivePath))
        into "classpath"
    }
    clean { delete "classpath" }
    ```
    * `gem`: a task with the same name is defined in this Gradle plugin
    ```
    task gem(type: JRubyExec, dependsOn: ["gemspec", "classpath"]) {
        jrubyArgs "-rrubygems/gem_runner", "-eGem::GemRunner.new.run(ARGV)", "build"
        script "${project.name}.gemspec"
        doLast { ant.move(file: "${project.name}-${project.version}.gem", todir: "pkg") }
    }
    ```
    * `gemPush`: a task with the same name is defined in this Gradle plugin
    ```
    task gemPush(type: JRubyExec, dependsOn: ["gem"]) {
        jrubyArgs "-rrubygems/gem_runner", "-eGem::GemRunner.new.run(ARGV)", "push"
        script "pkg/${project.name}-${project.version}.gem"
    }
    ```
    * `package`
    ```
    task "package"(dependsOn: ["gemspec", "classpath"]) {
        doLast {
            println "> Build succeeded."
            println "> You can run embulk with '-L ${file(".").absolutePath}' argument."
        }
    }
    ```
    * `gemspec`: the `gem` task defined in this Gradle plugin generates `.gemspec` under `build/`, and uses it to build a gem
    ```
    task gemspec {
        ext.gemspecFile = file("${project.name}.gemspec")
        inputs.file "build.gradle"
        outputs.file gemspecFile
        doLast { gemspecFile.write($/
    Gem::Specification.new do |spec|
      spec.name          = "${project.name}"
      spec.version       = "${project.version}"
      spec.authors       = ["Somebody Somewhere"]
      spec.summary       = %[Example input plugin for Embulk]
      spec.description   = %[An Embulk plugin to load example data.]
      spec.email         = ["somebody@example.com"]
      spec.licenses      = ["MIT"]
      spec.homepage      = "https://example.com"

      spec.files         = `git ls-files`.split("\n") + Dir["classpath/*.jar"]
      spec.test_files    = spec.files.grep(%r"^(test|spec)/")
      spec.require_paths = ["lib"]

      #spec.add_dependency 'YOUR_GEM_DEPENDENCY', ['~> YOUR_GEM_DEPENDENCY_VERSION']
      spec.add_development_dependency 'bundler'
      spec.add_development_dependency 'rake', ['>= 10.0']
    end
    /$)
        }
    }
    clean { delete "${project.name}.gemspec" }
    ```
7. Remove an unnecessary file.
    * `lib/embulk/<category>/<type>.rb`: the `gem` task defined in this Gradle plugin generates this `.rb` file under `build/` behind, and includes it in the gem. For example of `lib/embulk/input/example.rb`:
      ```
      Embulk::JavaPlugin.register_input(
        "example", "org.embulk.input.example.ExampleInputPlugin",
        File.expand_path('../../../../classpath', __FILE__))
      ```
8. Apply this Gradle plugin `"org.embulk.embulk-plugins"`.
    * Using the [plugins DSL](https://docs.gradle.org/current/userguide/plugins.html#sec:plugins_block):
      ```
      plugins {
          id "maven-publish"
          id "org.embulk.embulk-plugins" version "0.4.2"
      }
    * Using [legacy plugin application](https://docs.gradle.org/current/userguide/plugins.html#sec:old_plugin_application):
      ```
      buildscript {
          repositories {
          maven {
              url "https://plugins.gradle.org/m2/"
          }
      }
      dependencies {
          classpath "gradle.plugin.org.embulk:gradle-embulk-plugins:0.4.2"
      }

      apply plugin: "maven-publish"
      apply plugin: "org.embulk.embulk-plugins"
      ```
9. Remove unnecessary JRuby/Gradle plugin.
    * Plugin application:
      ```
          id "com.github.jruby-gradle.base" version "0.1.5"
      ```
    * Class import:
      ```
      import com.github.jrubygradle.JRubyExec
      ```
10. Configure the task `embulkPlugin`.
    * `mainClass`, `category`, and `type` are mandatory. For example:
      ```
      embulkPlugin {
          mainClass = "org.embulk.input.dummy.DummyInputPlugin"
          category = "input"
          type = "dummy"
      }
      ```
    * If a dependency (or dependencies) needs to be duplicated intentionally, add `ignoreConflicts` here in the `embulkPlugin` task like below. It does not affect any deliverable although it shows to ignore the conflict(s) in the related warning message.
      ```
      embulkPlugin {
          mainClass = "org.embulk.input.dummy.DummyInputPlugin"
          category = "input"
          type = "dummy"
          ignoreConflicts = [
              [ group: "javax.inject", module: "javax.inject" ],
              ...
          ]
      }
      ```
11. Configure publishing the plugin JAR to the Maven repository where you want to upload.
    * The standard `jar` task is already reconfigured to generate a JAR ready as an Embulk plugin.
    * Note that `uploadArchives` with the `maven` plugin is no longer supported.
    * Publishing example with `maven-publish`:
    ```
    publishing {
        publications {
            embulkPluginMaven(MavenPublication) {  // Publish it with "publishEmbulkPluginMavenPublicationToMavenRepository".
                from components.java  // Must be "components.java". The dependency modification works only for it.
            }
        }
        repositories {
            maven {
                url = "${project.buildDir}/mavenPublishLocal"
            }
        }
    }
    ```
12. Configure more to publish your plugin as a gem.
    * Configure the `gem` task. Note that `gem` is a type of archive tasks such as `jar` and `zip`, with some additional properties to fulfill `.gemspec`:
      ```
      gem {
          from("LICENSE")  // Optional -- if you need other files in the gem.
          authors = [ "Somebody Somewhere" ]
          email = [ "somebody@example.com" ]
          // "description" of the gem is copied from "description" of your Gradle project.
          summary = "Example input plugin for Embulk"
          homepage = "https://example.com"
          licenses = [ "Apache-2.0" ]
          metadata = [  // Optional -- if you need metadata in the gem.
              "foo": "bar"
          ]
      }
      ```
    * Configure the `gemPush` task. Note that the `host` property is mandatory:
      ```
      gemPush {
          host = "https://rubygems.org"
      }
      ```
    * Note that `rubygems` 2.7.9 embedded in JRuby 9.2.7.0 (the latest as of July 2019) does not support multi-factor authentication (OTP) yet. You'll need to set your authentication level to "UI only" when you push your gem into https://rubygems.org.
        * https://guides.rubygems.org/setting-up-multifactor-authentication/

What this Gradle plugin does?
------------------------------

This Gradle plugin does the following things for `jar` in addition to a normal Gradle build:

* Add some Embulk-specific attributes in generated JAR's manifest.
* Bring its transitive dependencies up flattened to the first level as `runtime`.
    * It is required in Embulk plugins because Embulk intentionally does not load transitive dependencies.
* Check that dependencies of `compileOnly` are not included in `runtime`.

And, it additionally provides some features for traditional `gem`-based Embulk plugins.
