package org.lsposed.lspd.service;

import android.os.Build;
import org.lsposed.lspd.os.SharedMemoryCompat;

import java.util.HashMap;

public class ObfuscationManager {
    // For module dexes
    // API 27+ uses reflection to avoid class loading in SharedMemory.
    static native Object obfuscateDex(Object memory);

    // For module dexes compatibility layer
    static SharedMemoryCompat obfuscateDexCompat(SharedMemoryCompat memory) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // API 27+: Convert to SharedMemory (Object) processing
            Object sm = memory.getSharedMemoryObject();
            if (sm != null) {
                Object result = obfuscateDex(sm);
                if (result != sm) {
                    return SharedMemoryCompat.fromSharedMemory(result);
                }
            }
            return memory;
        } else {
            // API 26: Use compatible native method returning SharedMemoryCompat object
            var result = obfuscateDexNativeCompat(memory.getFd(), memory.getSize());
            if (result != null)
                return result;
            return memory;
        }
    }



    private static native SharedMemoryCompat obfuscateDexNativeCompat(int fd, int size);

    // generates signature
    static native HashMap<String, String> getSignatures();
}
