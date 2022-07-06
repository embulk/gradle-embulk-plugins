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

import static org.embulk.gradle.embulk_plugins.Util.assertExcludeAll;
import static org.embulk.gradle.embulk_plugins.Util.assertFileDoesContain;
import static org.embulk.gradle.embulk_plugins.Util.assertFileDoesNotContain;
import static org.embulk.gradle.embulk_plugins.Util.assertNoElement;
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
import org.gradle.util.GradleVersion;
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

        runGradle(projectDir, ":dependencies", "--configuration", "runtimeClasspath", "--write-locks");
        final Path rootLockfilePath;
        if (GradleVersion.current().compareTo(GradleVersion.version("7.0")) >= 0) {
            rootLockfilePath = projectDir.resolve("gradle.lockfile");
        } else {
            rootLockfilePath = projectDir.resolve("gradle/dependency-locks/runtimeClasspath.lockfile");
        }
        for (final String line : Files.readAllLines(rootLockfilePath, StandardCharsets.UTF_8)) {
            System.out.println(line);
        }
        assertTrue(Files.exists(rootLockfilePath));
        assertFileDoesContain(rootLockfilePath, "commons-lang:commons-lang:2.6");
        assertFileDoesContain(rootLockfilePath, "commons-io:commons-io:2.6");
        assertFileDoesNotContain(rootLockfilePath, "org.embulk.input.test_subprojects:sublib:0.6.14");
        assertFileDoesNotContain(rootLockfilePath, "org.apache.commons:commons-math3:3.6.1");

        runGradle(projectDir, ":embulk-input-subprojects_subplugin:dependencies", "--configuration", "runtimeClasspath", "--write-locks");
        final Path subpluginLockfilePath;
        if (GradleVersion.current().compareTo(GradleVersion.version("7.0")) >= 0) {
            subpluginLockfilePath = subpluginDir.resolve("gradle.lockfile");
        } else {
            subpluginLockfilePath = subpluginDir.resolve("gradle/dependency-locks/runtimeClasspath.lockfile");
        }
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
        assertEquals(7, dependenciesRootEach.getLength());

        // "org.embulk:embulk-spi:0.10.35" => originally in build.gradle as "compileOnly".
        final Element dependencyRoot0 = (Element) dependenciesRootEach.item(0);
        assertSingleTextContentByTagName("org.embulk", dependencyRoot0, "groupId");
        assertSingleTextContentByTagName("embulk-spi", dependencyRoot0, "artifactId");
        assertSingleTextContentByTagName("0.10.35", dependencyRoot0, "version");
        assertNoElement(dependencyRoot0, "classifier");
        assertSingleTextContentByTagName("provided", dependencyRoot0, "scope");
        assertExcludeAll(dependencyRoot0);

        // "org.embulk:embulk-api:0.10.35" => originally in build.gradle as "compileOnly".
        final Element dependencyRoot1 = (Element) dependenciesRootEach.item(1);
        assertSingleTextContentByTagName("org.embulk", dependencyRoot1, "groupId");
        assertSingleTextContentByTagName("embulk-api", dependencyRoot1, "artifactId");
        assertSingleTextContentByTagName("0.10.35", dependencyRoot1, "version");
        assertNoElement(dependencyRoot1, "classifier");
        assertSingleTextContentByTagName("provided", dependencyRoot1, "scope");
        assertExcludeAll(dependencyRoot1);

        // "org.msgpack:msgpac-core:0.8.11" => added by the Gradle plugin as "provided".
        final Element dependencyRoot2 = (Element) dependenciesRootEach.item(2);
        assertSingleTextContentByTagName("org.msgpack", dependencyRoot2, "groupId");
        assertSingleTextContentByTagName("msgpack-core", dependencyRoot2, "artifactId");
        assertSingleTextContentByTagName("0.8.11", dependencyRoot2, "version");
        assertNoElement(dependencyRoot2, "classifier");
        assertSingleTextContentByTagName("provided", dependencyRoot2, "scope");
        assertExcludeAll(dependencyRoot2);

        // "org.slf4j:slf4j-api:1.7.30" => added by the Gradle plugin as "provided".
        final Element dependencyRoot3 = (Element) dependenciesRootEach.item(3);
        assertSingleTextContentByTagName("org.slf4j", dependencyRoot3, "groupId");
        assertSingleTextContentByTagName("slf4j-api", dependencyRoot3, "artifactId");
        assertSingleTextContentByTagName("1.7.30", dependencyRoot3, "version");
        assertNoElement(dependencyRoot3, "classifier");
        assertSingleTextContentByTagName("provided", dependencyRoot3, "scope");
        assertExcludeAll(dependencyRoot3);

        final Element dependencyRoot4 = (Element) dependenciesRootEach.item(4);
        assertSingleTextContentByTagName("org.embulk.input.test_subprojects", dependencyRoot4, "groupId");
        assertSingleTextContentByTagName("sublib", dependencyRoot4, "artifactId");
        assertSingleTextContentByTagName("0.6.14", dependencyRoot4, "version");
        assertNoElement(dependencyRoot4, "classifier");
        assertSingleTextContentByTagName("runtime", dependencyRoot4, "scope");
        assertExcludeAll(dependencyRoot4);

        final Element dependencyRoot5 = (Element) dependenciesRootEach.item(5);
        assertSingleTextContentByTagName("commons-io", dependencyRoot5, "groupId");
        assertSingleTextContentByTagName("commons-io", dependencyRoot5, "artifactId");
        assertSingleTextContentByTagName("2.6", dependencyRoot5, "version");
        assertNoElement(dependencyRoot5, "classifier");
        assertSingleTextContentByTagName("compile", dependencyRoot5, "scope");
        assertExcludeAll(dependencyRoot5);

        final Element dependencyRoot6 = (Element) dependenciesRootEach.item(6);
        assertSingleTextContentByTagName("commons-lang", dependencyRoot6, "groupId");
        assertSingleTextContentByTagName("commons-lang", dependencyRoot6, "artifactId");
        assertSingleTextContentByTagName("2.6", dependencyRoot6, "version");
        assertNoElement(dependencyRoot6, "classifier");
        assertSingleTextContentByTagName("runtime", dependencyRoot6, "scope");
        assertExcludeAll(dependencyRoot6);

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
        assertEquals(7, dependenciesSubEach.getLength());

        // "org.embulk:embulk-spi:0.10.35" => originally in build.gradle as "compileOnly".
        final Element dependencySub0 = (Element) dependenciesSubEach.item(0);
        assertSingleTextContentByTagName("org.embulk", dependencySub0, "groupId");
        assertSingleTextContentByTagName("embulk-spi", dependencySub0, "artifactId");
        assertSingleTextContentByTagName("0.10.35", dependencySub0, "version");
        assertNoElement(dependencySub0, "classifier");
        assertSingleTextContentByTagName("provided", dependencySub0, "scope");
        assertExcludeAll(dependencySub0);

        // "org.embulk:embulk-api:0.10.35" => originally in build.gradle as "compileOnly".
        final Element dependencySub1 = (Element) dependenciesSubEach.item(1);
        assertSingleTextContentByTagName("org.embulk", dependencySub1, "groupId");
        assertSingleTextContentByTagName("embulk-api", dependencySub1, "artifactId");
        assertSingleTextContentByTagName("0.10.35", dependencySub1, "version");
        assertNoElement(dependencySub1, "classifier");
        assertSingleTextContentByTagName("provided", dependencySub1, "scope");
        assertExcludeAll(dependencySub1);

        // "org.msgpack:msgpac-core:0.8.11" => added by the Gradle plugin as "provided".
        final Element dependencySub2 = (Element) dependenciesSubEach.item(2);
        assertSingleTextContentByTagName("org.msgpack", dependencySub2, "groupId");
        assertSingleTextContentByTagName("msgpack-core", dependencySub2, "artifactId");
        assertSingleTextContentByTagName("0.8.11", dependencySub2, "version");
        assertNoElement(dependencySub2, "classifier");
        assertSingleTextContentByTagName("provided", dependencySub2, "scope");
        assertExcludeAll(dependencySub2);

        // "org.slf4j:slf4j-api:1.7.30" => added by the Gradle plugin as "provided".
        final Element dependencySub3 = (Element) dependenciesSubEach.item(3);
        assertSingleTextContentByTagName("org.slf4j", dependencySub3, "groupId");
        assertSingleTextContentByTagName("slf4j-api", dependencySub3, "artifactId");
        assertSingleTextContentByTagName("1.7.30", dependencySub3, "version");
        assertNoElement(dependencySub3, "classifier");
        assertSingleTextContentByTagName("provided", dependencySub3, "scope");
        assertExcludeAll(dependencySub3);

        final Element dependencySub4 = (Element) dependenciesSubEach.item(4);
        assertSingleTextContentByTagName("org.embulk.input.test_subprojects", dependencySub4, "groupId");
        assertSingleTextContentByTagName("sublib", dependencySub4, "artifactId");
        assertSingleTextContentByTagName("0.6.14", dependencySub4, "version");
        assertNoElement(dependencySub4, "classifier");
        assertSingleTextContentByTagName("runtime", dependencySub4, "scope");
        assertExcludeAll(dependencySub4);

        final Element dependencySub5 = (Element) dependenciesSubEach.item(5);
        assertSingleTextContentByTagName("org.apache.commons", dependencySub5, "groupId");
        assertSingleTextContentByTagName("commons-math3", dependencySub5, "artifactId");
        assertSingleTextContentByTagName("3.6.1", dependencySub5, "version");
        assertNoElement(dependencySub5, "classifier");
        assertSingleTextContentByTagName("compile", dependencySub5, "scope");
        assertExcludeAll(dependencySub5);

        final Element dependencySub6 = (Element) dependenciesSubEach.item(6);
        assertSingleTextContentByTagName("commons-lang", dependencySub6, "groupId");
        assertSingleTextContentByTagName("commons-lang", dependencySub6, "artifactId");
        assertSingleTextContentByTagName("2.6", dependencySub6, "version");
        assertNoElement(dependencySub6, "classifier");
        assertSingleTextContentByTagName("runtime", dependencySub6, "scope");
        assertExcludeAll(dependencySub6);
    }
}
