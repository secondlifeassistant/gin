/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.gwt.inject.rebind;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.google.gwt.core.ext.GeneratorContext;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.TreeLogger.Type;
import com.google.gwt.dev.javac.CompiledClass;
import com.google.gwt.dev.javac.StandardGeneratorContext;

/**
 * Gin-internal class loader that allows us to load classes generated by other generators and
 * super-source.
 *
 * <p>In most cases, Gin needs access to the GWT version of a class (whether it is unmodified,
 * created by a generator or a super-source version) so this is the default version provided by this
 * class loader. The exception are JRE classes (which cannot be loaded by a custom class loader),
 * classes that are also used in the Gin Generator "rebind" code (otherwise class literal
 * comparisons yield strange results) and classes that are defined as super-source by Gin but must
 * be their "normal" self during the generator run. These exceptions are loaded with the system
 * class loader.
 *
 * <p>If the class is not available to GWT, we attempt to load it through the system class loader.
 *
 * <p>Unfortunately, GWT does not like to expose internal details like the compilation state and its
 * bytes. For now, we use reflection to access this internal state but in the long term we should
 * switch to other strategies such as running javac on source (which we'd need to reverse-engineer
 * from parsing the GWT AST).
 */
class GinBridgeClassLoader extends ClassLoader {

  private final TreeLogger logger;
  private final GeneratorContext context;

  /**
   * Packages that should not be loaded from GWT.
   */
  private final Collection<String> exceptedPackages;

  // Lazily load class files from compilation state.
  private boolean loadedClassFiles = false;
  private Map<String, CompiledClass> classFileMap;

  GinBridgeClassLoader(GeneratorContext context, TreeLogger logger,
      Collection<String> exceptedPackages) {
    super(GinBridgeClassLoader.class.getClassLoader()); // Use own class loader.
    this.context = context;
    this.logger = logger;
    this.exceptedPackages = getExceptedPackages(exceptedPackages);
  }

  private static Collection<String> getExceptedPackages(Collection<String> superSourceExceptions) {
    Set<String> names = new LinkedHashSet<String>();
    for (String name : superSourceExceptions) {
      if (name.endsWith(".")) {
        names.add(name);
      } else {
        names.add(name + ".");
      }
    }

    // Make sure we're not loading JRE classes through a non-system class loader (which is not
    // allowed).
    names.add("java.");

    // Annotation loading will require sun.reflect APIs, even when they're referenced from 
    // and present in client code.
    names.add("sun.reflect.");
    return names;
  }

  /**
   * @inheritDoc
   *
   * Gin class loading implementation, making sure that classes are loaded consistently and can be
   * GWT generated or super-source classes. See description {@link GinBridgeClassLoader above}.
   */
  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    Class<?> clazz = findLoadedClass(name);
    if (clazz == null) {
      if (inExceptedPackage(name)) {
        clazz = super.loadClass(name, false);
      } else {
        try {
          clazz = findClass(name);          
        } catch (ClassNotFoundException e) {
          clazz = super.loadClass(name, false);
          if (!clazz.isAnnotation()) { // Annotations are always safe to load
            logger.log(Type.WARN, String.format(
                "Class %s is used in Gin, but not available in GWT client code.", name));
          }
        }
      }
    }

    if (resolve) {
      resolveClass(clazz);
    }

    return clazz;
  }

  private boolean inExceptedPackage(String name) {
    for (String pkg : exceptedPackages) {
      if (name.startsWith(pkg)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Looks up classes in GWT's compilation state.
   */
  @Override
  protected Class<?> findClass(String name) throws ClassNotFoundException {
    if (!loadedClassFiles) {
      classFileMap = extractClassFileMap();
      loadedClassFiles = true;
    }

    if (classFileMap == null) {
      throw new ClassNotFoundException(name);
    }

    String internalName = name.replace('.', '/');
    CompiledClass compiledClass = classFileMap.get(internalName);
    if (compiledClass == null) {
      throw new ClassNotFoundException(name);
    }

    // Make sure the class's package is present.
    String pkg = compiledClass.getPackageName();
    if (getPackage(pkg) == null) {
      definePackage(pkg, null, null, null, null, null, null, null);
    }

    byte[] bytes = compiledClass.getBytes();
    return defineClass(name, bytes, 0, bytes.length);
  }

  /**
   * Retrieves class definitions from a {@link GeneratorContext} by downcasting.
   */
  private Map<String, CompiledClass> extractClassFileMap() {
    if (context instanceof StandardGeneratorContext) {
      StandardGeneratorContext standardContext = (StandardGeneratorContext) context;
      return standardContext.getCompilationState().getClassFileMap();
    } else {
      logger.log(TreeLogger.Type.WARN,
          String.format("Could not load generated classes from GWT context, "
              + "encountered unexpected generator type %s.", context.getClass()));
      return null;
    }
  }
}
