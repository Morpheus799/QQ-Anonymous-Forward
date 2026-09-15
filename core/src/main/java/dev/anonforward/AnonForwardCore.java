package dev.anonforward;

import android.content.Context;

public final class AnonForwardCore {
    private static final String PROCESS_OWNER_PROPERTY = "dev.anonforward.runtime.owner";
    private static boolean started;

    private AnonForwardCore() {}

    public static synchronized boolean start(
            Context hostContext,
            String hostDataDir,
            String process,
            String owner
    ) {
        if (started) return true;
        synchronized (System.getProperties()) {
            String existingOwner = System.getProperty(PROCESS_OWNER_PROPERTY);
            if (existingOwner != null && !existingOwner.equals(owner)) {
                AFLog.w("Skipping duplicate runtime " + owner + "; active owner=" + existingOwner);
                return false;
            }
            System.setProperty(PROCESS_OWNER_PROPERTY, owner);
        }
        try {
            AFLog.i("Loading " + owner + " in process " + process);
            AnonState.initialize(hostDataDir);
            NativeSsoHook.install(hostContext);
            PacketSendHook.install();
            ArkSendHook.install();
            ForwardSendHook.install(hostContext);
            if (isMainProcess(process)) MultiSelectMenuHook.install(hostContext);
            started = true;
            AFLog.i("AnonForward hooks installed: " + hookSummary());
            return true;
        } catch (Throwable error) {
            System.clearProperty(PROCESS_OWNER_PROPERTY);
            AFLog.e("Core initialization failed", error);
            return false;
        }
    }

    public static String hookSummary() {
        return "packet=" + PacketSendHook.hookCount()
                + ", nativePacket=" + NativeSsoHook.hookCount()
                + ", sendMsg=" + ArkSendHook.hookCount()
                + ", forward=" + ForwardSendHook.hookCount()
                + ", menu=" + MultiSelectMenuHook.hookCount();
    }

    private static boolean isMainProcess(String process) {
        return "com.tencent.mobileqq".equals(process) || "com.tencent.tim".equals(process);
    }
}
