package dev.anonforward;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.anonforward.runtime.HookBridge;
import dev.anonforward.runtime.RuntimeEnvironment;

/**
 * Hooks the QQNT native-to-Java SSO adapter. The native kernel builds long
 * message packets internally and hands the command plus protobuf bytes to
 * IDependsAdapter/IGlobalAdapter immediately before network transmission.
 */
final class NativeSsoHook {
    private static final String DEPENDS_ADAPTER =
            "com.tencent.qqnt.kernel.nativeinterface.IDependsAdapter";
    private static final String GLOBAL_ADAPTER =
            "com.tencent.qqnt.kernel.nativeinterface.IGlobalAdapter";
    private static final Set<Method> hookedMethods =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<String> observedCommands = new LinkedHashSet<>();
    private static boolean installed;
    private static boolean dexScanStarted;
    private static int hookCount;
    private static Context hostContext;

    private NativeSsoHook() {}

    static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        hostContext = context;
        try {
            hookSessionInitialization();
            hookEngineInitialization();
            rescanAsync();
        } catch (Throwable error) {
            AFLog.e("Native SSO hook installation failed", error);
        }
    }

    static int hookCount() {
        return hookCount;
    }

    static void rescanAsync() {
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> new Thread(NativeSsoHook::scanRuntime, "AnonForward-SsoScan").start(),
                800
        );
    }

    private static void hookSessionInitialization() throws Exception {
        ClassLoader host = RuntimeEnvironment.hostClassLoader();
        Class<?> dependsAdapter = host.loadClass(DEPENDS_ADAPTER);
        HookBridge bridge = RuntimeEnvironment.hookBridge();
        for (String className : new String[]{
                "com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession$CppProxy",
                "com.tencent.qqnt.kernelpublic.nativeinterface.IQQNTWrapperSession$CppProxy"
        }) {
            try {
                Class<?> session = host.loadClass(className);
                for (Method method : session.getDeclaredMethods()) {
                    if (!method.getName().equals("init") || Modifier.isAbstract(method.getModifiers())) continue;
                    boolean acceptsAdapter = false;
                    for (Class<?> parameter : method.getParameterTypes()) {
                        if (dependsAdapter.isAssignableFrom(parameter)) {
                            acceptsAdapter = true;
                            break;
                        }
                    }
                    if (!acceptsAdapter) continue;
                    method.setAccessible(true);
                    bridge.hookMethod(method, new HookBridge.MemberHookCallback() {
                        @Override
                        public void beforeHookedMember(HookBridge.MemberHookParam param) {
                            for (Object argument : param.getArgs()) hookIfAdapter(argument);
                        }

                        @Override
                        public void afterHookedMember(HookBridge.MemberHookParam param) {
                        }
                    }, HookBridge.PRIORITY_HIGHEST - 40);
                    AFLog.i("Hooked QQNT session init " + method);
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
    }

    private static void scanRuntime() {
        try {
            Object appRuntime = RuntimeEnvironment.appRuntime();
            if (appRuntime == null) {
                AFLog.w("Native SSO scan: AppRuntime is not ready");
                return;
            }
            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            scanObject(appRuntime, 3, visited);

            ClassLoader host = RuntimeEnvironment.hostClassLoader();
            Class<?> kernelInterface = host.loadClass("com.tencent.qqnt.kernel.api.IKernelService");
            Object kernelService = invokeRuntimeService(appRuntime, kernelInterface);
            if (kernelService != null) {
                scanObject(kernelService, 7, visited);
                try {
                    Object msgService = Reflect.call(kernelService, "getMsgService");
                    scanObject(msgService, 6, visited);
                } catch (ReflectiveOperationException ignored) {
                }
            }
            scanAdapterImplementationsWithDexKit();
            AFLog.i("Native SSO adapter scan finished, hooks=" + hookCount);
        } catch (Throwable error) {
            AFLog.e("Native SSO adapter scan failed", error);
        }
    }

    private static void hookEngineInitialization() throws Exception {
        ClassLoader host = RuntimeEnvironment.hostClassLoader();
        Class<?> globalAdapter = host.loadClass(GLOBAL_ADAPTER);
        HookBridge bridge = RuntimeEnvironment.hookBridge();
        for (String className : new String[]{
                "com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperEngine$CppProxy",
                "com.tencent.qqnt.kernelpublic.nativeinterface.IQQNTWrapperEngine$CppProxy"
        }) {
            try {
                Class<?> engine = host.loadClass(className);
                for (Method method : engine.getDeclaredMethods()) {
                    if (!method.getName().startsWith("init") || Modifier.isAbstract(method.getModifiers())) continue;
                    boolean acceptsAdapter = false;
                    for (Class<?> parameter : method.getParameterTypes()) {
                        if (globalAdapter.isAssignableFrom(parameter)) {
                            acceptsAdapter = true;
                            break;
                        }
                    }
                    if (!acceptsAdapter) continue;
                    method.setAccessible(true);
                    bridge.hookMethod(method, new HookBridge.MemberHookCallback() {
                        @Override
                        public void beforeHookedMember(HookBridge.MemberHookParam param) {
                            for (Object argument : param.getArgs()) hookIfAdapter(argument);
                        }

                        @Override
                        public void afterHookedMember(HookBridge.MemberHookParam param) {
                        }
                    }, HookBridge.PRIORITY_HIGHEST - 40);
                    AFLog.i("Hooked QQNT engine init " + method);
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
    }

    /** The selected runtime may provide deobfuscated adapter implementations. */
    private static synchronized void scanAdapterImplementationsWithDexKit() {
        if (dexScanStarted || !isMainProcess()) return;
        dexScanStarted = true;
        try {
            for (String interfaceName : new String[]{DEPENDS_ADAPTER, GLOBAL_ADAPTER}) {
                List<Class<?>> implementations = RuntimeEnvironment.findAdapterImplementations(interfaceName);
                int found = 0;
                for (Class<?> implementation : implementations) {
                    if (!implementation.isInterface()
                            && !Modifier.isAbstract(implementation.getModifiers())) {
                        hookAdapterClass(implementation);
                        found++;
                    }
                }
                AFLog.i("Runtime adapter search " + interfaceName + " found=" + found);
            }
        } catch (Throwable error) {
            AFLog.e("Runtime native packet adapter search failed", error);
        }
    }

    private static boolean isMainProcess() {
        String process = RuntimeEnvironment.processName();
        return process.equals("com.tencent.mobileqq") || process.equals("com.tencent.tim");
    }

    private static Object invokeRuntimeService(Object appRuntime, Class<?> kernelInterface)
            throws ReflectiveOperationException {
        for (Class<?> current = appRuntime.getClass(); current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals("getRuntimeService") || method.getParameterCount() != 2) continue;
                method.setAccessible(true);
                try {
                    return method.invoke(appRuntime, kernelInterface, "");
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return null;
    }

    private static void scanObject(Object value, int depth, Set<Object> visited) {
        if (value == null || depth < 0 || visited.size() > 4000 || !visited.add(value)) return;
        hookIfAdapter(value);
        if (depth == 0) return;

        if (value instanceof Collection<?> collection) {
            int count = 0;
            for (Object item : collection) {
                if (count++ >= 100) break;
                scanObject(item, depth - 1, visited);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            int count = 0;
            for (Object item : map.values()) {
                if (count++ >= 100) break;
                scanObject(item, depth - 1, visited);
            }
            return;
        }
        Class<?> type = value.getClass();
        String name = type.getName();
        if (type.isArray() || type.isPrimitive() || type.isEnum()
                || name.startsWith("java.lang.") || name.startsWith("android.")) return;

        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    scanObject(field.get(value), depth - 1, visited);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void hookIfAdapter(Object candidate) {
        if (candidate == null) return;
        try {
            ClassLoader host = RuntimeEnvironment.hostClassLoader();
            Class<?> depends = host.loadClass(DEPENDS_ADAPTER);
            Class<?> global = host.loadClass(GLOBAL_ADAPTER);
            if (!depends.isInstance(candidate) && !global.isInstance(candidate)) return;
            hookAdapterClass(candidate.getClass());
        } catch (Throwable error) {
            AFLog.e("Failed to inspect native SSO adapter", error);
        }
    }

    private static synchronized void hookAdapterClass(Class<?> adapterClass) {
        HookBridge bridge = RuntimeEnvironment.hookBridge();
        for (Class<?> current = adapterClass; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                int commandIndex;
                int payloadIndex;
                String channel;
                if (method.getName().equals("onSendSSORequest")
                        && method.getParameterCount() >= 3
                        && method.getParameterTypes()[1].equals(String.class)
                        && method.getParameterTypes()[2].equals(byte[].class)) {
                    commandIndex = 1;
                    payloadIndex = 2;
                    channel = "SSO";
                } else if (method.getName().equals("onSendOidbRequest")
                        && method.getParameterCount() >= 4
                        && method.getParameterTypes()[3].equals(byte[].class)) {
                    commandIndex = -1;
                    payloadIndex = 3;
                    channel = "OIDB";
                } else {
                    continue;
                }
                if (Modifier.isAbstract(method.getModifiers()) || hookedMethods.contains(method)) continue;
                method.setAccessible(true);
                bridge.hookMethod(method, new NativePacketCallback(commandIndex, payloadIndex, channel),
                        HookBridge.PRIORITY_HIGHEST - 5);
                hookedMethods.add(method);
                hookCount++;
                AFLog.i("Hooked native packet adapter " + method);
            }
        }
    }

    private static final class NativePacketCallback implements HookBridge.MemberHookCallback {
        private final int commandIndex;
        private final int payloadIndex;
        private final String channel;

        NativePacketCallback(int commandIndex, int payloadIndex, String channel) {
            this.commandIndex = commandIndex;
            this.payloadIndex = payloadIndex;
            this.channel = channel;
        }

        @Override
        public void beforeHookedMember(HookBridge.MemberHookParam param) {
            if (!AnonState.isArmed()) return;
            Object[] arguments = param.getArgs();
            String command = commandIndex >= 0
                    ? String.valueOf(arguments[commandIndex])
                    : "OidbSvc." + arguments[1] + "_" + arguments[2];
            byte[] original = (byte[]) arguments[payloadIndex];
            logObservedCommand(command, original);
            if (command.contains("SsoSendLongMsg")) {
                try {
                    byte[] sanitized = MultiMsgSanitizer.sanitizeRequest(original);
                    arguments[payloadIndex] = sanitized;
                    AFLog.i("Replaced native outgoing " + command + " bytes "
                            + original.length + " -> " + sanitized.length);
                    showToast("合并转发正文已匿名化");
                    scheduleOperationCleanup();
                } catch (Throwable error) {
                    AnonState.markPacketFailed();
                    AFLog.e("Failed to sanitize native outgoing long message", error);
                    showToast("匿名化失败，已阻止长消息上传");
                    param.setResult(null);
                }
                return;
            }
            try {
                OuterPreviewSanitizer.Result preview = OuterPreviewSanitizer.sanitizePacket(original);
                if (preview.replacements == 0) return;
                arguments[payloadIndex] = preview.data;
                AFLog.i("Replaced outer multimsg preview in " + channel + " " + command
                        + ", matches=" + preview.replacements + ", bytes="
                        + original.length + " -> " + preview.data.length);
                showToast("合并转发外层预览已匿名化");
                AnonState.disarm();
            } catch (Throwable error) {
                AnonState.markPacketFailed();
                AFLog.e("Failed to sanitize outer multimsg preview", error);
                showToast("外层预览匿名化失败");
                param.setResult(null);
            }
        }

        @Override
        public void afterHookedMember(HookBridge.MemberHookParam param) {
        }
    }

    private static synchronized void logObservedCommand(String command, byte[] data) {
        if (observedCommands.size() >= 80 || !observedCommands.add(command)) return;
        AFLog.i("Observed native SSO command=" + command + ", bytes="
                + (data == null ? 0 : data.length));
    }

    private static void showToast(String text) {
        Context context = hostContext;
        if (context == null) return;
        new Handler(Looper.getMainLooper()).post(
                () -> Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        );
    }

    private static void scheduleOperationCleanup() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (AnonState.isArmed() && AnonState.hasForwardStarted()) {
                AFLog.w("Anonymous forwarding cleanup after completed upload");
                AnonState.disarm();
            }
        }, 60_000);
    }
}
