package dev.anonforward;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.Map;

import dev.anonforward.runtime.RuntimeEnvironment;
import io.github.qauxv.chainloader.api.ChainLoaderAgent;

public final class AnonForwardEntry implements Runnable {
    private final String hostDataDir;

    public AnonForwardEntry(String modulePath, String hostDataDir, Map<String, Method> xblService) {
        this.hostDataDir = hostDataDir;
    }

    @Override
    public void run() {
        try {
            String process = ChainLoaderAgent.getProcessName();
            Context hostContext = hostContext();
            RuntimeEnvironment.configure(new QAuxRuntimeBackend());
            if (AnonForwardCore.start(hostContext, hostDataDir, process, "qaux-plugin")) {
                ControlReceiver.register(hostContext);
            }
        } catch (Throwable error) {
            AFLog.e("Plugin initialization failed", error);
        }
    }

    static String hookSummary() {
        return AnonForwardCore.hookSummary();
    }

    private static Context hostContext() throws ReflectiveOperationException {
        Method method = ChainLoaderAgent.class.getMethod("getHostApplication");
        return (Context) method.invoke(null);
    }
}
