package org.lsposed.lspd.util;

import static de.robv.android.xposed.XposedBridge.TAG;

import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import org.lsposed.lspd.os.SharedMemoryCompat;
import org.lsposed.lspd.util.Utils.Log;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.InMemoryDexClassLoader;
import hidden.ByteBufferDexClassLoader;
import sun.misc.CompoundEnumeration;

/**
  * Module class loader
  * API 27+: Use ByteBufferDexClassLoader (inherits from BaseDexClassLoader, supports ByteBuffer[])
  * API 26: Use InMemoryDexClassLoader (only supports single ByteBuffers, requires chaining)
  */
@SuppressWarnings("ConstantConditions")
public final class LspModuleClassLoader extends ClassLoader {
    private static final String zipSeparator = "!/";
    private static final List<File> systemNativeLibraryDirs =
            splitPaths(System.getProperty("java.library.path"));
    private final String apk;
    private final List<File> nativeLibraryDirs = new ArrayList<>();
    // Hold references to prevent GC and unmap on destroy for API 26
    private ByteBuffer[] dexBuffers;
    private List<SharedMemoryCompat> sharedMemories;

    // Internal ClassLoader used by API 27+
    private final ByteBufferDexClassLoader delegateClassLoader;
    private static List<File> splitPaths(String searchPath) {
        var result = new ArrayList<File>();
        if (searchPath == null) return result;
        for (var path : searchPath.split(File.pathSeparator)) {
            result.add(new File(path));
        }
        return result;
    }

    // API 27+ constructor - using ByteBufferDexClassLoader
    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private LspModuleClassLoader(ByteBuffer[] dexBuffers,
                                 ClassLoader parent,
                                 String apk) {
        super(parent);
        this.delegateClassLoader = new ByteBufferDexClassLoader(dexBuffers, parent);
        this.apk = apk;
        this.dexBuffers = null; // Handled by ByteBufferDexClassLoader or unmapped immediately
    }

    // API 29+ constructor - using ByteBufferDexClassLoader with librarySearchPath
    @RequiresApi(Build.VERSION_CODES.Q)
    private LspModuleClassLoader(ByteBuffer[] dexBuffers,
                                 String librarySearchPath,
                                 ClassLoader parent,
                                 String apk) {
        super(parent);
        this.delegateClassLoader = new ByteBufferDexClassLoader(dexBuffers, librarySearchPath, parent);
        initNativeLibraryDirs(librarySearchPath);
        this.apk = apk;
        this.dexBuffers = null; // Handled by ByteBufferDexClassLoader or unmapped immediately
    }

    // API 26 Constructor: Using Chained InMemoryDexClassLoader
    private LspModuleClassLoader(ClassLoader chainedLoader,
                                 ClassLoader parent,
                                 String apk,
                                 String librarySearchPath,
                                 ByteBuffer[] dexBuffers,
                                 List<SharedMemoryCompat> sharedMemories) {
        super(chainedLoader);
        this.delegateClassLoader = null; // API 26 does not use ByteBufferDexClassLoader
        this.apk = apk;
        this.dexBuffers = dexBuffers;
        this.sharedMemories = sharedMemories;
        initNativeLibraryDirs(librarySearchPath);
    }

    private void initNativeLibraryDirs(String librarySearchPath) {
        nativeLibraryDirs.addAll(splitPaths(librarySearchPath));
        nativeLibraryDirs.addAll(systemNativeLibraryDirs);
    }

    public void destroy() {
        if (dexBuffers != null) {
            for (var buffer : dexBuffers) {
                SharedMemoryCompat.unmap(buffer);
            }
            dexBuffers = null;
        }
        if (sharedMemories != null) {
            for (var dex : sharedMemories) {
                dex.close();
            }
            sharedMemories = null;
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        var cl = findLoadedClass(name);
        if (cl != null) {
            return cl;
        }
        // First try loading from BootClassLoader
        try {
            return Object.class.getClassLoader().loadClass(name);
        } catch (ClassNotFoundException ignored) {
        }
        return findClass(name);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && delegateClassLoader != null) {
            // API 27+: Delegate to ByteBufferDexClassLoader
            return delegateClassLoader.loadClass(name);
        } else {
            // API 26: parent is the InMemoryDexClassLoader chain
            ClassLoader parent = getParent();
            if (parent != null) {
                return parent.loadClass(name);
            }
            throw new ClassNotFoundException(name);
        }
    }

    public String findLibrary(String libraryName) {
        var fileName = System.mapLibraryName(libraryName);
        for (var file : nativeLibraryDirs) {
            var path = file.getPath();
            if (path.contains(zipSeparator)) {
                var split = path.split(zipSeparator, 2);
                try (var jarFile = new JarFile(split[0])) {
                    var entryName = split[1] + '/' + fileName;
                    var entry = jarFile.getEntry(entryName);
                    if (entry != null && entry.getMethod() == ZipEntry.STORED) {
                        return split[0] + zipSeparator + entryName;
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Can not open " + split[0], e);
                }
            } else if (file.isDirectory()) {
                var entryPath = new File(file, fileName).getPath();
                try {
                    var fd = Os.open(entryPath, OsConstants.O_RDONLY, 0);
                    Os.close(fd);
                    return entryPath;
                } catch (ErrnoException ignored) {
                }
            }
        }
        return null;
    }

    public String getLdLibraryPath() {
        var result = new StringBuilder();
        for (var directory : nativeLibraryDirs) {
            if (result.length() > 0) {
                result.append(':');
            }
            result.append(directory);
        }
        return result.toString();
    }

    @Override
    protected URL findResource(String name) {
        try {
            var urlHandler = new ClassPathURLStreamHandler(apk);
            var url = urlHandler.getEntryUrlOrNull(name);
            if (url == null) {
                // noinspection FinalizeCalledExplicitly
                urlHandler.finalize();
            }
            return url;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        var result = new ArrayList<URL>();
        var url = findResource(name);
        if (url != null) result.add(url);
        return Collections.enumeration(result);
    }

    @Override
    public URL getResource(String name) {
        var resource = Object.class.getClassLoader().getResource(name);
        if (resource != null) return resource;
        resource = findResource(name);
        if (resource != null) return resource;
        final var cl = getParent();
        return (cl == null) ? null : cl.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        @SuppressWarnings("unchecked") final var resources = (Enumeration<URL>[]) new Enumeration<?>[]{
                Object.class.getClassLoader().getResources(name),
                findResources(name),
                getParent() == null ? null : getParent().getResources(name)};
        return new CompoundEnumeration<>(resources);
    }

    @NonNull
    @Override
    public String toString() {
        if (apk == null) return "LspModuleClassLoader[instantiating]";
        return "LspModuleClassLoader[module=" + apk + ", api=" + Build.VERSION.SDK_INT + "]";
    }

    /**
     * Load the module APK
     * API 27+: Use ByteBufferDexClassLoader (supports ByteBuffer[])
     * API 26: Use chained InMemoryDexClassLoader (one ClassLoader per Dex)
     */
    public static ClassLoader loadApk(String apk,
                                      List<SharedMemoryCompat> dexes,
                                      String librarySearchPath,
                                      ClassLoader parent) {
        var dexBuffers = dexes.stream().parallel().map(dex -> {
            try {
                return dex.mapReadOnly();
            } catch (ErrnoException e) {
                Log.w(TAG, "Can not map " + dex, e);
                return null;
            }
        }).filter(Objects::nonNull).toArray(ByteBuffer[]::new);
        ClassLoader result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // API 27+: Using ByteBufferDexClassLoader
            LspModuleClassLoader cl;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cl = new LspModuleClassLoader(dexBuffers, librarySearchPath, parent, apk);
            } else {
                cl = new LspModuleClassLoader(dexBuffers, parent, apk);
                cl.initNativeLibraryDirs(librarySearchPath);
            }
            result = cl;
        } else {
            // API 26: Using Chained InMemoryDexClassLoaders
            Log.d(TAG, "Creating API 26 ClassLoader with path: " + librarySearchPath);
            result = createApi26ClassLoader(dexBuffers, dexes, parent, apk, librarySearchPath);
        }

        // API 26 (Android 8.0) InMemoryDexClassLoader references the ByteBuffer directly (Zero Copy).
        // Unmapping it causes SIGSEGV when ART accesses the Dex data.
        // API 27+ (Android 8.1+) typically copies or handles it differently, so we unmap to release resources.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            Arrays.stream(dexBuffers).parallel().forEach(SharedMemoryCompat::unmap);
            dexes.stream().parallel().forEach(SharedMemoryCompat::close);
        }
        return result;
    }

    /**
     * API 26 Specific: Creating a Chained InMemoryDexClassLoader
     * Because API 26's InMemoryDexClassLoader only supports a single ByteBuffer,
     * we need to create a ClassLoader for each Dex and chain them together.
     */
    @SuppressWarnings("JavaReflectionMemberAccess")
    private static ClassLoader createApi26ClassLoader(ByteBuffer[] dexBuffers,
            List<SharedMemoryCompat> dexes,
            ClassLoader parent,
            String apk,
            String librarySearchPath) {

        if (dexBuffers.length == 0) {
            Log.e(TAG, "No dex buffers provided for API 26 class loader");
            return parent;
        }

        InMemoryDexClassLoader loader = new InMemoryDexClassLoader(dexBuffers[0], parent);
        Object pathList;
        Class<?> dexPathListClass;

        // 1. Flatten Dexes
        try {
            dexPathListClass = Class.forName("dalvik.system.DexPathList");
            java.lang.reflect.Field pathListField = BaseDexClassLoader.class.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            pathList = pathListField.get(loader);

            java.lang.reflect.Field dexElementsField = dexPathListClass.getDeclaredField("dexElements");
            dexElementsField.setAccessible(true);
            Object[] existingElements = (Object[]) dexElementsField.get(pathList);

            if (dexBuffers.length > 1) {
                // Use Class.forName to reliably get the inner class
                Class<?> elementClass;
                try {
                    elementClass = Class.forName("dalvik.system.DexPathList$Element");
                } catch (ClassNotFoundException e) {
                    elementClass = Arrays.stream(dexPathListClass.getDeclaredClasses())
                        .filter(c -> c.getSimpleName().equals("Element"))
                        .findFirst()
                        .orElseThrow(() -> new ClassNotFoundException("DexPathList$Element not found"));
                }

                // Robustly find Element constructor
                java.lang.reflect.Constructor<?> elementConstructor = null;
                for (java.lang.reflect.Constructor<?> c : elementClass.getDeclaredConstructors()) {
                    // We look for a constructor that takes DexFile as the first or second argument
                    Class<?>[] params = c.getParameterTypes();
                    if (Arrays.stream(params).anyMatch(p -> p == dalvik.system.DexFile.class)) {
                        elementConstructor = c;
                        break;
                    }
                }
                if (elementConstructor == null) throw new NoSuchMethodException("Element constructor taking DexFile not found");
                elementConstructor.setAccessible(true);

                // Robustly find DexFile constructor taking ByteBuffer
                java.lang.reflect.Constructor<dalvik.system.DexFile> dexFileConstructor = null;
                try {
                    dexFileConstructor = dalvik.system.DexFile.class.getDeclaredConstructor(ByteBuffer.class);
                } catch (NoSuchMethodException e) {
                     // Try to find any constructor taking ByteBuffer
                     for (java.lang.reflect.Constructor<?> c : dalvik.system.DexFile.class.getDeclaredConstructors()) {
                         if (c.getParameterCount() == 1 && c.getParameterTypes()[0] == ByteBuffer.class) {
                             //noinspection unchecked
                             dexFileConstructor = (java.lang.reflect.Constructor<dalvik.system.DexFile>) c;
                             break;
                         }
                     }
                }
                if (dexFileConstructor == null) throw new NoSuchMethodException("DexFile(ByteBuffer) constructor not found");
                dexFileConstructor.setAccessible(true);

                int additionalCount = dexBuffers.length - 1;
                Object[] newElements = (Object[]) java.lang.reflect.Array.newInstance(existingElements.getClass().getComponentType(), additionalCount);

                for (int i = 0; i < additionalCount; i++) {
                    ByteBuffer buf = dexBuffers[i + 1];
                    dalvik.system.DexFile dexFile = dexFileConstructor.newInstance(buf);
                    
                    // Create Element instance based on constructor parameters
                    Object[] args = new Object[elementConstructor.getParameterCount()];
                    Class<?>[] params = elementConstructor.getParameterTypes();
                    for (int j = 0; j < params.length; j++) {
                        if (params[j] == dalvik.system.DexFile.class) {
                            args[j] = dexFile;
                        } else if (params[j] == File.class) {
                            args[j] = null; // No file for in-memory dex
                        } else if (params[j] == boolean.class) {
                            args[j] = false; // "isDirectory" usually
                        } else {
                            args[j] = null;
                        }
                    }
                    newElements[i] = elementConstructor.newInstance(args);
                }

                // Merge arrays
                Object[] combined = (Object[]) java.lang.reflect.Array.newInstance(existingElements.getClass().getComponentType(), existingElements.length + newElements.length);
                System.arraycopy(existingElements, 0, combined, 0, existingElements.length);
                System.arraycopy(newElements, 0, combined, existingElements.length, newElements.length);

                dexElementsField.set(pathList, combined);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to optimize InMemoryDexClassLoader (Flattening) on API 26", e);
            // Fallback to chaining
            ClassLoader current = parent;
            for (int i = dexBuffers.length - 1; i >= 0; i--) {
                try {
                    current = new InMemoryDexClassLoader(dexBuffers[i], current);
                } catch (Exception ex) {
                    Log.e(TAG, "Failed to create InMemoryDexClassLoader for dex " + i, ex);
                }
            }
            return new LspModuleClassLoader(current, parent, apk, librarySearchPath, dexBuffers, dexes);
        }

        // 2. Inject Native Library Path (with extraction support)
        try {
            Log.d(TAG, "Injecting library search path: " + librarySearchPath);
            if (librarySearchPath != null && !librarySearchPath.isEmpty()) {
                
                List<File> libFiles = new ArrayList<>();
                    // Use app cache dir if possible, fallback to tmp
                    File cacheDir = null;
                    File extractionDir = null;
                    try {
                            Class<?> atClass = Class.forName("android.app.ActivityThread");
                            Object at = atClass.getMethod("currentActivityThread").invoke(null);
                            if (at != null) {
                                Object app = atClass.getMethod("getApplication").invoke(at);
                                if (app != null) {
                                    cacheDir = (File) app.getClass().getMethod("getCacheDir").invoke(app);
                                }
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "Failed to get cache dir", t);
                        }
                    
                    if (cacheDir == null) {
                         String t = System.getProperty("java.io.tmpdir");
                         if (t != null) cacheDir = new File(t);
                    }

                    if (cacheDir != null) {
                         extractionDir = new File(cacheDir, "lsposed_libs_" + Integer.toHexString(librarySearchPath.hashCode()));
                    }

                for (File file : splitPaths(librarySearchPath)) {
                    String path = file.getPath();
                    if (path.contains(zipSeparator) && extractionDir != null) {
                        String[] split = path.split(zipSeparator, 2);
                        File apkFile = new File(split[0]);
                        String internalPath = split[1];
                        // Strip leading slash if present
                        if (internalPath.startsWith("/")) internalPath = internalPath.substring(1);
                        // Ensure trailing slash for directory matching
                        if (!internalPath.endsWith("/")) internalPath += "/";

                        // Extract
                        if (!extractionDir.exists() && !extractionDir.mkdirs()) {
                            Log.w(TAG, "Failed to create extraction dir: " + extractionDir);
                        } else {
                            try (JarFile jar = new JarFile(apkFile)) {
                                Enumeration<JarEntry> entries = jar.entries();
                                while (entries.hasMoreElements()) {
                                    JarEntry entry = entries.nextElement();
                                    if (!entry.isDirectory() && entry.getName().startsWith(internalPath) && entry.getName().endsWith(".so")) {
                                        File destFile = new File(extractionDir, new File(entry.getName()).getName());
                                        // Simple overwrite or skip logic
                                        if (!destFile.exists() || destFile.length() != entry.getSize()) {
                                            try (java.io.InputStream is = jar.getInputStream(entry);
                                                 java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile)) {
                                                byte[] buffer = new byte[8192];
                                                int read;
                                                while ((read = is.read(buffer)) != -1) {
                                                    fos.write(buffer, 0, read);
                                                }
                                            }
                                        }
                                    }
                                }
                                libFiles.add(extractionDir);
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to extract libs", e);
                            }
                        }
                    } else {
                        libFiles.add(file);
                    }
                }

                if (!libFiles.isEmpty()) {
                    Log.d(TAG, "Injecting native libs: " + libFiles);
                    // Update nativeLibraryDirectories
                    java.lang.reflect.Field nativeLibraryDirectoriesField = dexPathListClass.getDeclaredField("nativeLibraryDirectories");
                    nativeLibraryDirectoriesField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    List<File> oldNativeLibraryDirectories = (List<File>) nativeLibraryDirectoriesField.get(pathList);
                    List<File> nativeLibraryDirectories = new ArrayList<>(libFiles);
                    if (oldNativeLibraryDirectories != null) {
                        nativeLibraryDirectories.addAll(oldNativeLibraryDirectories);
                    }
                    nativeLibraryDirectoriesField.set(pathList, nativeLibraryDirectories);

                    // Update nativeLibraryPathElements
                    java.lang.reflect.Field nativeLibraryPathElementsField = dexPathListClass.getDeclaredField("nativeLibraryPathElements");
                    nativeLibraryPathElementsField.setAccessible(true);
                    Object[] existingLibElements = (Object[]) nativeLibraryPathElementsField.get(pathList);

                    // Try to find makePathElements
                    java.lang.reflect.Method makePathElements = null;
                    try {
                         makePathElements = dexPathListClass.getDeclaredMethod("makePathElements", List.class, File.class, List.class);
                    } catch (NoSuchMethodException e) {
                        try {
                            makePathElements = dexPathListClass.getDeclaredMethod("makePathElements", List.class);
                        } catch (NoSuchMethodException ignored) {}
                    }
                    
                        Object[] newLibElements = null;
                        Class<?> componentType = existingLibElements.getClass().getComponentType();
                        
                        if (makePathElements != null && makePathElements.getReturnType().getComponentType() == componentType) {
                             if (makePathElements.getParameterCount() == 3) {
                                  newLibElements = (Object[]) makePathElements.invoke(null, libFiles, null, new ArrayList<IOException>());
                             } else {
                                  newLibElements = (Object[]) makePathElements.invoke(null, libFiles);
                             }
                        } else {
                             // Manual creation if makePathElements is missing or incompatible types
                             // API 26 (Oreo) uses NativeLibraryElement
                             try {
                                 java.lang.reflect.Constructor<?> ctor = componentType.getConstructor(File.class);
                                 ctor.setAccessible(true);
                                 newLibElements = (Object[]) java.lang.reflect.Array.newInstance(componentType, libFiles.size());
                                 for (int i=0; i<libFiles.size(); i++) {
                                     newLibElements[i] = ctor.newInstance(libFiles.get(i));
                                 }
                             } catch (Exception ex) {
                                 Log.e(TAG, "Failed to create NativeLibraryElement manually", ex);
                             }
                        }

                        if (newLibElements != null) {

                        Object[] combinedLibElements = (Object[]) java.lang.reflect.Array.newInstance(existingLibElements.getClass().getComponentType(), existingLibElements.length + newLibElements.length);
                        System.arraycopy(newLibElements, 0, combinedLibElements, 0, newLibElements.length);
                        System.arraycopy(existingLibElements, 0, combinedLibElements, newLibElements.length, existingLibElements.length);

                        nativeLibraryPathElementsField.set(pathList, combinedLibElements);
                    } else {
                        Log.w(TAG, "Could not find makePathElements to inject library path elements on API 26");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to inject native libraries on API 26", e);
            // Do not fallback; return the flattened loader as it is better than nothing
        }

        return new LspModuleClassLoader(loader, parent, apk, librarySearchPath, dexBuffers, dexes);
    }
}
