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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * Configuration options for the {@link org.embulk.gradle.embulk_plugins.EmbulkPluginsPlugin}.
 *
 * <pre>{@code apply plugin: "org.embulk.embulk-plugins"
 *
 * embulkPlugin {
 *     mainClass = "org.embulk.input.example.ExampleInputPlugin"
 *     category = "input"
 *     type = "example"
 *     // mainJar = "shadowJar"
 *     ignoreConflicts = [
 *         [ group: "...", module: "..." ]
 *     ]
 * }}</pre>
 */
public class EmbulkPluginExtension {
    public EmbulkPluginExtension(final Project project) {
        final ObjectFactory objectFactory = project.getObjects();

        this.project = project;
        this.mainClass = objectFactory.property(String.class);
        this.category = objectFactory.property(String.class);
        this.type = objectFactory.property(String.class);
        this.mainJar = objectFactory.property(String.class);
        this.ignoreConflicts = castedListProperty(objectFactory);
    }

    public Property<String> getMainClass() {
        return this.mainClass;
    }

    public Property<String> getCategory() {
        return this.category;
    }

    public Property<String> getType() {
        return this.type;
    }

    public Property<String> getMainJar() {
        return this.mainJar;
    }

    public ListProperty<Map<String, String>> getIgnoreConflicts() {
        return this.ignoreConflicts;
    }

    void checkValidity() {
        final ArrayList<String> errors = new ArrayList<>();
        if ((!this.mainClass.isPresent()) || this.mainClass.get().isEmpty()) {
            errors.add("\"mainClass\"");
        }
        if ((!this.category.isPresent()) || this.category.get().isEmpty()) {
            errors.add("\"category\"");
        }
        if ((!this.type.isPresent()) || this.type.get().isEmpty()) {
            errors.add("\"type\"");
        }

        if (!errors.isEmpty()) {
            throw new GradleException(
                    "Failed to configure \"embulkPlugin\" because of insufficient settings: [ "
                    + String.join(", ", errors) + " ]");
        }

        if (!CATEGORIES.contains(this.category.get())) {
            throw new GradleException(
                    "Failed to configure \"embulkPlugin\" because \"category\" must be one of: [ "
                    + String.join(", ", CATEGORIES_ARRAY) + " ]");
        }

        if (this.ignoreConflicts.isPresent() && !this.ignoreConflicts.get().isEmpty()) {
            for (final Map<String, String> module : this.ignoreConflicts.get()) {
                try {
                    for (final Map.Entry<String, String> moduleAttribute : module.entrySet()) {
                        // Calling getKey() and getValue() to trigger type checks.
                        final String key = moduleAttribute.getKey();
                        final String value = moduleAttribute.getValue();
                    }
                } catch (final ClassCastException ex) {
                    throw new GradleException(
                            "Failed to configure \"embulkPlugin\" because \"ignoreConflicts\" does not consist only of "
                            + "String:String Maps.", ex);
                }

                final Set<String> moduleKeys = module.keySet();
                if (moduleKeys.size() != 2 || (!moduleKeys.contains("group")) || (!moduleKeys.contains("module"))) {
                    throw new GradleException(
                            "Failed to configure \"embulkPlugin\" because \"ignoreConflicts\"'s Map does not consist only of "
                            + "\"group\" and \"module\".");
                }
            }

        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T castedListProperty(final ObjectFactory objectFactory) {
        final T casted = (T) objectFactory.listProperty(Map.class);
        return casted;
    }

    private static final String[] CATEGORIES_ARRAY = {
        "input",
        "output",
        "parser",
        "formatter",
        "decoder",
        "encoder",
        "filter",
        "guess",
        "executor"
    };

    private static final Set<String> CATEGORIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(CATEGORIES_ARRAY)));

    private final Project project;
    private final Property<String> mainClass;
    private final Property<String> category;
    private final Property<String> type;
    private final Property<String> mainJar;
    private final ListProperty<Map<String, String>> ignoreConflicts;
}
