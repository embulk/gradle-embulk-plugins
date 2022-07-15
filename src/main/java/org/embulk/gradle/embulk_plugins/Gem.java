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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.file.copy.CopyAction;
import org.gradle.api.logging.Logger;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
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
 *   // Only if gem dependencies are required.
 *   dependencies = [ "'jsonpath', ['~> 0.5.8']", "'json', ['~> 2.0.2']" ]
 *
 *   // If true, auto-generate the bootstrap Ruby code in /lib/embulk/???/???.rb. (Default = true)
 *   generateRubyCode = true
 *
 *   // If true, auto-generate the .gemspec file at the root directory. (Default = true)
 *   generateGemspec = true
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

        this.email = objectFactory.listProperty(String.class);
        this.homepage = objectFactory.property(String.class);
        this.licenses = objectFactory.listProperty(String.class);
        this.dependencies = objectFactory.listProperty(String.class);
        // https://guides.rubygems.org/specification-reference/#metadata
        this.metadata = objectFactory.mapProperty(String.class, String.class);

        this.generateRubyCode = objectFactory.property(Boolean.class);
        this.generateRubyCode.set(true);

        this.generateGemspec = objectFactory.property(Boolean.class);
        this.generateGemspec.set(true);

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

        // https://discuss.gradle.org/t/rewrite-archiveversion-in-a-gradle-plugin/35162
        // It works for archiveFileName with Gradle 5.6.1+.
        resetArchiveVersionToRubyStyle(this.getArchiveVersion());

        // Copying the source files into the working directory. Note that the Gem task should not have top-level `into`
        // because AbstractArchiveTask#into represents a destination directory *inside* the archive for the files.
        // https://docs.gradle.org/5.5.1/javadoc/org/gradle/api/tasks/bundling/AbstractArchiveTask.html#into-java.lang.Object-
        //
        // TODO: Replace it with creating files in `GemCopyAction` (See #37)
        // https://github.com/embulk/gradle-embulk-plugins/issues/37
        project.copy(copySpec -> {
            copySpec.with(this);
            copySpec.into(this.getWorkingDir(project).toFile());
        });
        if ((!this.generateRubyCode.isPresent()) || this.generateRubyCode.get()) {
            this.createBootstrap(project);
        }
        if ((!this.generateGemspec.isPresent()) || this.generateGemspec.get()) {
            this.createGemspec(project, this.listFiles(project));
        }

        final ArrayList<String> args = new ArrayList<>();
        args.add("-rjars/setup");
        args.add("-S");
        args.add("gem");
        args.add("build");
        args.add(project.getName() + ".gemspec");

        final Path workingDirectory = this.getWorkingDir(project);

        final Configuration jrubyConfiguration = project.getConfigurations().detachedConfiguration();
        final Dependency jrubyDependency = project.getDependencies().create(this.jruby.get());
        jrubyConfiguration.withDependencies(dependencies -> {
            dependencies.add(jrubyDependency);
        });

        final FileCollection jrubyFiles = (FileCollection) jrubyConfiguration;
        if (logger.isLifecycleEnabled()) {
            logger.lifecycle(
                    "Executing: `java org.jruby.Main " + String.join(" ", args) + "`\n"
                    + "    with working directory at: " + workingDirectory.toString() + "\n"
                    + "    with classpath: "
                    + jrubyFiles.getFiles().stream().map(File::getPath).collect(Collectors.joining(", ", "[ ", " ]")));
        }

        final ExecResult execResult = project.javaexec(javaExecSpec -> {
            javaExecSpec.setWorkingDir(workingDirectory.toFile());
            javaExecSpec.setClasspath(jrubyFiles);
            javaExecSpec.setMain("org.jruby.Main");
            javaExecSpec.setArgs(args);

            javaExecSpec.setIgnoreExitValue(false);

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

        logger.lifecycle("Executing `gem build` finished successfully.");

        return new GemCopyAction(
                this.getWorkingDir(project).resolve(project.getName() + "-" + this.getArchiveVersion().get() + "-java.gem"),
                this.getArchiveFile(),
                project);
    }

    @Input
    public ListProperty<String> getAuthors() {
        return this.authors;
    }

    @Input
    public Property<String> getSummary() {
        return this.summary;
    }

    @Input
    public ListProperty<String> getEmail() {
        return this.email;
    }

    @Input
    public Property<String> getHomepage() {
        return this.homepage;
    }

    @Input
    public ListProperty<String> getLicenses() {
        return this.licenses;
    }

    @Input
    public ListProperty<String> getDependencies() {
        return this.dependencies;
    }

    @Input
    public MapProperty<String, String> getMetadata() {
        return this.metadata;
    }

    @Input
    public Property<Boolean> getGenerateRubyCode() {
        return this.generateRubyCode;
    }

    @Input
    public Property<Boolean> getGenerateGemspec() {
        return this.generateGemspec;
    }

    /**
     * Property to configure a dependency notation for JRuby to run `gem build` and `gem push` commands.
     */
    @Input
    public Property<Object> getJruby() {
        return this.jruby;
    }

    private void checkValidity(final Project project, final Logger logger) {
        if (project.getDescription() == null || project.getDescription().isEmpty()) {
            logger.warn("Recommended to configure \"project.description\".");
        }
        if ((!this.email.isPresent()) || this.email.get().isEmpty()) {
            logger.warn("Recommended to configure \"email\". For example: `email = [ \"foo@example.com\" ]`");
        }
        if (!this.homepage.isPresent()) {
            logger.warn("Recommended to configure \"homepage\". For example: `homepage = \"https://example.com\"`");
        }
        if (!this.licenses.isPresent()) {
            logger.warn("Recommended to configure \"licenses\". For example: `licenses = [ \"Apache-2.0\" ]`");
        }

        final ArrayList<String> errors = new ArrayList<>();
        if ((!this.getArchiveBaseName().isPresent()) || this.getArchiveBaseName().get().isEmpty()) {
            errors.add("\"archiveBaseName\"");
        }
        if ((!this.getArchiveVersion().isPresent()) || this.getArchiveVersion().get().isEmpty()) {
            errors.add("\"archiveVersion\"");
        }
        if ((!this.authors.isPresent()) || this.authors.get().isEmpty()) {
            errors.add("\"authors\"");
        }
        if ((!this.summary.isPresent()) || this.summary.get().isEmpty()) {
            errors.add("\"summary\"");
        }
        if (!errors.isEmpty()) {
            throw new GradleException(
                    "Failed to configure \"gem\" because of insufficient settings: [ "
                    + String.join(", ", errors) + " ]");
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

    private static void resetArchiveVersionToRubyStyle(final Property<String> archiveVersion) {
        if (!archiveVersion.isPresent()) {
            return;
        }

        final String lower = archiveVersion.get().toLowerCase();
        if (archiveVersion.get().equals(lower) && !archiveVersion.get().contains("-")) {
            return;
        }

        if (!lower.contains("-")) {
            archiveVersion.set(lower);
            return;
        }

        final ArrayList<String> rubyStyleVersionSplit = new ArrayList<>();
        for (final String split : lower.split("-")) {
            rubyStyleVersionSplit.add(split.toLowerCase());
        }
        archiveVersion.set(rubyStyleVersionSplit.stream().collect(Collectors.joining(".")));
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
            throw new GradleException("Failed to clean the target directory: " + root.toString(), ex);
        }
    }

    private void createBootstrap(final Project project) {
        final Path dirPath = this.getWorkingDir(project).resolve("lib").resolve("embulk").resolve(this.embulkPluginCategory.get());
        try {
            Files.createDirectories(dirPath);
        } catch (final IOException ex) {
            throw new GradleException("Failed to create the directory: " + dirPath.toString(), ex);
        }

        final Path filePath = dirPath.resolve(this.embulkPluginType.get() + ".rb");
        try (final PrintWriter writer = new PrintWriter(Files.newOutputStream(filePath, StandardOpenOption.CREATE_NEW))) {
            writer.println("Embulk::JavaPlugin.register_" + this.embulkPluginCategory.get() + "(");
            writer.println("  \"" + this.embulkPluginType.get() + "\", \"" + this.embulkPluginMainClass.get() + "\",");
            writer.println("  File.expand_path(\"../../../../classpath\", __FILE__))");
        } catch (final IOException ex) {
            throw new GradleException("Failed to create/write to the bootstrap Ruby file: " + filePath.toString(), ex);
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
            throw new GradleException("Failed to list files in the directory: " + root.toString(), ex);
        }

        return Collections.unmodifiableList(files);
    }

    private void createGemspec(final Project project, final List<Path> files) {
        final Path gemspecPath = this.getWorkingDir(project).resolve(project.getName() + ".gemspec");
        try (final PrintWriter writer = new PrintWriter(Files.newOutputStream(gemspecPath, StandardOpenOption.CREATE_NEW))) {
            this.dump(writer, project, files);
        } catch (final IOException ex) {
            throw new GradleException("Failed to create/write to the gemspec file: " + gemspecPath.toString(), ex);
        }
    }

    private Path getWorkingDir(final Project project) {
        return ((File) project.property("buildDir")).toPath().resolve("gemContents").normalize();
    }

    // https://guides.rubygems.org/specification-reference/
    // https://maven.apache.org/ref/3.6.0/maven-model/apidocs/org/apache/maven/model/Model.html
    private void dump(final PrintWriter writer, final Project project, final List<Path> files) {
        writer.println("Gem::Specification.new do |spec|");

        // REQUIRED GEMSPEC ATTRIBUTES
        writer.println("    spec.authors       = [" + renderList(this.authors.get()) + "]");
        writer.println("    spec.files         = [");
        for (final Path file : files) {
            writer.println("        \"" + pathToStringWithSlashes(file) + "\",");
        }
        writer.println("    ]");
        writer.println("    spec.name          = \"" + this.getArchiveBaseName().get() + "\"");
        writer.println("    spec.summary       = \"" + this.summary.get() + "\"");
        writer.println("    spec.version       = \"" + this.getArchiveVersion().get() + "\"");

        // RECOMMENDED GEMSPEC ATTRIBUTES
        if (project.getDescription() != null && !project.getDescription().isEmpty()) {
            writer.println("    spec.description   = \"" + project.getDescription() + "\"");
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
        if (this.dependencies.isPresent() && !this.dependencies.get().isEmpty()) {
            for (final String entry : this.dependencies.get()) {
                writer.println("    spec.add_dependency  " + entry);
            }
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

    static String pathToStringWithSlashesForTesting(final Path path) {
        return pathToStringWithSlashes(path);
    }

    /**
     * Convert the specified path to String, always separated with {@code "/"} even in Windows.
     */
    private static String pathToStringWithSlashes(final Path path) {
        if (File.separatorChar == '/') {
            return path.toString();
        }

        final StringBuilder builder = new StringBuilder();

        final Path root = path.getRoot();
        if (root != null) {
            builder.append(root.toString().replace(File.separatorChar, '/'));
        }

        final Stream<Path> pathElementStream = StreamSupport.stream(path.spliterator(), false);
        builder.append(pathElementStream.map(pathElement -> pathElement.toString()).collect(Collectors.joining("/")));
        return builder.toString();
    }

    private final Property<String> embulkPluginMainClass;
    private final Property<String> embulkPluginCategory;
    private final Property<String> embulkPluginType;

    private final ListProperty<String> authors;
    private final Property<String> summary;

    private final ListProperty<String> email;
    private final Property<String> homepage;
    // The singular `license` is to be substituted by `licenses`.
    private final ListProperty<String> licenses;
    private final ListProperty<String> dependencies;
    private final MapProperty<String, String> metadata;

    private final Property<Boolean> generateRubyCode;
    private final Property<Boolean> generateGemspec;

    private final Property<Object> jruby;
}
