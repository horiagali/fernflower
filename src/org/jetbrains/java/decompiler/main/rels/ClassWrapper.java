/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.java.decompiler.main.rels;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.collectors.CounterContainer;
import org.jetbrains.java.decompiler.main.collectors.VarNamesCollector;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructField;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.attr.StructGeneralAttribute;
import org.jetbrains.java.decompiler.struct.attr.StructLocalVariableTableAttribute;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.util.InterpreterUtil;
import org.jetbrains.java.decompiler.util.VBStyleCollection;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class ClassWrapper {

  private StructClass classStruct;
  private Set<String> hiddenMembers = new HashSet<String>();
  private VBStyleCollection<Exprent, String> staticFieldInitializers = new VBStyleCollection<Exprent, String>();
  private VBStyleCollection<Exprent, String> dynamicFieldInitializers = new VBStyleCollection<Exprent, String>();
  private VBStyleCollection<MethodWrapper, String> methods = new VBStyleCollection<MethodWrapper, String>();

  public ClassWrapper(StructClass classStruct) {
    this.classStruct = classStruct;
  }

  public void init() throws IOException {
    DecompilerContext.setProperty(DecompilerContext.CURRENT_CLASS, classStruct);
    DecompilerContext.getLogger().startClass(classStruct.qualifiedName);

    // collect field names
    Set<String> setFieldNames = new HashSet<String>();
    for (StructField fd : classStruct.getFields()) {
      setFieldNames.add(fd.getName());
    }

    int maxSec = Integer.parseInt(DecompilerContext.getProperty(IFernflowerPreferences.MAX_PROCESSING_METHOD).toString());

    for (StructMethod mt : classStruct.getMethods()) {
      DecompilerContext.getLogger().startMethod(mt.getName() + " " + mt.getDescriptor());

      VarNamesCollector vc = new VarNamesCollector();
      DecompilerContext.setVarNamesCollector(vc);

      CounterContainer counter = new CounterContainer();
      DecompilerContext.setCounterContainer(counter);

      DecompilerContext.setProperty(DecompilerContext.CURRENT_METHOD, mt);
      DecompilerContext.setProperty(DecompilerContext.CURRENT_METHOD_DESCRIPTOR, MethodDescriptor.parseDescriptor(mt.getDescriptor()));

      VarProcessor varProc = new VarProcessor();
      DecompilerContext.setProperty(DecompilerContext.CURRENT_VAR_PROCESSOR, varProc);

      RootStatement root = null;

      boolean isError = false;

      try {
        if (mt.containsCode()) {
          if (maxSec == 0) {
            root = MethodProcessorRunnable.codeToJava(mt, varProc);
          }
          else {
            MethodProcessorRunnable mtProc = new MethodProcessorRunnable(mt, varProc, DecompilerContext.getCurrentContext());

            Thread mtThread = new Thread(mtProc);
            long stopAt = System.currentTimeMillis() + maxSec * 1000;

            mtThread.start();

            while (mtThread.isAlive()) {
              synchronized (mtProc.lock) {
                mtProc.lock.wait(100);
              }

              if (System.currentTimeMillis() >= stopAt) {
                String message = "Processing time limit exceeded for method " + mt.getName() + ", execution interrupted.";
                DecompilerContext.getLogger().writeMessage(message, IFernflowerLogger.Severity.ERROR);
                killThread(mtThread);
                isError = true;
                break;
              }
            }

            if (!isError) {
              root = mtProc.getResult();
            }
          }
        }
        else {
          boolean thisVar = !mt.hasModifier(CodeConstants.ACC_STATIC);
          MethodDescriptor md = MethodDescriptor.parseDescriptor(mt.getDescriptor());

          int paramCount = 0;
          if (thisVar) {
            varProc.getThisVars().put(new VarVersionPair(0, 0), classStruct.qualifiedName);
            paramCount = 1;
          }
          paramCount += md.params.length;

          int varIndex = 0;
          for (int i = 0; i < paramCount; i++) {
            varProc.setVarName(new VarVersionPair(varIndex, 0), vc.getFreeName(varIndex));

            if (thisVar) {
              if (i == 0) {
                varIndex++;
              }
              else {
                varIndex += md.params[i - 1].stackSize;
              }
            }
            else {
              varIndex += md.params[i].stackSize;
            }
          }
        }
      }
      catch (Throwable ex) {
        DecompilerContext.getLogger().writeMessage("Method " + mt.getName() + " " + mt.getDescriptor() + " couldn't be decompiled.", ex);
        isError = true;
      }

      MethodWrapper methodWrapper = new MethodWrapper(root, varProc, mt, counter);
      methodWrapper.decompiledWithErrors = isError;

      methods.addWithKey(methodWrapper, InterpreterUtil.makeUniqueKey(mt.getName(), mt.getDescriptor()));

      // rename vars so that no one has the same name as a field
      varProc.refreshVarNames(new VarNamesCollector(setFieldNames));

      // if debug information present and should be used
      if (DecompilerContext.getOption(IFernflowerPreferences.USE_DEBUG_VAR_NAMES)) {
        StructLocalVariableTableAttribute attr = (StructLocalVariableTableAttribute)mt.getAttributes().getWithKey(
          StructGeneralAttribute.ATTRIBUTE_LOCAL_VARIABLE_TABLE);

        if (attr != null) {
          varProc.setDebugVarNames(attr.getMapVarNames());
        }
      }

      DecompilerContext.getLogger().endMethod();
    }

    DecompilerContext.getLogger().endClass();
  }

  @SuppressWarnings("deprecation")
  private static void killThread(Thread thread) {
    thread.stop();
  }

  public MethodWrapper getMethodWrapper(String name, String descriptor) {
    return methods.getWithKey(InterpreterUtil.makeUniqueKey(name, descriptor));
  }

  public StructClass getClassStruct() {
    return classStruct;
  }

  public VBStyleCollection<MethodWrapper, String> getMethods() {
    return methods;
  }

  public Set<String> getHiddenMembers() {
    return hiddenMembers;
  }

  public VBStyleCollection<Exprent, String> getStaticFieldInitializers() {
    return staticFieldInitializers;
  }

  public VBStyleCollection<Exprent, String> getDynamicFieldInitializers() {
    return dynamicFieldInitializers;
  }
}
