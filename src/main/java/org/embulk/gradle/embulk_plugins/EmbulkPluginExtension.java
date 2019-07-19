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

import org.gradle.api.Project;

/**
 * Configuration options for the {@link org.embulk.gradle.embulk_plugins.EmbulkPluginsPlugin}.
 *
 * <pre>{@code apply plugin: "org.embulk.embulk-plugins"
 *
 * embulkPlugin {
 *     mainClass = "org.embulk.input.example.ExampleInputPlugin"
 *     flatRuntimeConfiguration = "embulkPluginFlatRuntime"
 * }}</pre>
 */
public class EmbulkPluginExtension {
    public EmbulkPluginExtension(final Project project) {
        this.mainClass = null;
        this.flatRuntimeConfiguration = "embulkPluginFlatRuntime";
    }

    public String getMainClass() {
        return this.mainClass;
    }

    public void setMainClass(final String mainClass) {
        if (mainClass == null) {
            throw new NullPointerException("mainClass must not be null.");
        }
        this.mainClass = mainClass;
    }

    public String getFlatRuntimeConfiguration() {
        return this.flatRuntimeConfiguration;
    }

    public void setFlatRuntimeConfiguration(final String flatRuntimeConfiguration) {
        if (flatRuntimeConfiguration == null) {
            throw new NullPointerException("flatRuntimeConfiguration must not be null.");
        }
        this.flatRuntimeConfiguration = flatRuntimeConfiguration;
    }

    private String mainClass;
    private String flatRuntimeConfiguration;
}
