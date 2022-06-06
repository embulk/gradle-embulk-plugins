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
import static org.embulk.gradle.embulk_plugins.Util.assertSingleTextContentByTagName;
import static org.embulk.gradle.embulk_plugins.Util.getSingleElementByTagName;
import static org.embulk.gradle.embulk_plugins.Util.prepareProjectDir;
import static org.embulk.gradle.embulk_plugins.Util.runGradle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Tests subprojecting in Gradle in the Embulk plugins Gradle plugin.
 *
 * <p>This test is tentatively disabled on Windows. {@code GradleRunner} may keep some related files open.
 * It prevents JUnit 5 from removing the temporary directory ({@code TempDir}).
 *
 * @see <a href="https://github.com/embulk/gradle-embulk-plugins/runs/719452273">A failed test</a>
 */
class TestSubprojects {
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void test(@TempDir Path tempDir) throws IOException {
        final Path projectDir = prepareProjectDir(tempDir, "testSubprojects");
        final Path subpluginDir = projectDir.resolve("embulk-input-subprojects_subplugin");

        runGradle(projectDir, ":dependencies", "--configuration", "embulkPluginRuntime", "--write-locks");
        final Path rootLockfilePath = projectDir.resolve("gradle/dependency-locks/embulkPluginRuntime.lockfile");
        for (final String line : Files.readAllLines(rootLockfilePath, StandardCharsets.UTF_8)) {
            System.out.println(line);
        }
        assertTrue(Files.exists(rootLockfilePath));
        assertFileDoesContain(rootLockfilePath, "commons-lang:commons-lang:2.6");
        assertFileDoesContain(rootLockfilePath, "commons-io:commons-io:2.6");
        assertFileDoesNotContain(rootLockfilePath, "org.embulk.input.test_subprojects:sublib:0.6.14");
        assertFileDoesNotContain(rootLockfilePath, "org.apache.commons:commons-math3:3.6.1");

        runGradle(projectDir, ":embulk-input-subprojects_subplugin:dependencies", "--configuration", "embulkPluginRuntime", "--write-locks");
        final Path subpluginLockfilePath = subpluginDir.resolve("gradle/dependency-locks/embulkPluginRuntime.lockfile");
        for (final String line : Files.readAllLines(subpluginLockfilePath, StandardCharsets.UTF_8)) {
            System.out.println(line);
        }
        assertTrue(Files.exists(subpluginLockfilePath));
        assertFileDoesContain(subpluginLockfilePath, "commons-lang:commons-lang:2.6");
        assertFileDoesContain(subpluginLockfilePath, "org.apache.commons:commons-math3:3.6.1");
        assertFileDoesNotContain(subpluginLockfilePath, "org.embulk.input.test_subprojects:sublib:0.6.14");
        assertFileDoesNotContain(subpluginLockfilePath, "commons-io:commons-io:2.6");

        runGradle(projectDir, "publishEmbulkPluginMavenPublicationToMavenRepository", ":gem", ":embulk-input-subprojects_subplugin:gem");

        final Path rootVersionDir = projectDir.resolve("build/mavenLocalSubprojects/org/embulk/input/test_subprojects/embulk-input-subprojects_root/0.6.14");
        final Path rootJarPath = rootVersionDir.resolve("embulk-input-subprojects_root-0.6.14.jar");
        final Path rootPomPath = rootVersionDir.resolve("embulk-input-subprojects_root-0.6.14.pom");
        assertTrue(Files.exists(rootJarPath));
        assertTrue(Files.exists(rootPomPath));
        Files.walkFileTree(projectDir.resolve("build/gemContents"), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    System.out.println(projectDir.relativize(file));
                    return FileVisitResult.CONTINUE;
                }
            });
        assertTrue(Files.exists(projectDir.resolve("build/gemContents/lib/embulk/input/test_subprojects_root.rb")));
        assertTrue(Files.exists(projectDir.resolve("build/gemContents/classpath/commons-io-2.6.jar")));
        assertTrue(Files.exists(projectDir.resolve("build/gemContents/classpath/commons-lang-2.6.jar")));
        assertTrue(Files.exists(projectDir.resolve("build/gemContents/classpath/embulk-input-subprojects_root-0.6.14.jar")));
        assertTrue(Files.exists(projectDir.resolve("build/gemContents/classpath/sublib-0.6.14.jar")));

        System.out.println("Generated POM :");
        System.out.println("============================================================");
        for (final String line : Files.readAllLines(rootPomPath, StandardCharsets.UTF_8)) {
            System.out.println(line);
        }
        System.out.println("============================================================");

        final DocumentBuilderFactory factoryRoot = DocumentBuilderFactory.newInstance();

        final DocumentBuilder builderRoot;
        try {
            builderRoot = factoryRoot.newDocumentBuilder();
        } catch (final ParserConfigurationException ex) {
            throw new IOException(ex);
        }

        final Document documentRoot;
        try (final InputStream pomStream = Files.newInputStream(rootPomPath)) {
            documentRoot = builderRoot.parse(pomStream);
        } catch (final SAXException ex) {
            throw new IOException(ex);
        }

        assertEquals("1.0", documentRoot.getXmlVersion());
        final Element projectRoot = documentRoot.getDocumentElement();
        assertEquals("project", projectRoot.getTagName());
        assertSingleTextContentByTagName("4.0.0", projectRoot, "modelVersion");
        assertSingleTextContentByTagName("org.embulk.input.test_subprojects", projectRoot, "groupId");
        assertSingleTextContentByTagName("embulk-input-subprojects_root", projectRoot, "artifactId");
        assertSingleTextContentByTagName("0.6.14", projectRoot, "version");

        final Element dependenciesRoot = getSingleElementByTagName(projectRoot, "dependencies");
        final NodeList dependenciesRootEach = dependenciesRoot.getElementsByTagName("dependency");
        assertEquals(4, dependenciesRootEach.getLength());

        final Element dependencyRoot0 = (Element) dependenciesRootEach.item(0);
        assertSingleTextContentByTagName("org.embulk.input.test_subprojects", dependencyRoot0, "groupId");
        assertSingleTextContentByTagName("sublib", dependencyRoot0, "artifactId");
        assertSingleTextContentByTagName("0.6.14", dependencyRoot0, "version");
        assertSingleTextContentByTagName("compile", dependencyRoot0, "scope");

        final Element dependencyRoot1 = (Element) dependenciesRootEach.item(1);
        assertSingleTextContentByTagName("commons-io", dependencyRoot1, "groupId");
        assertSingleTextContentByTagName("commons-io", dependencyRoot1, "artifactId");
        assertSingleTextContentByTagName("2.6", dependencyRoot1, "version");
        assertSingleTextContentByTagName("compile", dependencyRoot1, "scope");

        // It is almost the same as dependencyRoot0. The only difference is "scope". It does not cause an immediate problem.
        // It looks like a problem of Gradle's POM generation with project reference (`compile project(":subproject")`).
        // TODO: Investigate the reason deep inside Gradle, and fix it.
        final Element dependencyRoot2 = (Element) dependenciesRootEach.item(2);
        assertSingleTextContentByTagName("org.embulk.input.test_subprojects", dependencyRoot2, "groupId");
        assertSingleTextContentByTagName("sublib", dependencyRoot2, "artifactId");
        assertSingleTextContentByTagName("0.6.14", dependencyRoot2, "version");
        assertSingleTextContentByTagName("runtime", dependencyRoot2, "scope");

        final Element dependencyRoot3 = (Element) dependenciesRootEach.item(3);
        assertSingleTextContentByTagName("commons-lang", dependencyRoot3, "groupId");
        assertSingleTextContentByTagName("commons-lang", dependencyRoot3, "artifactId");
        assertSingleTextContentByTagName("2.6", dependencyRoot3, "version");
        assertSingleTextContentByTagName("runtime", dependencyRoot3, "scope");

        final Path subVersionDir = projectDir.resolve("build/mavenLocalSubprojects/org/embulk/input/test_subprojects/embulk-input-subprojects_subplugin/0.6.14");
        final Path subJarPath = subVersionDir.resolve("embulk-input-subprojects_subplugin-0.6.14.jar");
        final Path subPomPath = subVersionDir.resolve("embulk-input-subprojects_subplugin-0.6.14.pom");
        assertTrue(Files.exists(subJarPath));
        assertTrue(Files.exists(subPomPath));
        Files.walkFileTree(projectDir.resolve("embulk-input-subprojects_subplugin/build/gemContents"), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    System.out.println(projectDir.relativize(file));
                    return FileVisitResult.CONTINUE;
                }
            });
        assertTrue(Files.exists(projectDir.resolve("embulk-input-subprojects_subplugin/build/gemContents/lib/embulk/input/test_subprojects_sub.rb")));
        assertTrue(Files.exists(projectDir.resolve("embulk-input-subprojects_subplugin/build/gemContents/classpath/commons-lang-2.6.jar")));
        assertTrue(Files.exists(projectDir.resolve("embulk-input-subprojects_subplugin/build/gemContents/classpath/commons-math3-3.6.1.jar")));
        assertTrue(Files.exists(projectDir.resolve("embulk-input-subprojects_subplugin/build/gemContents/classpath/embulk-input-subprojects_subplugin-0.6.14.jar")));
        assertTrue(Files.exists(projectDir.resolve("embulk-input-subprojects_subplugin/build/gemContents/classpath/sublib-0.6.14.jar")));

        System.out.println("Generated POM :");
        System.out.println("============================================================");
        for (final String line : Files.readAllLines(subPomPath, StandardCharsets.UTF_8)) {
            System.out.println(line);
        }
        System.out.println("============================================================");

        final DocumentBuilderFactory factorySub = DocumentBuilderFactory.newInstance();

        final DocumentBuilder builderSub;
        try {
            builderSub = factorySub.newDocumentBuilder();
        } catch (final ParserConfigurationException ex) {
            throw new IOException(ex);
        }

        final Document documentSub;
        try (final InputStream pomStream = Files.newInputStream(subPomPath)) {
            documentSub = builderSub.parse(pomStream);
        } catch (final SAXException ex) {
            throw new IOException(ex);
        }

        assertEquals("1.0", documentSub.getXmlVersion());
        final Element project = documentSub.getDocumentElement();
        assertEquals("project", project.getTagName());
        assertSingleTextContentByTagName("4.0.0", project, "modelVersion");
        assertSingleTextContentByTagName("org.embulk.input.test_subprojects", project, "groupId");
        assertSingleTextContentByTagName("embulk-input-subprojects_subplugin", project, "artifactId");
        assertSingleTextContentByTagName("0.6.14", project, "version");

        final Element dependenciesSub = getSingleElementByTagName(project, "dependencies");
        final NodeList dependenciesSubEach = dependenciesSub.getElementsByTagName("dependency");
        assertEquals(4, dependenciesSubEach.getLength());

        final Element dependencySub0 = (Element) dependenciesSubEach.item(0);
        assertSingleTextContentByTagName("org.embulk.input.test_subprojects", dependencySub0, "groupId");
        assertSingleTextContentByTagName("sublib", dependencySub0, "artifactId");
        assertSingleTextContentByTagName("0.6.14", dependencySub0, "version");
        assertSingleTextContentByTagName("compile", dependencySub0, "scope");

        final Element dependencySub1 = (Element) dependenciesSubEach.item(1);
        assertSingleTextContentByTagName("org.apache.commons", dependencySub1, "groupId");
        assertSingleTextContentByTagName("commons-math3", dependencySub1, "artifactId");
        assertSingleTextContentByTagName("3.6.1", dependencySub1, "version");
        assertSingleTextContentByTagName("compile", dependencySub1, "scope");

        // It is almost the same as dependencySub0. The only difference is "scope". It does not cause an immediate problem.
        // It looks like a problem of Gradle's POM generation with project reference (`compile project(":subproject")`).
        // TODO: Investigate the reason deep inside Gradle, and fix it.
        final Element dependencySub2 = (Element) dependenciesSubEach.item(2);
        assertSingleTextContentByTagName("org.embulk.input.test_subprojects", dependencySub2, "groupId");
        assertSingleTextContentByTagName("sublib", dependencySub2, "artifactId");
        assertSingleTextContentByTagName("0.6.14", dependencySub2, "version");
        assertSingleTextContentByTagName("runtime", dependencySub2, "scope");

        final Element dependencySub3 = (Element) dependenciesSubEach.item(3);
        assertSingleTextContentByTagName("commons-lang", dependencySub3, "groupId");
        assertSingleTextContentByTagName("commons-lang", dependencySub3, "artifactId");
        assertSingleTextContentByTagName("2.6", dependencySub3, "version");
        assertSingleTextContentByTagName("runtime", dependencySub3, "scope");
    }
}
