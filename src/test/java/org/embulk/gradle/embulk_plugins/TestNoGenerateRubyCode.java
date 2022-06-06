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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests "generateRubyCode" in the Embulk plugins Gradle plugin.
 *
 * <p>This test is tentatively disabled on Windows. {@code GradleRunner} may keep some related files open.
 * It prevents JUnit 5 from removing the temporary directory ({@code TempDir}).
 *
 * @see <a href="https://github.com/embulk/gradle-embulk-plugins/runs/719452273">A failed test</a>
 */
class TestNoGenerateRubyCode {
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void test(@TempDir Path tempDir) throws IOException {
        final Path projectDir = prepareProjectDir(tempDir, "testNoGenerateRubyCode");

        runGradle(projectDir, "gem");
        assertTrue(Files.exists(projectDir.resolve("build/libs/embulk-input-test4-0.9.2.jar")));
        assertTrue(Files.exists(projectDir.resolve("build/gemContents/lib/embulk/input/test4.rb")));

        final List<String> lines = Files.readAllLines(
                projectDir.resolve("build/gemContents/lib/embulk/input/test4.rb"), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertEquals("puts \"test\"", lines.get(0));

        final Path classpathDir = projectDir.resolve("build/gemContents/classpath");
        Files.walkFileTree(classpathDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    System.out.println(projectDir.relativize(file));
                    return FileVisitResult.CONTINUE;
                }
            });
        final Path pluginJarPath = classpathDir.resolve("embulk-input-test4-0.9.2.jar");
        final Path jsonApiJarPath = classpathDir.resolve("javax.json-api-1.1.4.jar");
        final Path jffiJarPath = classpathDir.resolve("jffi-1.2.23.jar");
        final Path jffiNativeJarPath = classpathDir.resolve("jffi-1.2.23-native.jar");
        assertTrue(Files.exists(pluginJarPath));
        assertTrue(Files.exists(jsonApiJarPath));
        assertTrue(Files.exists(jffiJarPath));
        assertTrue(Files.exists(jffiNativeJarPath));
    }
}
