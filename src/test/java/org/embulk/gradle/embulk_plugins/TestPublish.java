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
import static org.embulk.gradle.embulk_plugins.Util.assertNoElement;
import static org.embulk.gradle.embulk_plugins.Util.assertSingleTextContentByTagName;
import static org.embulk.gradle.embulk_plugins.Util.getSingleElementByTagName;
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
import java.util.jar.Attributes;
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
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Tests "publish" and pom.xml generation in general in the Embulk plugins Gradle plugin.
 *
 * <p>This test is tentatively disabled on Windows. {@code GradleRunner} may keep some related files open.
 * It prevents JUnit 5 from removing the temporary directory ({@code TempDir}).
 *
 * @see <a href="https://github.com/embulk/gradle-embulk-plugins/runs/719452273">A failed test</a>
 */
class TestPublish {
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void test(@TempDir Path tempDir) throws IOException {
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
        assertEquals(8, dependenciesEach.getLength());

        // "org.embulk:embulk-spi:0.10.35" => originally in build.gradle as "compileOnly".
        final Element dependency0 = (Element) dependenciesEach.item(0);
        assertSingleTextContentByTagName("org.embulk", dependency0, "groupId");
        assertSingleTextContentByTagName("embulk-spi", dependency0, "artifactId");
        assertSingleTextContentByTagName("0.10.35", dependency0, "version");
        assertNoElement(dependency0, "classifier");
        assertSingleTextContentByTagName("provided", dependency0, "scope");
        assertExcludeAll(dependency0);

        // "org.embulk:embulk-api:0.10.35" => originally in build.gradle as "compileOnly".
        final Element dependency1 = (Element) dependenciesEach.item(1);
        assertSingleTextContentByTagName("org.embulk", dependency1, "groupId");
        assertSingleTextContentByTagName("embulk-api", dependency1, "artifactId");
        assertSingleTextContentByTagName("0.10.35", dependency1, "version");
        assertNoElement(dependency1, "classifier");
        assertSingleTextContentByTagName("provided", dependency1, "scope");
        assertExcludeAll(dependency1);

        // "org.msgpack:msgpac-core:0.8.11" => added by the Gradle plugin as "provided".
        final Element dependency2 = (Element) dependenciesEach.item(2);
        assertSingleTextContentByTagName("org.msgpack", dependency2, "groupId");
        assertSingleTextContentByTagName("msgpack-core", dependency2, "artifactId");
        assertSingleTextContentByTagName("0.8.11", dependency2, "version");
        assertNoElement(dependency2, "classifier");
        assertSingleTextContentByTagName("provided", dependency2, "scope");
        assertExcludeAll(dependency2);

        // "org.slf4j:slf4j-api:1.7.30" => added by the Gradle plugin as "provided".
        final Element dependency3 = (Element) dependenciesEach.item(3);
        assertSingleTextContentByTagName("org.slf4j", dependency3, "groupId");
        assertSingleTextContentByTagName("slf4j-api", dependency3, "artifactId");
        assertSingleTextContentByTagName("1.7.30", dependency3, "version");
        assertNoElement(dependency3, "classifier");
        assertSingleTextContentByTagName("provided", dependency3, "scope");
        assertExcludeAll(dependency3);

        // "org.apache.commons:commons-text:1.7" => originally in build.gradle as "compile".
        // It depends on "org.apache.commons:commons-lang3:3.9".
        //
        // https://search.maven.org/artifact/org.apache.commons/commons-text/1.7/jar
        // https://repo1.maven.org/maven2/org/apache/commons/commons-text/1.7/
        final Element dependency4 = (Element) dependenciesEach.item(4);
        assertSingleTextContentByTagName("org.apache.commons", dependency4, "groupId");
        assertSingleTextContentByTagName("commons-text", dependency4, "artifactId");
        assertSingleTextContentByTagName("1.7", dependency4, "version");
        assertNoElement(dependency4, "classifier");
        assertSingleTextContentByTagName("compile", dependency4, "scope");
        assertExcludeAll(dependency4);

        // "com.github.jnr:jffi:1.2.23" => originally in build.gradle as "compile".
        final Element dependency5 = (Element) dependenciesEach.item(5);
        assertSingleTextContentByTagName("com.github.jnr", dependency5, "groupId");
        assertSingleTextContentByTagName("jffi", dependency5, "artifactId");
        assertSingleTextContentByTagName("1.2.23", dependency5, "version");
        assertNoElement(dependency5, "classifier");
        assertSingleTextContentByTagName("compile", dependency5, "scope");
        assertExcludeAll(dependency5);

        // "com.github.jnr:jffi:1.2.23:native" => originally in build.gradle "compile".
        final Element dependency6 = (Element) dependenciesEach.item(6);
        assertSingleTextContentByTagName("com.github.jnr", dependency6, "groupId");
        assertSingleTextContentByTagName("jffi", dependency6, "artifactId");
        assertSingleTextContentByTagName("1.2.23", dependency6, "version");
        assertSingleTextContentByTagName("native", dependency6, "classifier");
        assertSingleTextContentByTagName("compile", dependency6, "scope");
        assertExcludeAll(dependency6);

        // "org.apache.commons:commons-lang3:3.9" => added by the Gradle plugin as "compile".
        final Element dependency7 = (Element) dependenciesEach.item(7);
        assertSingleTextContentByTagName("org.apache.commons", dependency7, "groupId");
        assertSingleTextContentByTagName("commons-lang3", dependency7, "artifactId");
        assertSingleTextContentByTagName("3.9", dependency7, "version");
        assertNoElement(dependency7, "classifier");
        assertSingleTextContentByTagName("compile", dependency7, "scope");
        assertExcludeAll(dependency7);
    }
}
