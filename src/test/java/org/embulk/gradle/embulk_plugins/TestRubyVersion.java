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

import static org.embulk.gradle.embulk_plugins.Util.prepareProjectDir;
import static org.embulk.gradle.embulk_plugins.Util.runGradle;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the Ruby version in the Embulk plugins Gradle plugin.
 *
 * <p>This test is tentatively disabled on Windows. {@code GradleRunner} may keep some related files open.
 * It prevents JUnit 5 from removing the temporary directory ({@code TempDir}).
 *
 * @see <a href="https://github.com/embulk/gradle-embulk-plugins/runs/719452273">A failed test</a>
 */
class TestRubyVersion {
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void test(@TempDir Path tempDir) throws IOException {
        final Path projectDir = prepareProjectDir(tempDir, "testRubyVersion");

        runGradle(projectDir, "gem");
        assertTrue(Files.exists(projectDir.resolve("build/libs/embulk-input-test5-0.1.41-SNAPSHOT.jar")));
        assertTrue(Files.exists(projectDir.resolve("build/gems/embulk-input-test5-0.1.41.snapshot-java.gem")));
    }
}
