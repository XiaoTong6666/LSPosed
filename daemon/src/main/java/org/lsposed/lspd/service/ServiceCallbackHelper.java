package org.lsposed.lspd.service;

import android.os.IBinder;
import android.util.Log;

import static org.lsposed.lspd.service.ServiceManager.TAG;
import static org.lsposed.lspd.service.ServiceManager.getSystemServiceManager;
import static org.lsposed.lspd.service.LSPSystemServerService.PROXY_SERVICE_NAME;

public class ServiceCallbackHelper {
    static void register(LSPSystemServerService service) {
        try {
            var serviceCallback = new android.os.IServiceCallback.Stub() {
                @Override
                public void onRegistration(String name, IBinder binder) {
                    Log.d(TAG, "LSPSystemServerService::LSPSystemServerService onRegistration: " + name + " "
                            + binder);
                    if (name.equals(PROXY_SERVICE_NAME) && binder != null
                            && binder != service) {
                        Log.d(TAG, "Register " + name + " " + binder);
                        service.onServiceRegistered(binder);
                    }
                }

                @Override
                public IBinder asBinder() {
                    return this;
                }
            };
            getSystemServiceManager().registerForNotifications(PROXY_SERVICE_NAME, serviceCallback);
        } catch (Throwable e) {
            Log.e(TAG, "register service callback: ", e);
        }
    }
}
