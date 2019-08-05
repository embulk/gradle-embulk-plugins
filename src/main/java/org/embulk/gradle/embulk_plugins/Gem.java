/*
 * Copyright 2019 The Embulk project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.embulk.gradle.embulk_plugins;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.file.copy.CopyAction;
import org.gradle.api.logging.Logger;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.process.ExecResult;

/**
 * A Gradle task to build a gem.
 *
 * <p>Configuration example:
 *
 * <pre>{@code gem {
 *   from("LICENSE")
 *   authors = [ "Somebody Somewhere" ]
 *   email = [ "somebody@example.com" ]
 *   summary = "Example input plugin for Embulk"
 *   homepage = "https://example.com"
 *   licenses = [ "Apache-2.0" ]
 *
 *   // JRuby artifact to execute `gem build`.
 *   // NOTE: Not recommended for users to configure it because this Gradle plugin expects a fixed version of JRuby.
 *   // For example, a certain version of `gem` would be required for command line options specified.
 *   // This option is here just for a quick hack or debugging.
 *   jruby = "org.jruby:jruby-complete:9.X.Y.Z"
 * }}</pre>
 */
class Gem extends AbstractArchiveTask {
    @Inject
    public Gem() {
        super();

        final ObjectFactory objectFactory = this.getProject().getObjects();

        this.embulkPluginMainClass = objectFactory.property(String.class);
        this.embulkPluginCategory = objectFactory.property(String.class);
        this.embulkPluginType = objectFactory.property(String.class);

        this.authors = objectFactory.listProperty(String.class);
        this.summary = objectFactory.property(String.class);

        this.gemDescription = objectFactory.property(String.class);
        this.email = objectFactory.listProperty(String.class);
        this.homepage = objectFactory.property(String.class);
        this.licenses = objectFactory.listProperty(String.class);
        // https://guides.rubygems.org/specification-reference/#metadata
        this.metadata = objectFactory.mapProperty(String.class, String.class);

        this.jruby = objectFactory.property(Object.class);
        this.jruby.set(EmbulkPluginsPlugin.DEFAULT_JRUBY);

        this.getArchiveExtension().set("gem");
    }

    @Override
    protected CopyAction createCopyAction() {
        final Project project = this.getProject();
        final Logger logger = project.getLogger();
        this.checkValidity(project, logger);

        this.cleanIfExists(project);

        // Copying the source files into the working directory. Note that the Gem task should not have top-level `into`
        // because AbstractArchiveTask#into represents a destination directory *inside* the archive for the files.
        // https://docs.gradle.org/5.5.1/javadoc/org/gradle/api/tasks/bundling/AbstractArchiveTask.html#into-java.lang.Object-
        project.copy(copySpec -> {
            copySpec.with(this);
            copySpec.into(this.getWorkingDir(project).toFile());
        });
        this.createBootstrap(project);
        this.createGemspec(project, this.listFiles(project));

        final ArrayList<String> args = new ArrayList<>();
        args.add("-rjars/setup");
        args.add("-S");
        args.add("gem");
        args.add("build");
        args.add(project.getName() + ".gemspec");

        final Configuration jrubyConfiguration = project.getConfigurations().detachedConfiguration();
        final Dependency jrubyDependency = project.getDependencies().create(this.jruby.get());
        jrubyConfiguration.withDependencies(dependencies -> {
            dependencies.add(jrubyDependency);
        });

        final FileCollection jrubyFiles = (FileCollection) jrubyConfiguration;
        if (logger.isLifecycleEnabled()) {
            logger.lifecycle(args.stream().collect(Collectors.joining(" ", "Exec: `java org.jruby.Main ", "`")));
            logger.lifecycle(
                    jrubyFiles.getFiles().stream().map(File::toString).collect(Collectors.joining("],[", "Classpath: [", "]")));
        }

        final ExecResult execResult = project.javaexec(javaExecSpec -> {
            javaExecSpec.setWorkingDir(getWorkingDir(project).toFile());
            javaExecSpec.setClasspath(jrubyFiles);
            javaExecSpec.setMain("org.jruby.Main");
            javaExecSpec.setArgs(args);

            final HashMap<String, Object> environments = new HashMap<>();
            environments.putAll(System.getenv());
            environments.putAll(javaExecSpec.getEnvironment());

            // Clearing GEM_HOME and GEM_PATH so that user environment variables do not affect the gem execution.
            environments.remove("GEM_HOME");
            environments.remove("GEM_PATH");

            // JARS_LOCK, JARS_HOME, and JARS_SKIP are for "jar-dependencies".
            // https://github.com/mkristian/jar-dependencies/wiki/Jars.lock#jarslock-filename
            environments.remove("JARS_LOCK");
            // https://github.com/mkristian/jar-dependencies/blob/0.4.0/Readme.md#configuration
            environments.remove("JARS_HOME");
            environments.put("JARS_SKIP", "true");

            // https://github.com/mkristian/jbundler/wiki/Configuration
            environments.put("JBUNDLE_SKIP", "true");

            javaExecSpec.setEnvironment(environments);
        });
        execResult.assertNormalExitValue();

        logger.lifecycle("Exec `gem build` finished successfully.");

        return new GemCopyAction(
                this.getWorkingDir(project).resolve(project.getName() + "-" + this.getArchiveVersion().get() + "-java.gem"),
                this.getArchiveFile());
    }

    public ListProperty<String> getAuthors() {
        return this.authors;
    }

    public Property<String> getSummary() {
        return this.summary;
    }

    public Property<String> getGemDescription() {
        return this.gemDescription;
    }

    public ListProperty<String> getEmail() {
        return this.email;
    }

    public Property<String> getHomepage() {
        return this.homepage;
    }

    public ListProperty<String> getLicenses() {
        return this.licenses;
    }

    public MapProperty<String, String> getMetadata() {
        return this.metadata;
    }

    /**
     * Property to configure a dependency notation for JRuby to run `gem build` and `gem push` commands.
     */
    public Property<Object> getJruby() {
        return this.jruby;
    }

    private void checkValidity(final Project project, final Logger logger) {
        final ArrayList<String> errors = new ArrayList<>();
        if ((!this.authors.isPresent()) || this.authors.get().isEmpty()) {
            errors.add("'authors' must not be empty in 'gem'.");
        }
        if ((!this.getArchiveBaseName().isPresent()) || this.getArchiveBaseName().get().isEmpty()) {
            errors.add("'archiveBaseName' must be available of 'gem'.");
        }
        if ((!this.summary.isPresent()) || this.summary.get().isEmpty()) {
            errors.add("'summary' must be available in 'gem'.");
        }
        if ((!this.getArchiveVersion().isPresent()) || this.getArchiveVersion().get().isEmpty()) {
            errors.add("'archiveVersion' must be available in 'gem'.");
        }

        if (!errors.isEmpty()) {
            throw new GradleException("[gradle-embulk-plugins] " + String.join(" ", errors));
        }

        if (!this.gemDescription.isPresent()) {
            logger.warn("[gradle-embulk-plugins] `project.description` or `gemDescription` in `gem` is recommended.");
        }
        if ((!this.email.isPresent()) || this.email.get().isEmpty()) {
            logger.warn("[gradle-embulk-plugins] `email` is recommended in `gem`. For example: `email = [ \"foo@example.com\" ]");
        }
        if (!this.homepage.isPresent()) {
            logger.warn("[gradle-embulk-plugins] `homepage` is recommended in `gem`. For example: `homepage = \"https://github.com/example/embulk-input-example\"");
        }
        if ((!this.licenses.isPresent()) || this.licenses.get().isEmpty()) {
            logger.warn("[gradle-embulk-plugins] `licenses` is recommended in `gem`. For example: `licenses = [ \"Apache-2.0\" ]");
        }
    }

    void setEmbulkPluginMainClass(final String embulkPluginMainClass) {
        this.embulkPluginMainClass.set(embulkPluginMainClass);
    }

    void setEmbulkPluginCategory(final String embulkPluginCategory) {
        this.embulkPluginCategory.set(embulkPluginCategory);
    }

    void setEmbulkPluginType(final String embulkPluginType) {
        this.embulkPluginType.set(embulkPluginType);
    }

    private static String renderList(final List<String> strings) {
        return String.join(", ", strings.stream().map(s -> "\"" + s + "\"").collect(Collectors.toList()));
    }

    private void cleanIfExists(final Project project) {
        final Path root = this.getWorkingDir(project);
        if (!Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException ex) {
            throw new GradleException("Could not clean the target directory: " + root.toString(), ex);
        }
    }

    private void createBootstrap(final Project project) {
        final Path dirPath = this.getWorkingDir(project).resolve("lib").resolve("embulk").resolve(this.embulkPluginCategory.get());
        try {
            Files.createDirectories(dirPath);
        } catch (final IOException ex) {
            throw new GradleException("Could not create: " + dirPath.toString(), ex);
        }

        final Path filePath = dirPath.resolve(this.embulkPluginType.get() + ".rb");
        try (final PrintWriter writer = new PrintWriter(Files.newOutputStream(filePath, StandardOpenOption.CREATE_NEW))) {
            writer.println("Embulk::JavaPlugin.register_" + this.embulkPluginCategory.get() + "(");
            writer.println("  \"" + this.embulkPluginType.get() + "\", \"" + this.embulkPluginMainClass.get() + "\",");
            writer.println("  File.expand_path(\"../../../../classpath\", __FILE__))");
        } catch (final IOException ex) {
            throw new GradleException("Could not create and write: " + filePath.toString(), ex);
        }
    }

    private List<Path> listFiles(final Project project) {
        final ArrayList<Path> files = new ArrayList<>();

        final Path root = this.getWorkingDir(project);
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    files.add(root.relativize(file));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException ex) {
            throw new GradleException("Could not list files in the directory: " + root.toString(), ex);
        }

        return Collections.unmodifiableList(files);
    }

    private void createGemspec(final Project project, final List<Path> files) {
        final Path gemspecPath = this.getWorkingDir(project).resolve(project.getName() + ".gemspec");
        try (final PrintWriter writer = new PrintWriter(Files.newOutputStream(gemspecPath, StandardOpenOption.CREATE_NEW))) {
            this.dump(writer, files);
        } catch (final IOException ex) {
            throw new GradleException("Could not create and write: " + gemspecPath.toString(), ex);
        }
    }

    private Path getWorkingDir(final Project project) {
        return ((File) project.property("buildDir")).toPath().resolve("gemContents").normalize();
    }

    // https://guides.rubygems.org/specification-reference/
    // https://maven.apache.org/ref/3.6.0/maven-model/apidocs/org/apache/maven/model/Model.html
    private void dump(final PrintWriter writer, final List<Path> files) {
        writer.println("Gem::Specification.new do |spec|");

        // REQUIRED GEMSPEC ATTRIBUTES
        writer.println("    spec.authors       = [" + renderList(this.authors.get()) + "]");
        writer.println("    spec.files         = [");
        for (final Path file : files) {
            writer.println("        \"" + file.toString() + "\",");
        }
        writer.println("    ]");
        writer.println("    spec.name          = \"" + this.getArchiveBaseName().get() + "\"");
        writer.println("    spec.summary       = \"" + this.summary.get() + "\"");
        writer.println("    spec.version       = \"" + this.getArchiveVersion().get() + "\"");

        // RECOMMENDED GEMSPEC ATTRIBUTES
        if (this.gemDescription.isPresent()) {
            writer.println("    spec.description   = \"" + this.gemDescription.get() + "\"");
        }
        if (this.email.isPresent() && !this.email.get().isEmpty()) {
            writer.println("    spec.email         = [" + renderList(this.email.get()) + "]");
        }
        if (this.homepage.isPresent()) {
            writer.println("    spec.homepage      = \"" + this.homepage.get() + "\"");
        }
        if (this.licenses.isPresent() && !this.licenses.get().isEmpty()) {
            writer.println("    spec.licenses      = [" + renderList(this.licenses.get()) + "]");
        }
        if (this.metadata.isPresent() && !this.metadata.get().isEmpty()) {
            writer.println("    spec.metadata      = {");
            for (final Map.Entry<String, String> entry : this.metadata.get().entrySet()) {
                writer.println("        \"" + entry.getKey() + "\" => \"" + entry.getValue() + "\",");
            }
            writer.println("    }");
        }

        // OPTIONAL GEMSPEC ATTRIBUTES
        // add_development_dependency
        // add_runtime_dependency
        // author=
        // bindir
        // cert_chain
        // executables
        // extensions
        // extra_rdoc_files
        writer.println("    spec.platform      = \"java\"");
        // post_install_message
        // rdoc_options
        writer.println("    spec.require_paths = [ \"lib\" ]");
        // required_ruby_version
        // required_ruby_version=
        // required_rubygems_version
        // required_rubygems_version=
        // requirements
        // rubygems_version
        // signing_key
        writer.println("end");
    }

    private final Property<String> embulkPluginMainClass;
    private final Property<String> embulkPluginCategory;
    private final Property<String> embulkPluginType;

    private final ListProperty<String> authors;
    private final Property<String> summary;

    private final Property<String> gemDescription;
    private final ListProperty<String> email;
    private final Property<String> homepage;
    // The singular `license` is to be substituted by `licenses`.
    private final ListProperty<String> licenses;
    private final MapProperty<String, String> metadata;

    private final Property<Object> jruby;
}
