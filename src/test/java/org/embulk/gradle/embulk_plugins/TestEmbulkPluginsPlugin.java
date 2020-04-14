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

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

class TestEmbulkPluginsPlugin {
    @Test
    public void testPublish(@TempDir Path tempDir) throws IOException {
        final Path projectDir = Files.createDirectory(tempDir.resolve("embulk-input-test1"));
        Files.copy(TestEmbulkPluginsPlugin.class.getClassLoader().getResourceAsStream("build.gradle"),
                   projectDir.resolve("build.gradle"));

        this.build(projectDir, "jar");
        assertTrue(Files.exists(projectDir.resolve("build/libs/embulk-input-test1-0.2.5.jar")));

        this.build(projectDir, "publishEmbulkPluginMavenPublicationToMavenRepository");
        final Path versionDir = projectDir.resolve("build/mavenPublishLocal/org/embulk/input/test1/embulk-input-test1/0.2.5");
        final Path jarPath = versionDir.resolve("embulk-input-test1-0.2.5.jar");
        final Path pomPath = versionDir.resolve("embulk-input-test1-0.2.5.pom");
        assertTrue(Files.exists(jarPath));
        assertManifest(jarPath);
        assertTrue(Files.exists(pomPath));
        assertPom(pomPath);
    }

    @Test
    public void testEmbulkPluginRuntimeConfiguration(@TempDir Path tempDir) throws IOException {
        final Path projectDir = Files.createDirectory(tempDir.resolve("embulk-input-test2"));
        Files.copy(TestEmbulkPluginsPlugin.class.getClassLoader().getResourceAsStream("build2.gradle"),
                   projectDir.resolve("build.gradle"));

        this.build(projectDir, "dependencies", "--configuration", "embulkPluginRuntime", "--write-locks");
        final Path lockfilePath = projectDir.resolve("gradle/dependency-locks/embulkPluginRuntime.lockfile");
        assertTrue(Files.exists(lockfilePath));
        assertFileDoesNotContain(lockfilePath, "javax.inject:javax.inject");
    }

    @Test
    public void testVariableMainJar(@TempDir Path tempDir) throws IOException {
        final Path projectDir = Files.createDirectory(tempDir.resolve("embulk-input-test3"));
        Files.copy(TestEmbulkPluginsPlugin.class.getClassLoader().getResourceAsStream("build3.gradle"),
                   projectDir.resolve("build.gradle"));

        this.build(projectDir, "jar");
        assertTrue(Files.exists(projectDir.resolve("build/libs/embulk-input-test3-0.2.8.jar")));

        this.build(projectDir, "publishEmbulkPluginMavenPublicationToMavenRepository");
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

        final Attributes manifestAttributes = getManifestAttributes(jarPath);
        assertEquals("org.embulk.input.test3.Test3InputPlugin", manifestAttributes.getValue("Embulk-Plugin-Main-Class"));
        assertEquals("Bar", manifestAttributes.getValue("Foo"));

        assertTrue(Files.exists(pomPath));
        assertPom3(pomPath);
    }

    @Test
    public void testNoGenerateRubyCode(@TempDir Path tempDir) throws IOException {
        final Path projectDir = Files.createDirectory(tempDir.resolve("embulk-input-test4"));
        Files.copy(TestEmbulkPluginsPlugin.class.getClassLoader().getResourceAsStream("build4.gradle"),
                   projectDir.resolve("build.gradle"));
        Files.createDirectories(projectDir.resolve("lib/embulk/input/"));
        Files.copy(TestEmbulkPluginsPlugin.class.getClassLoader().getResourceAsStream("lib/embulk/input/test4.rb"),
                   projectDir.resolve("lib/embulk/input/test4.rb"));

        this.build(projectDir, "gem");
        assertTrue(Files.exists(projectDir.resolve("build/libs/embulk-input-test4-0.9.2.jar")));
        assertTrue(Files.exists(projectDir.resolve("build/gemContents/lib/embulk/input/test4.rb")));

        final List<String> lines = Files.readAllLines(
                projectDir.resolve("build/gemContents/lib/embulk/input/test4.rb"), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertEquals("puts \"test\"", lines.get(0));
    }

    @Test
    public void testRubyVersion(@TempDir Path tempDir) throws IOException {
        final Path projectDir = Files.createDirectory(tempDir.resolve("embulk-input-test5"));
        Files.copy(TestEmbulkPluginsPlugin.class.getClassLoader().getResourceAsStream("build5.gradle"),
                   projectDir.resolve("build.gradle"));

        this.build(projectDir, "gem");
        assertTrue(Files.exists(projectDir.resolve("build/libs/embulk-input-test5-0.1.41-SNAPSHOT.jar")));
        assertTrue(Files.exists(projectDir.resolve("build/gems/embulk-input-test5-0.1.41.snapshot-java.gem")));
    }

    @Test
    public void testSubprojects(@TempDir Path tempDir) throws IOException {
        final Path projectDir = Files.createDirectory(tempDir.resolve("embulk-input-subprojects"));
        final Path sublibDir = Files.createDirectory(projectDir.resolve("sublib"));
        final Path subpluginDir = Files.createDirectory(projectDir.resolve("embulk-input-subprojects_subplugin"));
        Files.copy(TestEmbulkPluginsPlugin.class.getClassLoader().getResourceAsStream("subprojects/build_root.gradle"),
                   projectDir.resolve("build.gradle"));
        Files.copy(TestEmbulkPluginsPlugin.class.getClassLoader().getResourceAsStream("subprojects/settings.gradle"),
                   projectDir.resolve("settings.gradle"));
        Files.copy(TestEmbulkPluginsPlugin.class.getClassLoader().getResourceAsStream("subprojects/build_sublib.gradle"),
                   sublibDir.resolve("build.gradle"));
        Files.copy(TestEmbulkPluginsPlugin.class.getClassLoader().getResourceAsStream("subprojects/build_subplugin.gradle"),
                   subpluginDir.resolve("build.gradle"));

        this.build(projectDir, ":dependencies", "--configuration", "embulkPluginRuntime", "--write-locks");
        final Path rootLockfilePath = projectDir.resolve("gradle/dependency-locks/embulkPluginRuntime.lockfile");
        for (final String line : Files.readAllLines(rootLockfilePath, StandardCharsets.UTF_8)) {
            System.out.println(line);
        }
        assertTrue(Files.exists(rootLockfilePath));
        assertFileDoesContain(rootLockfilePath, "commons-lang:commons-lang:2.6");
        assertFileDoesContain(rootLockfilePath, "commons-io:commons-io:2.6");
        assertFileDoesNotContain(rootLockfilePath, "org.embulk.input.test_subprojects:sublib:0.6.14");
        assertFileDoesNotContain(rootLockfilePath, "org.apache.commons:commons-math3:3.6.1");

        this.build(projectDir, ":embulk-input-subprojects_subplugin:dependencies", "--configuration", "embulkPluginRuntime", "--write-locks");
        final Path subpluginLockfilePath = subpluginDir.resolve("gradle/dependency-locks/embulkPluginRuntime.lockfile");
        for (final String line : Files.readAllLines(subpluginLockfilePath, StandardCharsets.UTF_8)) {
            System.out.println(line);
        }
        assertTrue(Files.exists(subpluginLockfilePath));
        assertFileDoesContain(subpluginLockfilePath, "commons-lang:commons-lang:2.6");
        assertFileDoesContain(subpluginLockfilePath, "org.apache.commons:commons-math3:3.6.1");
        assertFileDoesNotContain(subpluginLockfilePath, "org.embulk.input.test_subprojects:sublib:0.6.14");
        assertFileDoesNotContain(subpluginLockfilePath, "commons-io:commons-io:2.6");

        this.build(projectDir, "publishEmbulkPluginMavenPublicationToMavenRepository", ":gem", ":embulk-input-subprojects_subplugin:gem");

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
        assertPomSubprojectsRoot(rootPomPath);

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
        assertPomSubprojectsSubplugin(subPomPath);
    }

    private static BuildResult build(final Path projectDir, final String... args) {
        final ArrayList<String> argsList = new ArrayList<>();
        argsList.addAll(Arrays.asList(args));
        argsList.add("--stacktrace");
        final BuildResult result = newGradleRunner(projectDir, argsList).build();
        System.out.println("Running 'gradle " + String.join(" ", argsList) + "' :");
        System.out.println("============================================================");
        System.out.print(result.getOutput());
        System.out.println("============================================================");
        return result;
    }

    private static void assertManifest(final Path jarPath) throws IOException {
        final JarURLConnection connection = openJarUrlConnection(jarPath);
        final Manifest manifest = connection.getManifest();
        final Attributes manifestAttributes = manifest.getMainAttributes();
        assertEquals("org.embulk.input.test1.Test1InputPlugin", manifestAttributes.getValue("Embulk-Plugin-Main-Class"));
        assertEquals("0", manifestAttributes.getValue("Embulk-Plugin-Spi-Version"));
    }

    private static Attributes getManifestAttributes(final Path jarPath) throws IOException {
        final JarURLConnection connection = openJarUrlConnection(jarPath);
        final Manifest manifest = connection.getManifest();
        return manifest.getMainAttributes();
    }

    private static void assertPom(final Path pomPath) throws IOException {
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
        assertEquals(2, dependenciesEach.getLength());

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

        // "org.apache.commons:commons-lang3:3.9" => added by the Gradle plugin as "runtime".
        final Element dependency1 = (Element) dependenciesEach.item(1);
        assertSingleTextContentByTagName("org.apache.commons", dependency1, "groupId");
        assertSingleTextContentByTagName("commons-lang3", dependency1, "artifactId");
        assertSingleTextContentByTagName("3.9", dependency1, "version");
        assertSingleTextContentByTagName("runtime", dependency1, "scope");
    }

    private static void assertPom3(final Path pomPath) throws IOException {
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

    private static void assertPomSubprojectsRoot(final Path pomPath) throws IOException {
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
        assertSingleTextContentByTagName("org.embulk.input.test_subprojects", project, "groupId");
        assertSingleTextContentByTagName("embulk-input-subprojects_root", project, "artifactId");
        assertSingleTextContentByTagName("0.6.14", project, "version");

        final Element dependencies = getSingleElementByTagName(project, "dependencies");
        final NodeList dependenciesEach = dependencies.getElementsByTagName("dependency");
        assertEquals(4, dependenciesEach.getLength());

        final Element dependency0 = (Element) dependenciesEach.item(0);
        assertSingleTextContentByTagName("org.embulk.input.test_subprojects", dependency0, "groupId");
        assertSingleTextContentByTagName("sublib", dependency0, "artifactId");
        assertSingleTextContentByTagName("0.6.14", dependency0, "version");
        assertSingleTextContentByTagName("compile", dependency0, "scope");

        final Element dependency1 = (Element) dependenciesEach.item(1);
        assertSingleTextContentByTagName("commons-io", dependency1, "groupId");
        assertSingleTextContentByTagName("commons-io", dependency1, "artifactId");
        assertSingleTextContentByTagName("2.6", dependency1, "version");
        assertSingleTextContentByTagName("compile", dependency1, "scope");

        // It is almost the same as dependency0. The only difference is "scope". It does not cause an immediate problem.
        // It looks like a problem of Gradle's POM generation with project reference (`compile project(":subproject")`).
        // TODO: Investigate the reason deep inside Gradle, and fix it.
        final Element dependency2 = (Element) dependenciesEach.item(2);
        assertSingleTextContentByTagName("org.embulk.input.test_subprojects", dependency2, "groupId");
        assertSingleTextContentByTagName("sublib", dependency2, "artifactId");
        assertSingleTextContentByTagName("0.6.14", dependency2, "version");
        assertSingleTextContentByTagName("runtime", dependency2, "scope");

        final Element dependency3 = (Element) dependenciesEach.item(3);
        assertSingleTextContentByTagName("commons-lang", dependency3, "groupId");
        assertSingleTextContentByTagName("commons-lang", dependency3, "artifactId");
        assertSingleTextContentByTagName("2.6", dependency3, "version");
        assertSingleTextContentByTagName("runtime", dependency3, "scope");
    }

    private static void assertPomSubprojectsSubplugin(final Path pomPath) throws IOException {
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
        assertSingleTextContentByTagName("org.embulk.input.test_subprojects", project, "groupId");
        assertSingleTextContentByTagName("embulk-input-subprojects_subplugin", project, "artifactId");
        assertSingleTextContentByTagName("0.6.14", project, "version");

        final Element dependencies = getSingleElementByTagName(project, "dependencies");
        final NodeList dependenciesEach = dependencies.getElementsByTagName("dependency");
        assertEquals(4, dependenciesEach.getLength());

        final Element dependency0 = (Element) dependenciesEach.item(0);
        assertSingleTextContentByTagName("org.embulk.input.test_subprojects", dependency0, "groupId");
        assertSingleTextContentByTagName("sublib", dependency0, "artifactId");
        assertSingleTextContentByTagName("0.6.14", dependency0, "version");
        assertSingleTextContentByTagName("compile", dependency0, "scope");

        final Element dependency1 = (Element) dependenciesEach.item(1);
        assertSingleTextContentByTagName("org.apache.commons", dependency1, "groupId");
        assertSingleTextContentByTagName("commons-math3", dependency1, "artifactId");
        assertSingleTextContentByTagName("3.6.1", dependency1, "version");
        assertSingleTextContentByTagName("compile", dependency1, "scope");

        // It is almost the same as dependency0. The only difference is "scope". It does not cause an immediate problem.
        // It looks like a problem of Gradle's POM generation with project reference (`compile project(":subproject")`).
        // TODO: Investigate the reason deep inside Gradle, and fix it.
        final Element dependency2 = (Element) dependenciesEach.item(2);
        assertSingleTextContentByTagName("org.embulk.input.test_subprojects", dependency2, "groupId");
        assertSingleTextContentByTagName("sublib", dependency2, "artifactId");
        assertSingleTextContentByTagName("0.6.14", dependency2, "version");
        assertSingleTextContentByTagName("runtime", dependency2, "scope");

        final Element dependency3 = (Element) dependenciesEach.item(3);
        assertSingleTextContentByTagName("commons-lang", dependency3, "groupId");
        assertSingleTextContentByTagName("commons-lang", dependency3, "artifactId");
        assertSingleTextContentByTagName("2.6", dependency3, "version");
        assertSingleTextContentByTagName("runtime", dependency3, "scope");
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
}
