package org.lsposed.lspd.os;

import android.os.Build;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import android.system.Os;
import android.system.StructStat;

/**
 * SharedMemory compatibility layer
 *
 * API 27+: Use reflection to call android.os.SharedMemory (avoid referencing during class loading)
 * API 26: Use native implementation with ashmem
 *
 * - Do not directly reference the SharedMemory type in the class definition
 * - Use Object to hold the SharedMemory instance
 * - All SharedMemory method calls use reflection
 */
public class SharedMemoryCompat implements Parcelable, Closeable {
    private static final String TAG = "SharedMemoryCompat";

    // API 27+: Use Object to hold SharedMemory instances to avoid class loading resolution
    private Object sharedMemoryObj;

    // API 26: Holding file descriptors and sizes
    private int fd = -1;
    private int size = 0;

    // Reflection cache
    private static Class<?> sharedMemoryClass;
    private static Method createMethod;
    private static Method mapReadOnlyMethod;
    private static Method mapReadWriteMethod;
    private static Method unmapMethod;
    private static Method setProtectMethod;
    private static Method getSizeMethod;
    private static Method closeMethod;
    private static Method getFileDescriptorMethod;
    private static Constructor<?> smConstructor;
    private static Field fdDescriptorField;
    private static Constructor<?> directByteBufferConstructor;
    private static boolean isNativeLoaded = false;

    static {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                sharedMemoryClass = Class.forName("android.os.SharedMemory");
                createMethod = sharedMemoryClass.getMethod("create", String.class, int.class);
                mapReadOnlyMethod = sharedMemoryClass.getMethod("mapReadOnly");
                mapReadWriteMethod = sharedMemoryClass.getMethod("mapReadWrite");
                unmapMethod = sharedMemoryClass.getMethod("unmap", ByteBuffer.class);
                setProtectMethod = sharedMemoryClass.getMethod("setProtect", int.class);
                getSizeMethod = sharedMemoryClass.getMethod("getSize");
                closeMethod = sharedMemoryClass.getMethod("close");
                getFileDescriptorMethod = sharedMemoryClass.getDeclaredMethod("getFileDescriptor");
                getFileDescriptorMethod.setAccessible(true);
                smConstructor = sharedMemoryClass.getDeclaredConstructor(FileDescriptor.class);
                smConstructor.setAccessible(true);
                fdDescriptorField = FileDescriptor.class.getDeclaredField("descriptor");
                fdDescriptorField.setAccessible(true);
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize SharedMemory reflection", e);
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            try {
                System.loadLibrary("lspd"); // Try loading lspd instead of daemon
                isNativeLoaded = true;
            } catch (UnsatisfiedLinkError e) {
                // Try loading daemon just in case
                try {
                    System.loadLibrary("daemon");
                    isNativeLoaded = true;
                } catch (UnsatisfiedLinkError e2) {
                    Log.w(TAG, "Native library not loaded, using Os.mmap fallback on API 26");
                }
            }
            try {
                fdDescriptorField = FileDescriptor.class.getDeclaredField("descriptor");
                fdDescriptorField.setAccessible(true);
            } catch (Exception e) {
                Log.e(TAG, "Failed to get FileDescriptor.descriptor field", e);
            }
            try {
                Class<?> dbbClass = Class.forName("java.nio.DirectByteBuffer");
                directByteBufferConstructor = dbbClass.getDeclaredConstructor(long.class, int.class);
                directByteBufferConstructor.setAccessible(true);
            } catch (Exception e) {
                Log.w(TAG, "Failed to get DirectByteBuffer constructor", e);
            }
        }
    }

    // API 27+ Wrapper Constructor
    private SharedMemoryCompat(Object sharedMemory) {
        this.sharedMemoryObj = sharedMemory;
    }

    // API 26 constructor
    private SharedMemoryCompat(int fd, int size) {
        this.sharedMemoryObj = null;
        this.fd = fd;
        this.size = size;
    }

    // Read from Parcel
    protected SharedMemoryCompat(Parcel in) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // API 27+: Reading SharedMemory objects
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    sharedMemoryObj = in.readParcelable(sharedMemoryClass.getClassLoader(), sharedMemoryClass);
                } else {
                    sharedMemoryObj = in.readParcelable(sharedMemoryClass.getClassLoader());
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to read SharedMemory from parcel", e);
                sharedMemoryObj = null;
            }
            fd = -1;
            size = 0;
        } else {
            // API 26: Read fd and size
            sharedMemoryObj = null;
            ParcelFileDescriptor pfd = in.readFileDescriptor();
            if (pfd != null) {
                fd = pfd.detachFd();
            } else {
                fd = -1;
            }
            size = in.readInt();
        }
    }

    public static final Creator<SharedMemoryCompat> CREATOR = new Creator<SharedMemoryCompat>() {
        @Override
        public SharedMemoryCompat createFromParcel(Parcel in) {
            return new SharedMemoryCompat(in);
        }

        @Override
        public SharedMemoryCompat[] newArray(int size) {
            return new SharedMemoryCompat[size];
        }
    };

    // Create shared memory
    public static SharedMemoryCompat create(String name, int size) throws ErrnoException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // API 27+: Using reflection to call SharedMemory.create
            try {
                Object sm = createMethod.invoke(null, name, size);
                return new SharedMemoryCompat(sm);
            } catch (Exception e) {
                throw new ErrnoException("SharedMemory.create", OsConstants.ENOMEM, e);
            }
        } else {
            // API 26: Using ashmem
            int fd = nativeCreate(name, size);
            if (fd < 0) {
                throw new ErrnoException("ashmem_create", -fd);
            }
            return new SharedMemoryCompat(fd, size);
        }
    }

    // API 27+: From existing SharedMemory wrappers
    public static SharedMemoryCompat fromSharedMemory(Object sm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return new SharedMemoryCompat(sm);
        }
        throw new UnsupportedOperationException("fromSharedMemory requires API 27+");
    }

    // Create from fd
    public static SharedMemoryCompat fromFd(int fd, int size) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // API 27+: Wrapped as SharedMemory
            try {
                FileDescriptor fileDescriptor = new FileDescriptor();
                fdDescriptorField.setInt(fileDescriptor, fd);
                Object sm = smConstructor.newInstance(fileDescriptor);
                return new SharedMemoryCompat(sm);
            } catch (Exception e) {
                Log.e(TAG, "Failed to create SharedMemory from fd", e);
                return null;
            }
        } else {
            // API 26: Using fd
            return new SharedMemoryCompat(fd, size);
        }
    }

    private void checkOpen() {
        if (fd < 0) {
            throw new IllegalStateException("SharedMemoryCompat has been closed");
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1 && fd >= 0) {
                close();
            }
        } finally {
            super.finalize();
        }
    }

    // Map to read-only ByteBuffer
    public ByteBuffer mapReadOnly() throws ErrnoException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                return (ByteBuffer) mapReadOnlyMethod.invoke(sharedMemoryObj);
            } catch (Exception e) {
                throw new ErrnoException("mapReadOnly", OsConstants.ENOMEM, e);
            }
        } else {
            checkOpen();
            if (isNativeLoaded) {
                try {
                    ByteBuffer buffer = nativeMap(fd, size, true);
                    if (buffer != null) {
                        return buffer;
                    }
                } catch (UnsatisfiedLinkError e) {
                    Log.w(TAG, "nativeMap failed, falling back to Os.mmap");
                }
            }

            // Fallback to Os.mmap
            if (directByteBufferConstructor == null) {
                throw new ErrnoException("mmap", OsConstants.ENOMEM);
            }
            try {
                FileDescriptor fileDescriptor = new FileDescriptor();
                fdDescriptorField.setInt(fileDescriptor, fd);
                long addr = Os.mmap(0, size, OsConstants.PROT_READ, OsConstants.MAP_SHARED, fileDescriptor, 0);
                return (ByteBuffer) directByteBufferConstructor.newInstance(addr, size);
            } catch (Exception e) {
                if (e instanceof ErrnoException)
                    throw (ErrnoException) e;
                throw new ErrnoException("mmap", OsConstants.ENOMEM, e);
            }
        }
    }

    // Maps to a ByteBuffer for reading and writing
    public ByteBuffer mapReadWrite() throws ErrnoException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                return (ByteBuffer) mapReadWriteMethod.invoke(sharedMemoryObj);
            } catch (Exception e) {
                throw new ErrnoException("mapReadWrite", OsConstants.ENOMEM, e);
            }
        } else {
            checkOpen();
            if (isNativeLoaded) {
                try {
                    ByteBuffer buffer = nativeMap(fd, size, false);
                    if (buffer != null) {
                        return buffer;
                    }
                } catch (UnsatisfiedLinkError e) {
                    Log.w(TAG, "nativeMap failed, falling back to Os.mmap");
                }
            }

            // Fallback to Os.mmap
            if (directByteBufferConstructor == null) {
                throw new ErrnoException("mmap", OsConstants.ENOMEM);
            }
            try {
                FileDescriptor fileDescriptor = new FileDescriptor();
                fdDescriptorField.setInt(fileDescriptor, fd);
                long addr = Os.mmap(0, size, OsConstants.PROT_READ | OsConstants.PROT_WRITE, OsConstants.MAP_SHARED,
                        fileDescriptor, 0);
                return (ByteBuffer) directByteBufferConstructor.newInstance(addr, size);
            } catch (Exception e) {
                if (e instanceof ErrnoException)
                    throw (ErrnoException) e;
                throw new ErrnoException("mmap", OsConstants.ENOMEM, e);
            }
        }
    }

    public static void unmap(ByteBuffer buffer, int size) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                unmapMethod.invoke(null, buffer);
            } catch (Exception e) {
                Log.e(TAG, "Failed to unmap", e);
            }
        } else {
            if (isNativeLoaded) {
                try {
                    nativeUnmap(buffer, size);
                    return;
                } catch (UnsatisfiedLinkError e) {
                    // Fall through to fallback
                }
            }

            // Fallback Os.munmap
            try {
                Field addrField = java.nio.Buffer.class.getDeclaredField("address");
                addrField.setAccessible(true);
                long addr = addrField.getLong(buffer);
                Os.munmap(addr, size);
            } catch (Exception ex) {
                Log.w(TAG, "Failed to unmap buffer", ex);
            }
        }
    }

    // Cancel mapping
    public static void unmap(ByteBuffer buffer) {
        unmap(buffer, buffer.capacity());
    }

    // Get size
    public int getSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                return (int) getSizeMethod.invoke(sharedMemoryObj);
            } catch (Exception e) {
                Log.e(TAG, "Failed to get size", e);
                return 0;
            }
        } else {
            checkOpen();
            return size;
        }
    }

    // Get the file descriptor (int)
    public int getFd() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                FileDescriptor fileDescriptor = (FileDescriptor) getFileDescriptorMethod.invoke(sharedMemoryObj);
                return fdDescriptorField.getInt(fileDescriptor);
            } catch (Exception e) {
                Log.e(TAG, "Failed to get fd from SharedMemory", e);
                return -1;
            }
        } else {
            checkOpen();
            return fd;
        }
    }

    // Set protection mode
    public boolean setProtect(int prot) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                return (boolean) setProtectMethod.invoke(sharedMemoryObj, prot);
            } catch (Exception e) {
                Log.e(TAG, "Failed to set protect", e);
                return false;
            }
        } else {
            checkOpen();
            if (isNativeLoaded) {
                try {
                    return nativeSetProt(fd, prot) == 0;
                } catch (UnsatisfiedLinkError e) {
                    return false;
                }
            }
            return false;
        }
    }

    // API 27+: Retrieves the raw SharedMemory object
    // Returns Object to avoid class loading issues
    public Object getSharedMemoryObject() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return sharedMemoryObj;
        }
        return null;
    }

    @Override
    public void close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            if (sharedMemoryObj != null) {
                try {
                    closeMethod.invoke(sharedMemoryObj);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to close SharedMemory", e);
                }
            }
        } else {
            if (fd >= 0) {
                boolean closed = false;
                if (isNativeLoaded) {
                    try {
                        nativeClose(fd);
                        closed = true;
                    } catch (UnsatisfiedLinkError e) {
                        // fallback
                    }
                }

                if (!closed) {
                    try {
                        FileDescriptor fileDescriptor = new FileDescriptor();
                        fdDescriptorField.setInt(fileDescriptor, fd);
                        Os.close(fileDescriptor);
                    } catch (Exception ex) {
                        // ignore
                    }
                }
                fd = -1;
            }
        }
    }

    @Override
    public int describeContents() {
        return Parcelable.CONTENTS_FILE_DESCRIPTOR;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // API 27+: Writing to SharedMemory objects
            dest.writeParcelable((Parcelable) sharedMemoryObj, flags);
        } else {
            // API 26: Write fd and size
            if (fd >= 0) {
                try {
                    FileDescriptor fileDescriptor = new FileDescriptor();
                    fdDescriptorField.setInt(fileDescriptor, fd);
                    dest.writeFileDescriptor(fileDescriptor);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to write fd to parcel", e);
                    dest.writeFileDescriptor(null);
                }
            } else {
                dest.writeFileDescriptor(null);
            }
            dest.writeInt(size);
        }
    }

    // API 26: Native Methods

    public void writeFdToParcel(Parcel dest) {
        int fd = getFd();
        if (fd < 0) {
            dest.writeFileDescriptor(null);
            return;
        }
        try {
            FileDescriptor fileDescriptor = new FileDescriptor();
            fdDescriptorField.setInt(fileDescriptor, fd);
            dest.writeFileDescriptor(fileDescriptor);
        } catch (Exception e) {
            Log.e(TAG, "Failed to write fd to parcel", e);
            dest.writeFileDescriptor(null);
        }
    }

    public static long getFileSize(int fd) {
        if (fd < 0)
            return 0;
        try {
            FileDescriptor fileDescriptor = new FileDescriptor();
            fdDescriptorField.setInt(fileDescriptor, fd);
            StructStat stat = Os.fstat(fileDescriptor);
            return stat.st_size;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get file size", e);
            return 0;
        }
    }

    private static native int nativeCreate(String name, int size);

    private static native ByteBuffer nativeMap(int fd, int size, boolean readOnly);

    private static native void nativeUnmap(ByteBuffer buffer, int size);

    private static native void nativeClose(int fd);

    private static native int nativeSetProt(int fd, int prot);
}
