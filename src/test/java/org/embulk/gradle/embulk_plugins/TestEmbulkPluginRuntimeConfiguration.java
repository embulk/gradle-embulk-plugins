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

import static org.embulk.gradle.embulk_plugins.Util.assertFileDoesContain;
import static org.embulk.gradle.embulk_plugins.Util.assertFileDoesNotContain;
import static org.embulk.gradle.embulk_plugins.Util.prepareProjectDir;
import static org.embulk.gradle.embulk_plugins.Util.runGradle;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.util.GradleVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the "runtimeClasspath" configuration in the Embulk plugins Gradle plugin.
 *
 * <p>Ths test is tentatively disabled on Windows. {@code GradleRunner} may keep some related files open.
 * It prevents JUnit 5 from removing the temporary directory ({@code TempDir}).
 *
 * @see <a href="https://github.com/embulk/gradle-embulk-plugins/runs/719452273">A failed test</a>
 */
class TestEmbulkPluginRuntimeConfiguration {
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void test(@TempDir Path tempDir) throws IOException {
        final Path projectDir = prepareProjectDir(tempDir, "testEmbulkPluginRuntimeConfiguration");

        runGradle(projectDir, "dependencies", "--configuration", "runtimeClasspath", "--write-locks");
        final Path lockfilePath;
        if (GradleVersion.current().compareTo(GradleVersion.version("7.0")) >= 0) {
            lockfilePath = projectDir.resolve("gradle.lockfile");
        } else {
            lockfilePath = projectDir.resolve("gradle/dependency-locks/runtimeClasspath.lockfile");
        }
        assertTrue(Files.exists(lockfilePath));
        assertFileDoesNotContain(lockfilePath, "javax.inject:javax.inject");
        assertFileDoesNotContain(lockfilePath, "org.apache.commons:commons-lang3");
        assertFileDoesContain(lockfilePath, "org.apache.bval:bval-jsr303");
        assertFileDoesContain(lockfilePath, "org.apache.bval:bval-core");
        assertFileDoesContain(lockfilePath, "com.github.jnr:jffi:1.2.23");

        // .lockfile does not contain classifiers by its definition.
        assertFileDoesNotContain(lockfilePath, "com.github.jnr:jffi:1.2.23:native");
    }
}
