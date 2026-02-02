/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.lspd.hooker;

import static org.lsposed.lspd.core.ApplicationServiceClient.serviceClient;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.app.LoadedApk;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import java.lang.reflect.Method;
import org.lsposed.lspd.nativebridge.HookBridge;

import androidx.annotation.NonNull;

import org.lsposed.lspd.impl.LSPosedContext;
import org.lsposed.lspd.util.Hookers;
import org.lsposed.lspd.util.MetaDataReader;
import org.lsposed.lspd.util.Utils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.annotations.AfterInvocation;
import io.github.libxposed.api.annotations.XposedHooker;

@SuppressLint("BlockedPrivateApi")
@XposedHooker
public class LoadedApkCreateCLHooker implements XposedInterface.Hooker {
    private final static Field defaultClassLoaderField;

    private final static Set<LoadedApk> loadedApks = ConcurrentHashMap.newKeySet();

    static {
        Field field = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                field = LoadedApk.class.getDeclaredField("mDefaultClassLoader");
                field.setAccessible(true);
            } catch (Throwable ignored) {
            }
        }
        defaultClassLoaderField = field;
    }

    static void addLoadedApk(LoadedApk loadedApk) {
        loadedApks.add(loadedApk);
    }

    @AfterInvocation
    public static void afterHookedMethod(XposedInterface.AfterHookCallback callback) {
        LoadedApk loadedApk = (LoadedApk) callback.getThisObject();

        if (callback.getArgs()[0] != null || !loadedApks.contains(loadedApk)) {
            return;
        }

        try {
            Hookers.logD("LoadedApk#createClassLoader starts");

            String packageName = ActivityThread.currentPackageName();
            String processName = ActivityThread.currentProcessName();
            boolean isFirstPackage = packageName != null && processName != null && packageName.equals(loadedApk.getPackageName());
            if (!isFirstPackage) {
                packageName = loadedApk.getPackageName();
                processName = ActivityThread.currentPackageName();
            } else if (packageName.equals("android")) {
                packageName = "system";
            }

            Object mAppDir = XposedHelpers.getObjectField(loadedApk, "mAppDir");
            ClassLoader classLoader = (ClassLoader) XposedHelpers.getObjectField(loadedApk, "mClassLoader");
            Hookers.logD("LoadedApk#createClassLoader ends: " + mAppDir + " -> " + classLoader);

            if (classLoader == null) {
                return;
            }

            if (!isFirstPackage && !XposedHelpers.getBooleanField(loadedApk, "mIncludeCode")) {
                Hookers.logD("LoadedApk#<init> mIncludeCode == false: " + mAppDir);
                return;
            }

            if (!isFirstPackage && !XposedInit.getLoadedModules().getOrDefault(packageName, Optional.of("")).isPresent()) {
                // XposedBridge.log("LSPosed: Skipping " + packageName + " (not first package and no modules)");
                return;
            }
            
            boolean hasModules = XposedInit.getLoadedModules().getOrDefault(packageName, Optional.empty()).isPresent();
            if (hasModules) {
                 XposedBridge.log("LSPosed: Package " + packageName + " has modules loaded. first=" + isFirstPackage);
            }

            XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(
                    XposedBridge.sLoadedPackageCallbacks);
            lpparam.packageName = packageName;
            lpparam.processName = processName;
            lpparam.classLoader = classLoader;
            lpparam.appInfo = loadedApk.getApplicationInfo();
            lpparam.isFirstApplication = isFirstPackage;

            if (isFirstPackage) {
                if (hasModules) {
                    hookNewXSP(lpparam);
                }
                XposedBridge.log("LSPosed: invoking hookApplicationFix for " + lpparam.packageName);
                hookApplicationFix(lpparam, loadedApk);
            }

            Hookers.logD("Call handleLoadedPackage: packageName=" + lpparam.packageName + " processName=" + lpparam.processName + " isFirstPackage=" + isFirstPackage + " classLoader=" + lpparam.classLoader + " appInfo=" + lpparam.appInfo);
            XC_LoadPackage.callAll(lpparam);

            LSPosedContext.callOnPackageLoaded(new XposedModuleInterface.PackageLoadedParam() {
                @NonNull
                @Override
                public String getPackageName() {
                    return loadedApk.getPackageName();
                }

                @NonNull
                @Override
                public ApplicationInfo getApplicationInfo() {
                    return loadedApk.getApplicationInfo();
                }

                @NonNull
                @Override
                public ClassLoader getDefaultClassLoader() {
                    try {
                        return (ClassLoader) defaultClassLoaderField.get(loadedApk);
                    } catch (Throwable t) {
                        throw new IllegalStateException(t);
                    }
                }

                @NonNull
                @Override
                public ClassLoader getClassLoader() {
                    return classLoader;
                }

                @Override
                public boolean isFirstPackage() {
                    return isFirstPackage;
                }
            });
        } catch (Throwable t) {
            Hookers.logE("error when hooking LoadedApk#createClassLoader", t);
        } finally {
            loadedApks.remove(loadedApk);
        }
    }

    private static void hookNewXSP(XC_LoadPackage.LoadPackageParam lpparam) {
        int xposedminversion = -1;
        boolean xposedsharedprefs = false;
        try {
            Map<String, Object> metaData = MetaDataReader.getMetaData(new File(lpparam.appInfo.sourceDir));
            Object minVersionRaw = metaData.get("xposedminversion");
            if (minVersionRaw instanceof Integer) {
                xposedminversion = (Integer) minVersionRaw;
            } else if (minVersionRaw instanceof String) {
                xposedminversion = MetaDataReader.extractIntPart((String) minVersionRaw);
            }
            xposedsharedprefs = metaData.containsKey("xposedsharedprefs");
        } catch (NumberFormatException | IOException e) {
            Hookers.logE("ApkParser fails", e);
        }

        if (xposedminversion > 92 || xposedsharedprefs) {
            Utils.logI("New modules detected, hook preferences");
            XposedHelpers.findAndHookMethod("android.app.ContextImpl", lpparam.classLoader, "checkMode", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (((int) param.args[0] & 1/*Context.MODE_WORLD_READABLE*/) != 0) {
                        param.setThrowable(null);
                    }
                }
            });
            XposedHelpers.findAndHookMethod("android.app.ContextImpl", lpparam.classLoader, "getPreferencesDir", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return new File(serviceClient.getPrefsPath(lpparam.packageName));
                }
            });
        }
    }

    private static void hookApplicationFix(XC_LoadPackage.LoadPackageParam lpparam, Object loadedApk) {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.O && Build.VERSION.SDK_INT != Build.VERSION_CODES.O_MR1) {
            return;
        }
        XposedBridge.log("LSPosed: Starting polling for Application instance on " + lpparam.packageName);
        
        new Thread(() -> {
            try {
                Application app = null;
                for (int i = 0; i < 50; i++) { // Poll for up to 5 seconds
                    if (i % 10 == 0) XposedBridge.log("LSPosed: Polling iteration " + i + " for " + lpparam.packageName);
                    try {
                        app = (Application) XposedHelpers.getObjectField(loadedApk, "mApplication");
                    } catch (Throwable t) {
                         // Ignore field access error during early init
                    }
                    if (app != null) {
                         XposedBridge.log("LSPosed: Found Application instance for " + lpparam.packageName);
                         break;
                    }
                    Thread.sleep(100);
                }
                
                if (app == null) {
                    XposedBridge.log("LSPosed Error: Timed out waiting for Application on " + lpparam.packageName);
                    return;
                }
                
                if (XposedHelpers.getAdditionalInstanceField(app, "LSPosed_Attach_Ran") != null) return;
                XposedHelpers.setAdditionalInstanceField(app, "LSPosed_Attach_Ran", true);

                Method attachMethod = Application.class.getDeclaredMethod("attach", Context.class);
                Object[][] callbacks = HookBridge.callbackSnapshot(XC_MethodHook.class, attachMethod);
                if (callbacks != null && callbacks.length > 1 && callbacks[1] != null) {
                    Object[] legacyCallbacks = callbacks[1];
                    if (legacyCallbacks.length > 0) {
                        XposedBridge.log("LSPosed: Manually invoking " + legacyCallbacks.length + " Application.attach callbacks (via polling) for " + app.getPackageName());
                        
                        XC_MethodHook.MethodHookParam attachParam = new XC_MethodHook.MethodHookParam();
                        attachParam.method = attachMethod;
                        attachParam.thisObject = app;
                        attachParam.args = new Object[]{app.getBaseContext()};
                        attachParam.setResult(null);
                        
                        for (Object cb : legacyCallbacks) {
                            if (cb instanceof XC_MethodHook) {
                                ((XC_MethodHook) cb).callAfterHookedMethod(attachParam);
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }).start();
    }
}
