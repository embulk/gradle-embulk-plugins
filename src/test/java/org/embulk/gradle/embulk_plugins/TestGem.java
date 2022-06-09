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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestGem {
    @Test
    public void testPathsWithSlashes(@TempDir Path tempDir) throws IOException {
        Files.copy(TestGem.class.getClassLoader().getResourceAsStream("testGem.txt"),
                   tempDir.resolve("file0"));
        final Path dir1 = Files.createDirectory(tempDir.resolve("sub1"));
        Files.copy(TestGem.class.getClassLoader().getResourceAsStream("testGem.txt"),
                   dir1.resolve("file1"));
        Files.copy(TestGem.class.getClassLoader().getResourceAsStream("testGem.txt"),
                   dir1.resolve("file2"));
        final Path dir2 = Files.createDirectory(dir1.resolve("sub2"));
        Files.copy(TestGem.class.getClassLoader().getResourceAsStream("testGem.txt"),
                   dir2.resolve("file3"));

        final String[] foundPathsWithSlashes = listFiles(tempDir).stream()
                .map(path -> Gem.pathToStringWithSlashesForTesting(path))
                .sorted()
                .toArray(String[]::new);

        assertEquals(4, foundPathsWithSlashes.length);
        assertEquals("file0", foundPathsWithSlashes[0]);
        assertEquals("sub1/file1", foundPathsWithSlashes[1]);
        assertEquals("sub1/file2", foundPathsWithSlashes[2]);
        assertEquals("sub1/sub2/file3", foundPathsWithSlashes[3]);
    }

    private static List<Path> listFiles(final Path root) throws IOException {
        final ArrayList<Path> files = new ArrayList<>();

        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                files.add(root.relativize(file));
                return FileVisitResult.CONTINUE;
            }
        });

        return Collections.unmodifiableList(files);
    }
}
