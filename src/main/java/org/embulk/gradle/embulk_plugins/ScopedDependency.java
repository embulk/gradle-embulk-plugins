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

import java.util.Map;
import java.util.Objects;

final class ScopedDependency {
    private ScopedDependency(
            final VersionlessDependency versionless,
            final String version,
            final MavenScope scope,
            final boolean optional) {
        this.versionless = versionless;
        this.version = version;
        this.scope = scope;
        this.optional = optional;
    }

    static ScopedDependency of(
            final VersionlessDependency versionless,
            final String version,
            final MavenScope scope) {
        return new ScopedDependency(versionless, version, scope, false);
    }

    /**
     * Creates an instance of {@link ScopedDependency} from {@code Map<String, Object>} to be declared in {@code build.gradle}.
     */
    static ScopedDependency ofMap(final Map<String, Object> map) {
        String groupId = null;
        String artifactId = null;
        String classifier = null;
        String version = null;
        String scope = null;
        Boolean optional = null;  // null, true, or false

        for (final Map.Entry<String, Object> entry : map.entrySet()) {
            switch (entry.getKey()) {
                case "groupId":
                    if (entry.getValue() instanceof String) {
                        groupId = (String) entry.getValue();
                    }
                    break;
                case "artifactId":
                    if (entry.getValue() instanceof String) {
                        artifactId = (String) entry.getValue();
                    }
                    break;
                case "classifier":
                    if (entry.getValue() instanceof String) {
                        classifier = (String) entry.getValue();
                    }
                    break;
                case "version":
                    if (entry.getValue() instanceof String) {
                        version = (String) entry.getValue();
                    }
                    break;
                case "scope":
                    if (entry.getValue() instanceof String) {
                        scope = (String) entry.getValue();
                    }
                    break;
                case "optional":
                    if (entry.getValue() instanceof Boolean) {
                        optional = (Boolean) entry.getValue();
                    }
                    break;
                default:
                    throw new UnsupportedOperationException("\"" + entry.getKey() + "\" is not accepted as a Maven dependency.");
            }
        }

        if (groupId == null || artifactId == null || version == null || scope == null) {
            throw new NullPointerException(
                    "\"groupId\", \"artifactId\", \"version\", and \"scope\" are mandatory in a Maven dependency.");
        }

        return new ScopedDependency(
                VersionlessDependency.of(groupId, artifactId, classifier),
                version,
                MavenScope.of(scope),
                optional == null ? false : (boolean) optional);
    }

    VersionlessDependency getVersionlessDependency() {
        return this.versionless;
    }

    String getVersion() {
        return this.version;
    }

    MavenScope getScope() {
        return this.scope;
    }

    boolean isOptional() {
        return this.optional;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.versionless, this.version, this.scope, this.optional);
    }

    @Override
    public boolean equals(final Object otherObject) {
        if (this == otherObject) {
            return true;
        }
        if (!(otherObject instanceof ScopedDependency)) {
            return false;
        }
        final ScopedDependency other = (ScopedDependency) otherObject;
        return Objects.equals(this.versionless, other.versionless)
                && Objects.equals(this.version, other.version)
                && Objects.equals(this.scope, other.scope)
                && (this.optional == other.optional);
    }

    @Override
    public String toString() {
        return this.versionless.toString(this.version, this.scope);
    }

    private final VersionlessDependency versionless;
    private final String version;
    private final MavenScope scope;
    private final boolean optional;
}
