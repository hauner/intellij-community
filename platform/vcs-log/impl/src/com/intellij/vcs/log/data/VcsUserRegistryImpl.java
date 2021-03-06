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
package com.intellij.vcs.log.data;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.io.*;
import com.intellij.vcs.log.VcsUser;
import com.intellij.vcs.log.VcsUserRegistry;
import com.intellij.vcs.log.impl.VcsUserImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 *
 */
public class VcsUserRegistryImpl implements Disposable, VcsUserRegistry {

  private static final File USER_CACHE_APP_DIR = new File(PathManager.getSystemPath(), "vcs-users");
  private static final Logger LOG = Logger.getInstance(VcsUserRegistryImpl.class);
  private static final PersistentEnumeratorBase.DataFilter ACCEPT_ALL_DATA_FILTER = new PersistentEnumeratorBase.DataFilter() {
    @Override
    public boolean accept(int id) {
      return true;
    }
  };

  @Nullable private final PersistentEnumerator<VcsUser> myPersistentEnumerator;

  VcsUserRegistryImpl(@NotNull Project project) {
    final File mapFile = new File(USER_CACHE_APP_DIR, project.getName() + "." + project.getLocationHash());
    Disposer.register(project, this);
    myPersistentEnumerator = initEnumerator(mapFile);
  }

  @Nullable
  private static PersistentEnumerator<VcsUser> initEnumerator(@NotNull final File mapFile) {
    try {
      return IOUtil.openCleanOrResetBroken(new ThrowableComputable<PersistentEnumerator<VcsUser>, IOException>() {
        @Override
        public PersistentEnumerator<VcsUser> compute() throws IOException {
          return new PersistentEnumerator<VcsUser>(mapFile, new MyDescriptor(), Page.PAGE_SIZE);
        }
      }, mapFile);
    }
    catch (IOException e) {
      LOG.warn(e);
      return null;
    }
  }

  public void addUser(@NotNull VcsUser user) {
    try {
      if (myPersistentEnumerator != null) {
        myPersistentEnumerator.enumerate(user);
      }
    }
    catch (IOException e) {
      LOG.warn(e);
    }
  }

  public void addUsers(@NotNull Collection<VcsUser> users) {
    for (VcsUser user : users) {
      addUser(user);
    }
  }

  @Override
  @NotNull
  public Set<VcsUser> getUsers() {
    try {
      Collection<VcsUser> users = myPersistentEnumerator != null ?
                                  myPersistentEnumerator.getAllDataObjects(ACCEPT_ALL_DATA_FILTER) :
                                  Collections.<VcsUser>emptySet();
      return ContainerUtil.newHashSet(users);
    }
    catch (IOException e) {
      LOG.warn(e);
      return Collections.emptySet();
    }
  }

  public void flush() {
    if (myPersistentEnumerator != null) {
      myPersistentEnumerator.force();
    }
  }

  @Override
  public void dispose() {
    try {
      if (myPersistentEnumerator != null) {
        myPersistentEnumerator.close();
      }
    }
    catch (IOException e) {
      LOG.warn(e);
    }
  }

  private static class MyDescriptor implements KeyDescriptor<VcsUser> {
    @Override
    public void save(@NotNull DataOutput out, VcsUser value) throws IOException {
      IOUtil.writeUTF(out, value.getName());
      IOUtil.writeUTF(out, value.getEmail());
    }

    @Override
    public VcsUser read(@NotNull DataInput in) throws IOException {
      String name = IOUtil.readUTF(in);
      String email = IOUtil.readUTF(in);
      return new VcsUserImpl(name, email);
    }

    @Override
    public int getHashCode(VcsUser value) {
      return value.hashCode();
    }

    @Override
    public boolean isEqual(VcsUser val1, VcsUser val2) {
      return val1.equals(val2);
    }
  }
}
