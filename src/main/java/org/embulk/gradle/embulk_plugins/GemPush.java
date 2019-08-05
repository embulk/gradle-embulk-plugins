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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.JavaExec;

/**
 * A Gradle task to push (publish) a gem.
 *
 * <p>Configuration example:
 *
 * <pre>{@code gemPush {
 *   host = "https://rubygems.org"
 *
 *   // JRuby artifact to execute `gem push`.
 *   // NOTE: Not recommended for users to configure it because this Gradle plugin expects a fixed version of JRuby.
 *   // For example, a certain version of `gem` would be required for command line options specified.
 *   // This option is here just for a quick hack or debugging.
 *   jruby = "org.jruby:jruby-complete:9.X.Y.Z"
 * }}</pre>
 */
class GemPush extends JavaExec {
    @Inject
    public GemPush() {
        super();

        final ObjectFactory objectFactory = this.getProject().getObjects();
        this.host = objectFactory.property(String.class);

        this.jruby = objectFactory.property(Object.class);
        this.jruby.set(EmbulkPluginsPlugin.DEFAULT_JRUBY);
    }

    @Override
    public void exec() {
        if ((!this.getHost().isPresent()) || this.getHost().get().isEmpty()) {
            throw new GradleException("`host` must be specified in `gemPush`.");
        }

        final Project project = this.getProject();
        final Logger logger = project.getLogger();

        final Gem gemTask = (Gem) project.getTasks().getByName("gem");
        final File archiveFile = gemTask.getArchiveFile().get().getAsFile();

        final Configuration jrubyConfiguration = project.getConfigurations().detachedConfiguration();
        final Dependency jrubyDependency = project.getDependencies().create(this.jruby);
        jrubyConfiguration.withDependencies(dependencies -> {
            dependencies.add(jrubyDependency);
        });

        final FileCollection jrubyFiles = (FileCollection) jrubyConfiguration;
        this.setIgnoreExitValue(false);
        this.setWorkingDir(archiveFile.toPath().getParent().toFile());
        this.setClasspath(jrubyFiles);
        this.setMain("org.jruby.Main");

        final ArrayList<String> args = new ArrayList<>();
        args.add("-rjars/setup");
        args.add("-S");
        args.add("gem");
        args.add("push");
        args.add(archiveFile.toString());
        args.add("--verbose");
        this.setArgs(args);

        if (logger.isLifecycleEnabled()) {
            logger.lifecycle(args.stream().collect(Collectors.joining(" ", "Exec: `java org.jruby.Main ", "`")));
            logger.lifecycle(
                    jrubyFiles.getFiles().stream().map(File::toString).collect(Collectors.joining("],[", "Classpath: [", "]")));
        }

        final HashMap<String, Object> environments = new HashMap<>();
        environments.putAll(System.getenv());
        environments.putAll(this.getEnvironment());

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

        // Set the RubyGems host for sure.
        environments.put("RUBYGEMS_HOST", this.getHost().get());

        this.setEnvironment(environments);

        super.exec();

        logger.lifecycle("Exec `gem push` finished successfully.");
    }

    public Property<String> getHost() {
        return this.host;
    }

    private final Property<String> host;

    private final Property<Object> jruby;
}
