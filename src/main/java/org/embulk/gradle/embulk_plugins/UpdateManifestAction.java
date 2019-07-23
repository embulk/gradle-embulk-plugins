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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.gradle.api.Action;
import org.gradle.api.java.archives.Manifest;

class UpdateManifestAction implements Action<Manifest> {
    private UpdateManifestAction(final Map<String, String> attributes) {
        this.attributes = attributes;
    }

    static class Builder {
        private Builder() {
            this.attributes = new HashMap<>();
        }

        Builder add(final String key, final String value) {
            this.attributes.put(key, value);
            return this;
        }

        UpdateManifestAction build() {
            return new UpdateManifestAction(Collections.unmodifiableMap(this.attributes));
        }

        private final HashMap<String, String> attributes;
    }

    static Builder builder() {
        return new Builder();
    }

    @Override
    public void execute(final Manifest manifest) {
        manifest.attributes(this.attributes);
    }

    private final Map<String, String> attributes;
}
