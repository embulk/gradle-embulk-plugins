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

import java.util.Map;
import org.gradle.api.artifacts.ResolvedDependency;

class Util {
    private Util() {
        // No instantiation.
    }

    /**
     * Traverses the dependency tree recursively to list all the dependencies.
     */
    static void recurseAllDependencies(
            final ResolvedDependency dependency,
            final Map<String, ResolvedDependency> allDependencies) {
        final String key = dependency.getModuleGroup() + ":" + dependency.getModuleName();
        if (allDependencies.containsKey(key)) {
            return;
        }
        allDependencies.put(key, dependency);
        if (dependency.getChildren().size() > 0) {
            for (final ResolvedDependency child : dependency.getChildren()) {
                recurseAllDependencies(child, allDependencies);
            }
        }
    }
}
