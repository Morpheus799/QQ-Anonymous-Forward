package dev.anonforward.runtime;

import java.util.Collections;
import java.util.List;

public final class RuntimeEnvironment {
    public interface Backend {
        ClassLoader moduleClassLoader();
        ClassLoader hostClassLoader();
        HookBridge hookBridge();
        String processName();
        Object appRuntime() throws ReflectiveOperationException;
        List<Class<?>> findAdapterImplementations(String interfaceName) throws Exception;
        void logToFramework(String message, Throwable error);
    }

    private static Backend backend;

    private RuntimeEnvironment() {}

    public static synchronized void configure(Backend value) {
        if (backend != null) throw new IllegalStateException("Runtime backend is already configured");
        backend = value;
    }

    public static ClassLoader moduleClassLoader() { return require().moduleClassLoader(); }
    public static ClassLoader hostClassLoader() { return require().hostClassLoader(); }
    public static HookBridge hookBridge() { return require().hookBridge(); }
    public static String processName() { return require().processName(); }

    public static Object appRuntime() throws ReflectiveOperationException {
        return require().appRuntime();
    }

    public static List<Class<?>> findAdapterImplementations(String interfaceName) throws Exception {
        List<Class<?>> result = require().findAdapterImplementations(interfaceName);
        return result == null ? Collections.emptyList() : result;
    }

    public static void logToFramework(String message, Throwable error) {
        Backend value = backend;
        if (value != null) value.logToFramework(message, error);
    }

    private static Backend require() {
        Backend value = backend;
        if (value == null) throw new IllegalStateException("Runtime backend is not configured");
        return value;
    }
}
