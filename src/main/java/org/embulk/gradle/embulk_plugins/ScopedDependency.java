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

import java.util.Objects;

final class ScopedDependency {
    private ScopedDependency(
            final VersionlessDependency versionless,
            final String version,
            final MavenScope scope) {
        this.versionless = versionless;
        this.version = version;
        this.scope = scope;
    }

    static ScopedDependency of(
            final VersionlessDependency versionless,
            final String version,
            final MavenScope scope) {
        return new ScopedDependency(versionless, version, scope);
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

    @Override
    public int hashCode() {
        return Objects.hash(this.versionless, this.version, this.scope);
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
                && Objects.equals(this.scope, other.scope);
    }

    @Override
    public String toString() {
        return this.versionless.toString(this.version, this.scope);
    }

    private final VersionlessDependency versionless;
    private final String version;
    private final MavenScope scope;
}
