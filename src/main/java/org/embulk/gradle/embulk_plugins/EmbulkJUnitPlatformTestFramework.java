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
import org.gradle.api.internal.tasks.testing.WorkerTestClassProcessorFactory;
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter;
import org.gradle.api.internal.tasks.testing.junitplatform.JUnitPlatformTestFramework;
import org.gradle.api.internal.tasks.testing.junitplatform.JUnitPlatformSpec;

import org.gradle.api.Action;
import org.gradle.api.JavaVersion;
import org.gradle.api.internal.tasks.testing.TestClassProcessor;
import org.gradle.api.internal.tasks.testing.TestFramework;
import org.gradle.api.internal.tasks.testing.WorkerTestClassProcessorFactory;
import org.gradle.api.internal.tasks.testing.detection.TestFrameworkDetector;
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter;
import org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.actor.ActorFactory;
import org.gradle.internal.id.IdGenerator;
import org.gradle.internal.jvm.UnsupportedJavaRuntimeException;
import org.gradle.internal.scan.UsedByScanPlugin;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.time.Clock;
import org.gradle.process.internal.worker.WorkerProcessBuilder;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.util.List;

public class EmbulkJUnitPlatformTestFramework extends JUnitPlatformTestFramework {
    public EmbulkJUnitPlatformTestFramework(final DefaultTestFilter filter) {
        super(filter);
    }

    @Override
    public WorkerTestClassProcessorFactory getProcessorFactory() {
        return super.getProcessorFactory();
        /*
        if (!JavaVersion.current().isJava8Compatible()) {
            throw new UnsupportedJavaRuntimeException("Running JUnit Platform requires Java 8+, please configure your test java executable with Java 8 or higher.");
        }
        return new JUnitPlatformTestClassProcessorFactory(new JUnitPlatformSpec(options,
            filter.getIncludePatterns(), filter.getExcludePatterns(),
            filter.getCommandLineIncludePatterns()));
        */
    }

    static class JUnitPlatformTestClassProcessorFactory implements WorkerTestClassProcessorFactory, Serializable {
        private final JUnitPlatformSpec spec;

        JUnitPlatformTestClassProcessorFactory(JUnitPlatformSpec spec) {
            this.spec = spec;
        }

        @Override
        public TestClassProcessor create(ServiceRegistry serviceRegistry) {
            try {
                IdGenerator<?> idGenerator = serviceRegistry.get(IdGenerator.class);
                Clock clock = serviceRegistry.get(Clock.class);
                ActorFactory actorFactory = serviceRegistry.get(ActorFactory.class);
                Class<?> clazz = getClass().getClassLoader().loadClass("org.gradle.api.internal.tasks.testing.junitplatform.JUnitPlatformTestClassProcessor");
                Constructor<?> constructor = clazz.getConstructor(JUnitPlatformSpec.class, IdGenerator.class, ActorFactory.class, Clock.class);
                return (TestClassProcessor) constructor.newInstance(spec, idGenerator, actorFactory, clock);
            } catch (Exception e) {
                throw UncheckedException.throwAsUncheckedException(e);
            }
        }
    }
}
