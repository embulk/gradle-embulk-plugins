/*
 * Copyright 2022 The Embulk project
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

import groovy.util.Node;
import java.util.Objects;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;

final class VersionlessDependency {
    private VersionlessDependency(
            final String group,
            final String artifactName,
            final String classifier) {
        final StringBuilder builder = new StringBuilder();

        if (group == null) {
            this.group = "";
        } else {
            this.group = group;
            builder.append(group);
        }

        builder.append(":");

        if (artifactName == null) {
            throw new NullPointerException("artifactName must not be null.");
        }
        this.artifactName = artifactName;
        builder.append(artifactName);

        if (classifier == null) {
            this.classifier = null;
        } else {
            this.classifier = classifier;
            builder.append(":");
            builder.append(classifier);
        }

        this.stringified = builder.toString();
    }

    static VersionlessDependency of(
            final String group,
            final String artifactName,
            final String classifier) {
        return new VersionlessDependency(group, artifactName, classifier);
    }

    static VersionlessDependency fromModule(final ModuleComponentIdentifier identifier, final ResolvedArtifact artifact) {
        return new VersionlessDependency(identifier.getGroup(), identifier.getModule(), artifact.getClassifier());
    }

    String getGroup() {
        return this.group;
    }

    String getArtifactName() {
        return this.artifactName;
    }

    String getClassifier() {
        return this.classifier;
    }

    Node toNode() {
        final Node node = new Node(null, "dependency");
        node.appendNode("groupId", this.group);
        node.appendNode("artifactId", this.artifactName);
        if (this.classifier != null) {
            node.appendNode("classifier", this.classifier);
        }
        return node;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.group, this.artifactName, this.classifier);
    }

    @Override
    public boolean equals(final Object otherObject) {
        if (this == otherObject) {
            return true;
        }
        if (!(otherObject instanceof VersionlessDependency)) {
            return false;
        }
        final VersionlessDependency other = (VersionlessDependency) otherObject;
        return Objects.equals(this.group, other.group)
                && Objects.equals(this.artifactName, other.artifactName)
                && Objects.equals(this.classifier, other.classifier);
    }

    @Override
    public String toString() {
        return this.stringified;
    }

    public String toString(final String version, final MavenScope scope) {
        final StringBuilder builder = new StringBuilder();
        if (this.group != null) {
            builder.append(this.group);
        }
        builder.append(":");
        builder.append(this.artifactName);
        builder.append(":");
        builder.append(version);
        if (this.classifier != null) {
            builder.append(":");
            builder.append(this.classifier);
        }
        builder.append("@");
        builder.append(scope.toString());
        return builder.toString();
    }

    private final String group;
    private final String artifactName;
    private final String classifier;

    private final String stringified;
}
