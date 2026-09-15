package dev.anonforward;

import android.app.Application;
import android.content.Context;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import dev.anonforward.runtime.RuntimeEnvironment;

public final class StandaloneXposedEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final AtomicBoolean initialized = new AtomicBoolean();
    private static String modulePath;

    @Override
    public void initZygote(StartupParam startupParam) {
        modulePath = startupParam.modulePath;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!isTarget(loadPackageParam.packageName)) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook(10000) {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!initialized.compareAndSet(false, true)) return;
                Application application = (Application) param.thisObject;
                try {
                    ClassLoader moduleLoader = StandaloneXposedEntry.class.getClassLoader();
                    if (moduleLoader == null) throw new IllegalStateException("Module class loader is null");
                    RuntimeEnvironment.configure(new StandaloneRuntimeBackend(
                            moduleLoader,
                            loadPackageParam.classLoader,
                            new XposedHookBridge(),
                            loadPackageParam.processName
                    ));
                    AnonForwardCore.start(
                            application,
                            application.getApplicationInfo().dataDir,
                            loadPackageParam.processName,
                            "lsposed-standalone"
                    );
                } catch (Throwable error) {
                    XposedBridge.log("[AnonForward/E] Standalone initialization failed: " + error);
                    XposedBridge.log(error);
                }
            }
        });
    }

    public static String getModulePath() {
        return modulePath;
    }

    private static boolean isTarget(String packageName) {
        return "com.tencent.mobileqq".equals(packageName) || "com.tencent.tim".equals(packageName);
    }
}
