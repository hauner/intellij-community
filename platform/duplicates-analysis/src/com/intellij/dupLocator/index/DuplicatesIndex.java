/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package com.intellij.dupLocator.index;

import com.intellij.dupLocator.DuplicatesProfile;
import com.intellij.dupLocator.DuplocateVisitor;
import com.intellij.dupLocator.DuplocatorState;
import com.intellij.dupLocator.treeHash.FragmentsCollector;
import com.intellij.dupLocator.util.PsiFragment;
import com.intellij.lang.Language;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.SystemProperties;
import com.intellij.util.indexing.*;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.DataInputOutputUtil;
import com.intellij.util.io.EnumeratorIntegerDescriptor;
import com.intellij.util.io.KeyDescriptor;
import gnu.trove.THashMap;
import gnu.trove.TIntArrayList;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * @by Maxim.Mossienko on 12/11/13.
 */
public class DuplicatesIndex extends FileBasedIndexExtension<Integer, TIntArrayList> implements PsiDependentIndex {
  static boolean ourEnabled = SystemProperties.getBooleanProperty("idea.enable.duplicates.online.calculation",
                                                                  isEnabledByDefault());

  private static boolean isEnabledByDefault() {
    Application application = ApplicationManager.getApplication();
    return application.isInternal() && !application.isUnitTestMode();
  }

  @NonNls public static final ID<Integer, TIntArrayList> NAME = ID.create("DuplicatesIndex");
  private static final int myBaseVersion = 10;

  private final FileBasedIndex.InputFilter myInputFilter = new FileBasedIndex.InputFilter() {
    @Override
    public boolean acceptInput(@NotNull final VirtualFile file) {
      return ourEnabled && findDuplicatesProfile(file.getFileType()) != null;
    }
  };

  private final DataExternalizer<TIntArrayList> myValueExternalizer = new DataExternalizer<TIntArrayList>() {
    @Override
    public void save(@NotNull DataOutput out, TIntArrayList list) throws IOException {
      if (list.size() == 1) DataInputOutputUtil.writeINT(out, list.getQuick(0));
      else {
        DataInputOutputUtil.writeINT(out, -list.size());
        int prev = 0;
        for (int i = 0, len = list.size(); i < len; ++i) {
          int value = list.getQuick(i);
          DataInputOutputUtil.writeINT(out, value - prev);
          prev = value;
        }
      }
    }

    @Override
    public TIntArrayList read(@NotNull DataInput in) throws IOException {
      int capacityOrValue = DataInputOutputUtil.readINT(in);
      if (capacityOrValue >= 0) {
        TIntArrayList list = new TIntArrayList(1);
        list.add(capacityOrValue);
        return list;
      }
      capacityOrValue = -capacityOrValue;
      TIntArrayList list = new TIntArrayList(capacityOrValue);
      int prev = 0;
      while(capacityOrValue-- > 0) {
        int value = DataInputOutputUtil.readINT(in) + prev;
        list.add(value);
        prev = value;
      }
      return list;
    }
  };

  private final DataIndexer<Integer, TIntArrayList, FileContent> myIndexer = new DataIndexer<Integer, TIntArrayList, FileContent>() {
    @Override
    @NotNull
    public Map<Integer, TIntArrayList> map(@NotNull final FileContent inputData) {
      return ApplicationManager.getApplication().runReadAction(new Computable<Map<Integer, TIntArrayList>>() {
        @Override
        public Map<Integer, TIntArrayList> compute() {
          FileType type = inputData.getFileType();

          DuplicatesProfile profile = findDuplicatesProfile(type);
          if (profile == null) return Collections.emptyMap();

          MyFragmentsCollector collector = new MyFragmentsCollector(profile, ((LanguageFileType)type).getLanguage());
          DuplocateVisitor visitor = profile.createVisitor(collector, true);
          visitor.visitNode(((FileContentImpl)inputData).getPsiFileAccountingForUnsavedDocument());

          return collector.getMap();
        }
      });
    }
  };

  @Nullable
  public static DuplicatesProfile findDuplicatesProfile(FileType fileType) {
    if (!(fileType instanceof LanguageFileType)) return null;
    Language language = ((LanguageFileType)fileType).getLanguage();
    DuplicatesProfile profile = DuplicatesProfile.findProfileForLanguage(language);
    return profile != null && profile.supportIndex() ? profile : null;
  }

  @Override
  public int getVersion() {
    return myBaseVersion + (ourEnabled ? 0xFF : 0);
  }

  @Override
  public boolean dependsOnFileContent() {
    return true;
  }

  @NotNull
  @Override
  public ID<Integer,TIntArrayList> getName() {
    return NAME;
  }

  @NotNull
  @Override
  public DataIndexer<Integer, TIntArrayList, FileContent> getIndexer() {
    return myIndexer;
  }

  @NotNull
  @Override
  public DataExternalizer<TIntArrayList> getValueExternalizer() {
    return myValueExternalizer;
  }

  @NotNull
  @Override
  public KeyDescriptor<Integer> getKeyDescriptor() {
    return EnumeratorIntegerDescriptor.INSTANCE;
  }

  @NotNull
  @Override
  public FileBasedIndex.InputFilter getInputFilter() {
    return myInputFilter;
  }

  //private static final TracingData myTracingData = new TracingData();
  private static final TracingData myTracingData = null;

  private static class MyFragmentsCollector implements FragmentsCollector {
    private final THashMap<Integer, TIntArrayList> myMap = new THashMap<Integer, TIntArrayList>();
    private final DuplicatesProfile myProfile;
    private final DuplocatorState myDuplocatorState;

    public MyFragmentsCollector(DuplicatesProfile profile, Language language) {
      myProfile = profile;
      myDuplocatorState = profile.getDuplocatorState(language);
    }

    @Override
    public void add(int hash, int cost, @Nullable PsiFragment frag) {
      if (!isIndexedFragment(frag, cost, myProfile, myDuplocatorState)) {
        return;
      }

      if (myTracingData != null) myTracingData.record(hash, cost, frag);

      TIntArrayList list = myMap.get(hash);
      if (list == null) { myMap.put(hash, list = new TIntArrayList()); }
      list.add(frag.getStartOffset());
    }

    public THashMap<Integer,TIntArrayList> getMap() {
      return myMap;
    }
  }

  static boolean isIndexedFragment(@Nullable PsiFragment frag, int cost, DuplicatesProfile profile, DuplocatorState duplocatorState) {
    if(frag == null) return false;
    return profile.shouldPutInIndex(frag, cost, duplocatorState);
  }

  @TestOnly
  public static boolean setEnabled(boolean value) {
    boolean old = ourEnabled;
    ourEnabled = value;
    return old;
  }

  @Override
  public boolean hasSnapshotMapping() {
    return true;
  }
}
