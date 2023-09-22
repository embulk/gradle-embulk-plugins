Gradle plugin for Embulk plugins
=================================

Versions
---------

| Gradle Plugin version | Supported Gradle version(s) |
| --------------------- | --------------------------- |
| v0.6.1                | Gradle 7.6.1                |

Gradle 8 is not officially supported yet. (It may work, but not confirmed.)

Quick Start
------------

```
plugins {
    id "java"
    id "maven-publish"
    id "signing"

    // Apply this Gradle plugin.
    id "org.embulk.embulk-plugins" version "0.6.1"
}

repositories {
    mavenCentral()
}

// Set your own group ID. It would typically be:
// - From your own domain (ex. "com.example"), or
// - From your GitHub account (ex. "io.github.your-github-user").
//
// Note that you should not use "org.embulk" unless the plugin is maintained under: https://github.com/embulk
group = "..."

// Set the version of the plugin.
version = "0.1.5-SNAPSHOT"

// Set the description of your plugin.
description = "An Embulk plugin to load example data."

configurations {
    // We'd recommend to enable dependency locking so that you are aware of transitive dependencies.
    // See: https://docs.gradle.org/current/userguide/dependency_locking.html
    compileClasspath.resolutionStrategy.activateDependencyLocking()
    runtimeClasspath.resolutionStrategy.activateDependencyLocking()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(8)
    }

    // "javadoc" JAR and "sources" JAR are required to publish the plugin to Maven Central.
    withJavadocJar()
    withSourcesJar()
}

dependencies {
    // The versioning rule of "embulk-spi" has been independent from Embulk versions since v0.11.
    // It would be two digits, such as "0.11", "1.0", "1.1", ...
    // This is the version of Embulk SPI, as a contract between the core and plugins.
    compileOnly "org.embulk:embulk-spi:0.11"

    // An Embulk plugin should not depend on "embulk-core" if the plugin is ready for Embulk v0.11 and v1.0.

    // An Embulk plugin would usually depend some "embulk-util-*" librarires, for example, "embulk-util-config".
    // Note that Gradle 7+ needs to declare dependencies by "implementation", not by "compile".
    implementation "org.embulk:embulk-util-config:0.3.4"

    // ...

    testImplementation "junit:junit:4.13.2"

    // The Embulk main packages are often required for testing.
    testImplementation "org.embulk:embulk-spi:0.11"
    testImplementation "org.embulk:embulk-core:0.11.0"
    testImplementation "org.embulk:embulk-deps:0.11.0"
    testImplementation "org.embulk:embulk-junit4:0.11.0"
}

embulkPlugin {
    // Set the plugin's main class.
    mainClass = "org.embulk.input.example.ExampleInputPlugin"

    // Set: "decoder", "encoder", "filter", "formatter", "guess", "input", "output", or "parser"
    category = "input"

    // Set the "type" of the Embulk plugin used in Embulk's configuration YAML.
    // For instance, it would be "example" for "embulk-input-example".
    type = "example"
}

// It would be a good habit to contain the LICENSE file(s) at "META-INF/" in your plugin packages.
jar {
    metaInf {
        from rootProject.file("LICENSE")
    }
}
sourcesJar {
    metaInf {
        from rootProject.file("LICENSE")
    }
}
javadocJar {
    metaInf {
        from rootProject.file("LICENSE")
    }
}

// The publishing settings are usually required to publish the plugin to Maven Central.
// Publish it by: "./gradlew publishMavenPublicationToMavenRepository"
publishing {
    publications {
        maven(MavenPublication) {
            groupId = project.group
            artifactId = project.name

            from components.java
            // javadocJar and sourcesJar are added by java.withJavadocJar() and java.withSourcesJar() above.
            // See: https://docs.gradle.org/current/javadoc/org/gradle/api/plugins/JavaPluginExtension.html

            // Some pom.xml attributes are mandatory in Maven Central.
            // See: https://central.sonatype.org/pages/requirements.html
            pom {
                packaging "jar"

                name = project.name
                description = project.description
                url = "https://.../"

                licenses {
                    license {
                        // See: http://central.sonatype.org/pages/requirements.html#license-information
                        name = "..."
                        url = "..."
                        distribution = "repo"
                    }
                }

                developers {
                    developer {
                        name = "..."
                        email = "..."
                    }
                    developer {
                        name = "..."
                        email = "..."
                    }
                    // ...
                }

                scm {
                    connection = "scm:git:git://github.com/.../....git"
                    developerConnection = "scm:git:git@github.com:.../....git"
                    url = "https://github.com/.../..."
                }
            }
        }
    }

    repositories {
        maven {  // publishMavenPublicationToMavenCentralRepository
            name = "mavenCentral"

            // Note that the URLs may be different in your case, depending on your OSSRH / Sonatype registration.
            // See: https://central.sonatype.org/publish/publish-maven/
            if (project.version.endsWith("-SNAPSHOT")) {
                url "https://oss.sonatype.org/content/repositories/snapshots"
            } else {
                url "https://oss.sonatype.org/service/local/staging/deploy/maven2"
            }

            // Just an optional technique to specify OSSRH username and password from Gradle properties.
            //
            // It is sometimes useful to publish the plugin to Maven Central from CI like GitHub Actions.
            credentials {
                username = project.hasProperty("ossrhUsername") ? ossrhUsername : ""
                password = project.hasProperty("ossrhPassword") ? ossrhPassword : ""
            }
        }
    }
}

// The signing settings are usually required to publish the plugin to Maven Central.
// See: https://central.sonatype.org/publish/requirements/gpg/
signing {
    // Just an optional technique to specify a GPG key and password from Gradle properties.
    //
    // Set your GPG key into "signingKey" in the ASCII armor format.
    // Set your GPG key password into "signingPassword".
    //
    // It is sometimes useful to publish the plugin to Maven Central from CI like GitHub Actions.
    if (project.hasProperty("signingKey") && project.hasProperty("signingPassword")) {
        logger.lifecycle("Signing with an in-memory key.")
        useInMemoryPgpKeys(signingKey, signingPassword)
    }

    sign publishing.publications.maven
}

// Enable the following "gem" and "gemPush" tasks if you want to publish your plugin also as a Ruby Gem.

// gem {
//     authors = [ "..." ]
//     email = [ "..." ]
//     // "description" of the Ruby Gem would come from "description" of the Gradle project.
//     summary = "Example input plugin for Embulk"
//     homepage = "https://.../"
//     licenses = [ "..." ]  // See: https://guides.rubygems.org/specification-reference/#license=
//
//     from("LICENSE")  // If you want to include LICENSE file(s) in the Ruby Gem package.
// }

// Push it by: "./gradlew gemPush"
// gemPush {
//     host = "https://rubygems.org"
// }
```

### How to migrate from old `build.gradle`

1. Upgrade your Gradle wrapper to `7.6.1`.
2. Define `group`, `version`, and `description` in your Gradle project.
    * `group` should **NOT** be `"org.embulk"` unless your project is under: https://github.com/embulk.
      ```
      group = "com.example"
      version = "0.1.5-SNAPSHOT"
      description = "An Embulk plugin to load example data."
      ```
3. Replace dependencies on Embulk.
    * Old (without this Gradle plugin):
      ```
      compile "org.embulk:embulk-core:0.9.23"
      provided "org.embulk:embulk-core:0.9.23"
      ```
    * New:
      ```
      compileOnly "org.embulk:embulk-spi:0.11"

      testImplementation "org.embulk:embulk-spi:0.11"
      testImplementation "org.embulk:embulk-core:0.11.0"
      testImplementation "org.embulk:embulk-deps:0.11.0"
      testImplementation "org.embulk:embulk-junit4:0.11.0"
      ```
4. **Remove** an unnecessary configuration.
    * `provided`
    ```
    configurations {
        provided
    }
    ```
5. **Remove** unnecessary tasks.
    * `classpath`
    ```
    task classpath(type: Copy, dependsOn: ["jar"]) {
        doFirst { file("classpath").deleteDir() }
        from (configurations.runtime - configurations.provided + files(jar.archivePath))
        into "classpath"
    }
    clean { delete "classpath" }
    ```
    * `gem`
    ```
    task gem(type: JRubyExec, dependsOn: ["gemspec", "classpath"]) {
        jrubyArgs "-rrubygems/gem_runner", "-eGem::GemRunner.new.run(ARGV)", "build"
        script "${project.name}.gemspec"
        doLast { ant.move(file: "${project.name}-${project.version}.gem", todir: "pkg") }
    }
    ```
    * `gemPush`
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
    * `gemspec`
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
6. **Remove** an unnecessary file.
    * `lib/embulk/<category>/<type>.rb`
      ```
      Embulk::JavaPlugin.register_input(
        "example", "org.embulk.input.example.ExampleInputPlugin",
        File.expand_path('../../../../classpath', __FILE__))
      ```
7. Apply this Gradle plugin `"org.embulk.embulk-plugins"`.
    * In Gradle's [plugins DSL](https://docs.gradle.org/current/userguide/plugins.html#sec:plugins_block):
      ```
      plugins {
          id "java"
          id "maven-publish"
          id "signing"
          id "org.embulk.embulk-plugins" version "0.6.1"
      }
8. **Remove** unnecessary JRuby/Gradle plugin.
    * Plugin application:
      ```
          id "com.github.jruby-gradle.base" version "0.1.5"
      ```
    * Class import:
      ```
      import com.github.jrubygradle.JRubyExec
      ```
9. Configure the task `embulkPlugin`.
    * `mainClass`, `category`, and `type` are mandatory. For example:
      ```
      embulkPlugin {
          mainClass = "org.embulk.input.dummy.DummyInputPlugin"
          category = "input"
          type = "dummy"
      }
      ```
10. Configure `publishing`. Recommended to publish your Embulk plugin to Maven Central.
    * See the example above.
11. Configure `signing`. It is mandatory to publish your Embulk plugin to Maven Central.
    * See the example above.
12. Configure `gem` and `gemPush` if you want to publish your Embulk plugin also as a Ruby Gem.
    * See the example above.
    * Note that JRuby's `rubygems` may not support multi-factor authentication (OTP) for [https://rubygems.org/](https://rubygems.org/) yet. You may need to set your authentication level to "UI only".
        * https://guides.rubygems.org/setting-up-multifactor-authentication/

What this Gradle plugin does?
------------------------------

This Gradle plugin has two main purposes to satisfy two requirements as an Embulk plugin.

One of the requirements is to get Embulk plugin's `pom.xml` to include all dependencies as the direct first-level dependencies without any transitive dependency. This is an important restriction to keep dependencies consistent between plugin development and Embulk's runtime. (Indeed, Embulk's `PluginClassLoader` is implemented for Maven-based plugins to load only the direct first-level dependencies without any transitive dependency.)

The other requirement is to add some required attributes in `MANIFEST.MF`.

In addition, this Gradle plugin provides some support for publishing RubyGems-based plugins.

This Gradle plugin depends on Gradle's `java-plugin` and `maven-publish-plugin`.

For Maintainers of this Gradle plugin
--------------------------------------

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
