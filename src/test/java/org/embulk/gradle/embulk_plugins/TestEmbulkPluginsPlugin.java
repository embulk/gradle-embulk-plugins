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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Tests the Embulk plugins Gradle plugin by {@code org.gradle.testkit.runner.GradleRunner}.
 *
 * <p>Those tests are tentatively disabled on Windows. {@code GradleRunner} may keep some related files open.
 * It prevents JUnit 5 from removing the temporary directory ({@code TempDir}).
 *
 * @see <a href="https://github.com/embulk/gradle-embulk-plugins/runs/719452273">A failed test</a>
 */
class TestEmbulkPluginsPlugin {
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void testPublish(@TempDir Path tempDir) throws IOException {
        final Path projectDir = prepareProjectDir(tempDir, "testPublish");

        runGradle(projectDir, "jar");

        assertTrue(Files.exists(projectDir.resolve("build/libs/embulk-input-test1-0.2.5.jar")));

        runGradle(projectDir, "publishEmbulkPluginMavenPublicationToMavenRepository");

        final Path versionDir = projectDir.resolve("build/mavenPublishLocal/org/embulk/input/test1/embulk-input-test1/0.2.5");
        final Path jarPath = versionDir.resolve("embulk-input-test1-0.2.5.jar");
        final Path pomPath = versionDir.resolve("embulk-input-test1-0.2.5.pom");
        assertTrue(Files.exists(jarPath));

        final JarURLConnection connection = openJarUrlConnection(jarPath);
        final Manifest manifest = connection.getManifest();
        final Attributes manifestAttributes = manifest.getMainAttributes();
        assertEquals("org.embulk.input.test1.Test1InputPlugin", manifestAttributes.getValue("Embulk-Plugin-Main-Class"));
        assertEquals("0", manifestAttributes.getValue("Embulk-Plugin-Spi-Version"));

        assertTrue(Files.exists(pomPath));

        System.out.println("Generated POM :");
        System.out.println("============================================================");
        for (final String line : Files.readAllLines(pomPath, StandardCharsets.UTF_8)) {
            System.out.println(line);
        }
        System.out.println("============================================================");

        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        final DocumentBuilder builder;
        try {
            builder = factory.newDocumentBuilder();
        } catch (final ParserConfigurationException ex) {
            throw new IOException(ex);
        }

        final Document document;
        try (final InputStream pomStream = Files.newInputStream(pomPath)) {
            document = builder.parse(pomStream);
        } catch (final SAXException ex) {
            throw new IOException(ex);
        }

        assertEquals("1.0", document.getXmlVersion());
        final Element project = document.getDocumentElement();
        assertEquals("project", project.getTagName());
        assertSingleTextContentByTagName("4.0.0", project, "modelVersion");
        assertSingleTextContentByTagName("org.embulk.input.test1", project, "groupId");
        assertSingleTextContentByTagName("embulk-input-test1", project, "artifactId");
        assertSingleTextContentByTagName("0.2.5", project, "version");

        final Element dependencies = getSingleElementByTagName(project, "dependencies");
        final NodeList dependenciesEach = dependencies.getElementsByTagName("dependency");

        // In case of a dependency with a classifier, "compile" and "runtime" are duplicated.
        // The behavior is not intended, not very good, but acceptable as of now.
        //
        // TODO: Upgrade the base Gradle, and test it in later Gradle.
        assertEquals(6, dependenciesEach.getLength());

        // "org.apache.commons:commons-text:1.7" => originally in build.gradle as "compile".
        // It depends on "org.apache.commons:commons-lang3:3.9".
        //
        // https://search.maven.org/artifact/org.apache.commons/commons-text/1.7/jar
        // https://repo1.maven.org/maven2/org/apache/commons/commons-text/1.7/
        final Element dependency0 = (Element) dependenciesEach.item(0);
        assertSingleTextContentByTagName("org.apache.commons", dependency0, "groupId");
        assertSingleTextContentByTagName("commons-text", dependency0, "artifactId");
        assertSingleTextContentByTagName("1.7", dependency0, "version");
        assertSingleTextContentByTagName("compile", dependency0, "scope");

        // "com.github.jnr:jffi:1.2.23" => originally in build.gradle as "compile".
        final Element dependency1 = (Element) dependenciesEach.item(1);
        assertSingleTextContentByTagName("com.github.jnr", dependency1, "groupId");
        assertSingleTextContentByTagName("jffi", dependency1, "artifactId");
        assertSingleTextContentByTagName("1.2.23", dependency1, "version");
        assertNoElement(dependency1, "classifier");
        assertSingleTextContentByTagName("compile", dependency1, "scope");

        // "com.github.jnr:jffi:1.2.23:native" => originally in build.gradle as "compile".
        final Element dependency2 = (Element) dependenciesEach.item(2);
        assertSingleTextContentByTagName("com.github.jnr", dependency2, "groupId");
        assertSingleTextContentByTagName("jffi", dependency2, "artifactId");
        assertSingleTextContentByTagName("1.2.23", dependency2, "version");
        assertSingleTextContentByTagName("native", dependency2, "classifier");
        assertSingleTextContentByTagName("compile", dependency2, "scope");

        // "com.github.jnr:jffi:1.2.23" => added (duplicated) by the Gradle plugin as "runtime".
        final Element dependency3 = (Element) dependenciesEach.item(3);
        assertSingleTextContentByTagName("com.github.jnr", dependency3, "groupId");
        assertSingleTextContentByTagName("jffi", dependency3, "artifactId");
        assertSingleTextContentByTagName("1.2.23", dependency3, "version");
        assertNoElement(dependency3, "classifier");
        assertSingleTextContentByTagName("runtime", dependency3, "scope");

        // "com.github.jnr:jffi:1.2.23:native" => added (duplicated) by the Gradle plugin as "runtime".
        final Element dependency4 = (Element) dependenciesEach.item(4);
        assertSingleTextContentByTagName("com.github.jnr", dependency4, "groupId");
        assertSingleTextContentByTagName("jffi", dependency4, "artifactId");
        assertSingleTextContentByTagName("1.2.23", dependency4, "version");
        assertSingleTextContentByTagName("native", dependency4, "classifier");
        assertSingleTextContentByTagName("runtime", dependency4, "scope");

        // "org.apache.commons:commons-lang3:3.9" => added by the Gradle plugin as "runtime".
        final Element dependency5 = (Element) dependenciesEach.item(5);
        assertSingleTextContentByTagName("org.apache.commons", dependency5, "groupId");
        assertSingleTextContentByTagName("commons-lang3", dependency5, "artifactId");
        assertSingleTextContentByTagName("3.9", dependency5, "version");
        assertSingleTextContentByTagName("runtime", dependency5, "scope");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void testEmbulkPluginRuntimeConfiguration(@TempDir Path tempDir) throws IOException {
        final Path projectDir = prepareProjectDir(tempDir, "testEmbulkPluginRuntimeConfiguration");

        runGradle(projectDir, "dependencies", "--configuration", "embulkPluginRuntime", "--write-locks");
        final Path lockfilePath = projectDir.resolve("gradle/dependency-locks/embulkPluginRuntime.lockfile");
        assertTrue(Files.exists(lockfilePath));
        assertFileDoesNotContain(lockfilePath, "javax.inject:javax.inject");
        assertFileDoesNotContain(lockfilePath, "org.apache.commons:commons-lang3");
        assertFileDoesContain(lockfilePath, "org.apache.bval:bval-jsr303");
        assertFileDoesContain(lockfilePath, "org.apache.bval:bval-core");
        assertFileDoesContain(lockfilePath, "com.github.jnr:jffi:1.2.23");

        // .lockfile does not contain classifiers by its definition.
        assertFileDoesNotContain(lockfilePath, "com.github.jnr:jffi:1.2.23:native");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void testVariableMainJar(@TempDir Path tempDir) throws IOException {
        final Path projectDir = prepareProjectDir(tempDir, "testVariableMainJar");

        runGradle(projectDir, "jar");
        assertTrue(Files.exists(projectDir.resolve("build/libs/embulk-input-test3-0.2.8.jar")));

        runGradle(projectDir, "publishEmbulkPluginMavenPublicationToMavenRepository");
        final Path versionDir = projectDir.resolve("build/mavenLocal3/org/embulk/input/test3/embulk-input-test3/0.2.8");
        final Path jarPath = versionDir.resolve("embulk-input-test3-0.2.8.jar");
        final Path pomPath = versionDir.resolve("embulk-input-test3-0.2.8.pom");

        assertTrue(Files.exists(jarPath));
        try (final JarFile jarFile = new JarFile(jarPath.toFile())) {
            boolean found = false;
            final Enumeration<JarEntry> entries = jarFile.entries();
            for (; entries.hasMoreElements(); ) {
                final JarEntry entry = entries.nextElement();
                if (entry.getName().equals("javax/json/Json.class")) {
                    found = true;
                    break;
                }
            }
            assertTrue(found);
        }

        final JarURLConnection connection = openJarUrlConnection(jarPath);
        final Manifest manifest = connection.getManifest();
        final Attributes manifestAttributes = manifest.getMainAttributes();
        assertEquals("org.embulk.input.test3.Test3InputPlugin", manifestAttributes.getValue("Embulk-Plugin-Main-Class"));
        assertEquals("Bar", manifestAttributes.getValue("Foo"));

        assertTrue(Files.exists(pomPath));

        System.out.println("Generated POM :");
        System.out.println("============================================================");
        for (final String line : Files.readAllLines(pomPath, StandardCharsets.UTF_8)) {
            System.out.println(line);
        }
        System.out.println("============================================================");

        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        final DocumentBuilder builder;
        try {
            builder = factory.newDocumentBuilder();
        } catch (final ParserConfigurationException ex) {
            throw new IOException(ex);
        }

        final Document document;
        try (final InputStream pomStream = Files.newInputStream(pomPath)) {
            document = builder.parse(pomStream);
        } catch (final SAXException ex) {
            throw new IOException(ex);
        }

        assertEquals("1.0", document.getXmlVersion());
        final Element project = document.getDocumentElement();
        assertEquals("project", project.getTagName());
        assertSingleTextContentByTagName("4.0.0", project, "modelVersion");
        assertSingleTextContentByTagName("org.embulk.input.test3", project, "groupId");
        assertSingleTextContentByTagName("embulk-input-test3", project, "artifactId");
        assertSingleTextContentByTagName("0.2.8", project, "version");

        assertNoElementByTagName(project, "dependencies");  // No dependencies.
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void testNoGenerateRubyCode(@TempDir Path tempDir) throws IOException {
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

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void testRubyVersion(@TempDir Path tempDir) throws IOException {
        final Path projectDir = prepareProjectDir(tempDir, "testRubyVersion");

        runGradle(projectDir, "gem");
        assertTrue(Files.exists(projectDir.resolve("build/libs/embulk-input-test5-0.1.41-SNAPSHOT.jar")));
        assertTrue(Files.exists(projectDir.resolve("build/gems/embulk-input-test5-0.1.41.snapshot-java.gem")));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void testSubprojects(@TempDir Path tempDir) throws IOException {
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

    private static Path prepareProjectDir(final Path tempDir, final String testProjectName) {
        final String resourceName = testProjectName + System.getProperty("file.separator") + "build.gradle";
        final Path resourceDir;
        try {
            final URL resourceUrl = TestEmbulkPluginsPlugin.class.getClassLoader().getResource(resourceName);
            if (resourceUrl == null) {
                throw new FileNotFoundException(resourceName + " is not found.");
            }
            resourceDir = Paths.get(resourceUrl.toURI()).getParent();
        } catch (final Exception ex) {
            fail("Failed to find a test resource.", ex);
            throw new RuntimeException(ex);  // Never reaches.
        }

        final Path projectDir;
        try {
            projectDir = Files.createDirectory(tempDir.resolve(testProjectName));
        } catch (final Exception ex) {
            fail("Failed to create a test directory.", ex);
            throw new RuntimeException(ex);  // Never reaches.
        }

        try {
            copyFilesRecursively(resourceDir, projectDir);
        } catch (final Exception ex) {
            fail("Failed to copy test files.", ex);
            throw new RuntimeException(ex);  // Never reaches.
        }

        return projectDir;
    }

    private static void copyFilesRecursively(final Path source, final Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
                final Path target = destination.resolve(source.relativize(dir));
                Files.createDirectories(target);
                System.out.println(target.toString() + System.getProperty("file.separator"));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                final Path target = destination.resolve(source.relativize(file));
                Files.copy(file, target);
                System.out.println(target);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static BuildResult runGradle(final Path projectDir, final String... args) {
        final ArrayList<String> argsList = new ArrayList<>();
        argsList.addAll(Arrays.asList(args));
        argsList.add("--stacktrace");
        argsList.add("--info");
        final BuildResult result = newGradleRunner(projectDir, argsList).build();
        System.out.println("Running 'gradle " + String.join(" ", argsList) + "' :");
        System.out.println("============================================================");
        System.out.print(result.getOutput());
        System.out.println("============================================================");
        return result;
    }

    private static void assertFileDoesContain(final Path path, final String expected) throws IOException {
        try (final Stream<String> lines = Files.newBufferedReader(path).lines()) {
            final boolean found = lines.filter(actualLine -> {
                return actualLine.contains(expected);
            }).findAny().isPresent();
            if (!found) {
                fail("\"" + path.toString() + "\" does not contain \"" + expected + "\".");
            }
        }
    }

    private static void assertFileDoesNotContain(final Path path, final String notExpected) throws IOException {
        try (final Stream<String> lines = Files.newBufferedReader(path).lines()) {
            lines.forEach(actualLine -> {
                assertFalse(actualLine.contains(notExpected));
            });
        }
    }

    private static GradleRunner newGradleRunner(final Path projectDir, final List<String> args) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(args)
                .withDebug(true)
                .withPluginClasspath();
    }

    private static JarURLConnection openJarUrlConnection(final Path jarPath) throws IOException {
        final URL jarUrl = new URL("jar:" + jarPath.toUri().toURL().toString() + "!/");
        return (JarURLConnection) jarUrl.openConnection();
    }

    private static Element getSingleElementByTagName(final Element element, final String name) {
        final NodeList childNodeList = element.getChildNodes();
        final ArrayList<Element> matchedElements = new ArrayList<>();
        for (int i = 0; i < childNodeList.getLength(); ++i) {
            final Node foundNode = childNodeList.item(i);
            if (foundNode instanceof Element) {
                final Element foundElement = (Element) foundNode;
                if (foundElement.getTagName().equals(name)) {
                    matchedElements.add(foundElement);
                }
            }
        }
        assertEquals(1, matchedElements.size());
        return matchedElements.get(0);
    }

    private static void assertNoElementByTagName(final Element element, final String name) {
        final NodeList childNodeList = element.getChildNodes();
        final ArrayList<Element> matchedElements = new ArrayList<>();
        for (int i = 0; i < childNodeList.getLength(); ++i) {
            final Node foundNode = childNodeList.item(i);
            if (foundNode instanceof Element) {
                final Element foundElement = (Element) foundNode;
                if (foundElement.getTagName().equals(name)) {
                    matchedElements.add(foundElement);
                }
            }
        }
        assertEquals(0, matchedElements.size());
    }

    private static void assertSingleTextContentByTagName(final String expected, final Element element, final String name) {
        final Element childElement = getSingleElementByTagName(element, name);
        assertEquals(expected, childElement.getTextContent());
    }

    private static void assertNoElement(final Element element, final String name) {
        final NodeList childNodeList = element.getChildNodes();
        final ArrayList<Element> matchedElements = new ArrayList<>();
        for (int i = 0; i < childNodeList.getLength(); ++i) {
            final Node foundNode = childNodeList.item(i);
            if (foundNode instanceof Element) {
                final Element foundElement = (Element) foundNode;
                if (foundElement.getTagName().equals(name)) {
                    matchedElements.add(foundElement);
                }
            }
        }
        assertEquals(0, matchedElements.size());
    }
}
