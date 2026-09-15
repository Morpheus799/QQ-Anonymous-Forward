package dev.anonforward;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import dev.anonforward.runtime.HookBridge;
import dev.anonforward.runtime.RuntimeEnvironment;

final class StandaloneRuntimeBackend implements RuntimeEnvironment.Backend {
    private final ClassLoader moduleClassLoader;
    private final ClassLoader hostClassLoader;
    private final HookBridge hookBridge;
    private final String processName;

    StandaloneRuntimeBackend(
            ClassLoader moduleClassLoader,
            ClassLoader hostClassLoader,
            HookBridge hookBridge,
            String processName
    ) {
        this.moduleClassLoader = moduleClassLoader;
        this.hostClassLoader = hostClassLoader;
        this.hookBridge = hookBridge;
        this.processName = processName;
    }

    @Override public ClassLoader moduleClassLoader() { return moduleClassLoader; }
    @Override public ClassLoader hostClassLoader() { return hostClassLoader; }
    @Override public HookBridge hookBridge() { return hookBridge; }
    @Override public String processName() { return processName; }

    @Override
    public Object appRuntime() throws ReflectiveOperationException {
        Class<?> mobileQQ = hostClassLoader.loadClass("mqq.app.MobileQQ");
        Field singleton = mobileQQ.getDeclaredField("sMobileQQ");
        singleton.setAccessible(true);
        Object application = singleton.get(null);
        if (application == null) return null;
        for (Class<?> current = mobileQQ; current != null; current = current.getSuperclass()) {
            try {
                Field runtime = current.getDeclaredField("mAppRuntime");
                runtime.setAccessible(true);
                return runtime.get(application);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    @Override
    public List<Class<?>> findAdapterImplementations(String interfaceName) {
        // The standalone entry hooks engine/session initialization before adapters are registered.
        return Collections.emptyList();
    }

    @Override
    public void logToFramework(String message, Throwable error) {
        XposedBridge.log(message);
        if (error != null) XposedBridge.log(error);
    }
}
