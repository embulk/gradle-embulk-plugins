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
import static org.junit.jupiter.api.Assertions.fail;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Utility methods for testing the Embulk plugins Gradle plugin.
 */
class Util {
    private Util() {
        // No instantiation.
    }

    static Path prepareProjectDir(final Path tempDir, final String testProjectName) {
        final String resourceName = testProjectName + System.getProperty("file.separator") + "build.gradle";
        final Path resourceDir;
        try {
            final URL resourceUrl = Util.class.getClassLoader().getResource(resourceName);
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

    static BuildResult runGradle(final Path projectDir, final String... args) {
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

    static void assertFileDoesContain(final Path path, final String expected) throws IOException {
        try (final Stream<String> lines = Files.newBufferedReader(path).lines()) {
            final boolean found = lines.filter(actualLine -> {
                return actualLine.contains(expected);
            }).findAny().isPresent();
            if (!found) {
                fail("\"" + path.toString() + "\" does not contain \"" + expected + "\".");
            }
        }
    }

    static void assertFileDoesNotContain(final Path path, final String notExpected) throws IOException {
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

    static JarURLConnection openJarUrlConnection(final Path jarPath) throws IOException {
        final URL jarUrl = new URL("jar:" + jarPath.toUri().toURL().toString() + "!/");
        return (JarURLConnection) jarUrl.openConnection();
    }

    static Element getSingleElementByTagName(final Element element, final String name) {
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

    static void assertNoElementByTagName(final Element element, final String name) {
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

    static void assertSingleTextContentByTagName(final String expected, final Element element, final String name) {
        final Element childElement = getSingleElementByTagName(element, name);
        assertEquals(expected, childElement.getTextContent());
    }

    static void assertNoElement(final Element element, final String name) {
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
