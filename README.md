Gradle plugin for Embulk plugins
=================================

Versions
---------

| Plugin version | Supported Gradle versions | Method to manipulate `pom.xml`            |
| -------------- | ------------------------- | ----------------------------------------- |
| v0.4.5         | Gradle 6                  | Done by additional Gradle `configuration` |
| v0.5.5         | Gradle 6 & 7              | Done by `pom.withXml`                     |
| v0.6.0 (TBA)   | Gradle 7.6                | Done by `pom.withXml`                     |

Quick Guide
------------

```
plugins {
    id "java"
    id "maven-publish"

    // Once this Gradle plugin is applied, its transitive dependencies are automatically updated to be flattened.
    // The update affects the default `jar` task, and default Maven uploading mechanisms as well.
    id "org.embulk.embulk-plugins" version "0.5.5"
}

group = "com.example"
version = "0.1.5-ALPHA"
description = "An Embulk plugin to load example data."

repositories {
    mavenCentral()
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
publishing {
    publications {
        embulkPluginMaven(MavenPublication) {  // Publish it with "publishEmbulkPluginMavenPublicationToMavenRepository".
            from components.java  // Must be "components.java".
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

The dependency configurations `compileClasspath` and `runtimeClasspath` have [dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html) activated by default.

In the beginning of your Embulk plugin project, or after migrating your Embulk plugin project to use this Gradle plugin, it is recommended for you to run `./gradlew dependencies --write-locks`, and to add generated `lockfile`(s) in your version control system.

* Gradle 6: `/gradle/dependency-locks/compileClasspath.lockfile` and `/gradle/dependency-locks/runtimeClasspath.lockfile`
* Gradle 7: `/gradle.lockfile`

Embulk plugins are sensitive about dependency libraries. Your project will have better checks about dependencies by `lockfile`(s).

### How to migrate old-style `build.gradle` of your Embulk plugins

1. Upgrade your Gradle wrapper to `6.4.1` or later.
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
          id "org.embulk.embulk-plugins" version "0.5.5"
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
          classpath "gradle.plugin.org.embulk:gradle-embulk-plugins:0.5.5"
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

This Gradle plugin has two main purposes to satisfy two requirements as an Embulk plugin.

One of the requirements is to get Embulk plugin's `pom.xml` to include all dependencies as the direct first-level dependencies without any transitive dependency. This is an important restriction to keep dependencies consistent between plugin development and Embulk's runtime. (Indeed, Embulk's `PluginClassLoader` is implemented for Maven-based plugins to load only the direct first-level dependencies without any transitive dependency.)

The other requirement is to add some required attributes in `MANIFEST.MF`.

In addition, this Gradle plugin provides some support for publishing RubyGems-based plugins.

This Gradle plugin depends on Gradle's `java-plugin` and `maven-publish-plugin`.

For Maintainers
----------------

### Release

Modify `version` in `build.gradle` at a detached commit, and then tag the commit with an annotation.

```
git checkout --detach master

(Edit: Remove "-SNAPSHOT" in "version" in build.gradle.)

git add build.gradle

git commit -m "Release vX.Y.Z"

git tag -a vX.Y.Z

(Edit: Write a tag annotation in the changelog format.)
```

See [Keep a Changelog](https://keepachangelog.com/en/1.0.0/) for the changelog format. We adopt a part of it for Git's tag annotation like below.

```
## [X.Y.Z] - YYYY-MM-DD

### Added
- Added a feature.

### Changed
- Changed something.

### Fixed
- Fixed a bug.
```

Push the annotated tag, then. It triggers a release operation on GitHub Actions after approval.

```
git push -u origin vX.Y.Z
```
