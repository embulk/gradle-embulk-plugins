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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.Attributes;
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
    public void testUpload(@TempDir Path tempDir) throws IOException {
        final Path projectDir = Files.createDirectory(tempDir.resolve("embulk-input-test1"));
        Files.copy(TestEmbulkPluginsPlugin.class.getClassLoader().getResourceAsStream("build.gradle"),
                   projectDir.resolve("build.gradle"));

        this.build(projectDir, "jar");
        assertTrue(Files.exists(projectDir.resolve("build/libs/embulk-input-test1-0.2.5.jar")));

        this.build(projectDir, "uploadArchives");
        final Path versionDir = projectDir.resolve("build/mavenLocal/org/embulk/input/test1/embulk-input-test1/0.2.5");
        final Path jarPath = versionDir.resolve("embulk-input-test1-0.2.5.jar");
        final Path pomPath = versionDir.resolve("embulk-input-test1-0.2.5.pom");
        assertTrue(Files.exists(jarPath));
        assertManifest(jarPath);
        assertTrue(Files.exists(pomPath));
        assertPom(pomPath);
    }

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

    private static void assertSingleTextContentByTagName(final String expected, final Element element, final String name) {
        final Element childElement = getSingleElementByTagName(element, name);
        assertEquals(expected, childElement.getTextContent());
    }
}
