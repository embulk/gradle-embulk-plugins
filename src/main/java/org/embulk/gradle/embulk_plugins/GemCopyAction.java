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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.file.RegularFile;
import org.gradle.api.internal.file.copy.CopyAction;
import org.gradle.api.internal.file.copy.CopyActionProcessingStream;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.WorkResults;

class GemCopyAction implements CopyAction {
    public GemCopyAction(
            final Path sourceGemFilePath,
            final Provider<RegularFile> destinationGemFile,
            final Project project) {
        this.sourceGemFilePath = sourceGemFilePath;
        this.destinationGemFile = destinationGemFile;
        this.project = project;
    }

    @Override
    public WorkResult execute(final CopyActionProcessingStream dummy) {
        final Path destinationGemFilePath = this.destinationGemFile.get().getAsFile().toPath();

        try {
            Files.createDirectories(destinationGemFilePath.getParent());
            Files.deleteIfExists(destinationGemFilePath);
            Files.move(this.sourceGemFilePath, destinationGemFilePath);
        } catch (final IOException ex) {
            throw new GradleException("Failed to locate the generated gem file at: " + destinationGemFilePath.toString(), ex);
        }

        this.project.getLogger().lifecycle(
                "Moved {} to {}.",
                project.getProjectDir().toPath().relativize(this.sourceGemFilePath),
                project.getProjectDir().toPath().relativize(destinationGemFilePath));

        return WorkResults.didWork(true);
    }

    private final Path sourceGemFilePath;
    private final Provider<RegularFile> destinationGemFile;
    private final Project project;
}
