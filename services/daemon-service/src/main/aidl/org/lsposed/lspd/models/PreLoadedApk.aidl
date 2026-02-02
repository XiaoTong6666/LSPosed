package org.lsposed.lspd.models;

import org.lsposed.lspd.os.SharedMemoryCompat;
parcelable PreLoadedApk {
    List<SharedMemoryCompat> preLoadedDexes;
    List<String> moduleClassNames;
    List<String> moduleLibraryNames;
    boolean legacy;
}
