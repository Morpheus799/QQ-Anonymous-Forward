package io.github.qauxv.chainloader.api;

import io.github.qauxv.loader.hookapi.IHookBridge;

public final class ChainLoaderAgent {
    private ChainLoaderAgent() {}
    public static ClassLoader getModuleClassLoader() { throw new UnsupportedOperationException(); }
    public static ClassLoader getHostClassLoader() { throw new UnsupportedOperationException(); }
    public static IHookBridge getHookBridge() { throw new UnsupportedOperationException(); }
    public static String getProcessName() { throw new UnsupportedOperationException(); }
}
