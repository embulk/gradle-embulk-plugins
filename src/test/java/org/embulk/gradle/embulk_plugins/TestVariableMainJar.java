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

import static org.embulk.gradle.embulk_plugins.Util.assertNoElementByTagName;
import static org.embulk.gradle.embulk_plugins.Util.assertSingleTextContentByTagName;
import static org.embulk.gradle.embulk_plugins.Util.openJarUrlConnection;
import static org.embulk.gradle.embulk_plugins.Util.prepareProjectDir;
import static org.embulk.gradle.embulk_plugins.Util.runGradle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * Tests the "mainJar" setting in the Embulk plugins Gradle plugin.
 *
 * <p>This test is tentatively disabled on Windows. {@code GradleRunner} may keep some related files open.
 * It prevents JUnit 5 from removing the temporary directory ({@code TempDir}).
 *
 * @see <a href="https://github.com/embulk/gradle-embulk-plugins/runs/719452273">A failed test</a>
 */
class TestVariableMainJar {
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void test(@TempDir Path tempDir) throws IOException {
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
}
