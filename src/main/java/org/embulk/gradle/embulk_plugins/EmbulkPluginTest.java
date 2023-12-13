/*
 * Copyright 2023 The Embulk project
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.internal.tasks.testing.TestFramework;
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter;
import org.gradle.api.internal.tasks.testing.junitplatform.JUnitPlatformTestFramework;

public class EmbulkPluginTest extends Test {
    public void useEmbulkJUnitPlatform() {
        final Class<? extends Test> klass = Test.class;
        final Method method;
        try {
            method = klass.getDeclaredMethod("useTestFramework", TestFramework.class);
        } catch (final NoSuchMethodException ex) {
            throw new GradleException("Unexpected failure", ex);
        }

        final boolean isAccessible = method.isAccessible();
        try {
            method.setAccessible(true);
            System.out.println("!");
            method.invoke(this, new EmbulkJUnitPlatformTestFramework((DefaultTestFilter) this.getFilter()));
            // method.invoke(this, new JUnitPlatformTestFramework((DefaultTestFilter) this.getFilter()));
            System.out.println("?");
        } catch (final IllegalAccessException ex) {
            throw new GradleException("Unexpected failure", ex);
        } catch (final InvocationTargetException ex) {
            throw new GradleException("Unexpected failure", ex);
        } finally {
            method.setAccessible(isAccessible);
        }
    }
}
