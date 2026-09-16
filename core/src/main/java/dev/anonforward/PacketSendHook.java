package dev.anonforward;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import dev.anonforward.runtime.HookBridge;
import dev.anonforward.runtime.RuntimeEnvironment;

final class PacketSendHook {
    private static boolean installed;
    private static int hookCount;
    private static final Set<String> observedCommands = new LinkedHashSet<>();

    private PacketSendHook() {}

    static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            ClassLoader host = RuntimeEnvironment.hostClassLoader();
            Class<?> toServiceMsg = host.loadClass("com.tencent.qphone.base.remote.ToServiceMsg");
            HookBridge bridge = RuntimeEnvironment.hookBridge();
            Set<Method> candidates = new HashSet<>();
            for (String className : new String[]{
                    "mqq.app.AppRuntime",
                    "com.tencent.common.app.AppInterface",
                    "com.tencent.mobileqq.app.QQAppInterface"
            }) {
                try {
                    for (Class<?> type = host.loadClass(className); type != null; type = type.getSuperclass()) {
                        for (Method method : type.getDeclaredMethods()) {
                            if (Modifier.isAbstract(method.getModifiers()) || method.getParameterCount() == 0) continue;
                            if (!method.getName().equals("sendToService") && method.getParameterCount() != 1) continue;
                            Class<?>[] parameters = method.getParameterTypes();
                            for (Class<?> parameter : parameters) {
                                if (toServiceMsg.isAssignableFrom(parameter)) {
                                    method.setAccessible(true);
                                    candidates.add(method);
                                    break;
                                }
                            }
                        }
                    }
                } catch (ClassNotFoundException ignored) {
                }
            }
            for (Method method : candidates) {
                bridge.hookMethod(method, new PacketCallback(toServiceMsg), HookBridge.PRIORITY_HIGHEST - 20);
                hookCount++;
                AFLog.i("Hooked packet method " + method);
            }
            if (candidates.isEmpty()) AFLog.w("No ToServiceMsg send method found");
        } catch (Throwable error) {
            AFLog.e("Packet hook installation failed", error);
        }
    }

    static int hookCount() {
        return hookCount;
    }

    private static final class PacketCallback implements HookBridge.MemberHookCallback {
        private final Class<?> toServiceMsg;

        PacketCallback(Class<?> toServiceMsg) {
            this.toServiceMsg = toServiceMsg;
        }

        @Override
        public void beforeHookedMember(HookBridge.MemberHookParam param) {
            if (!AnonState.isArmed()) return;
            for (Object argument : param.getArgs()) {
                if (!toServiceMsg.isInstance(argument)) continue;
                try {
                    String command = String.valueOf(Reflect.call(argument, "getServiceCmd"));
                    logObservedCommand(command, argument);
                    if (!command.contains("SsoSendLongMsg")) return;
                    byte[] original = (byte[]) Reflect.call(argument, "getWupBuffer");
                    byte[] sanitized = MultiMsgSanitizer.sanitizeRequest(original);
                    replaceBuffer(argument, sanitized);
                    AFLog.i("Replaced outgoing " + command + " bytes " + original.length + " -> " + sanitized.length);
                } catch (Throwable error) {
                    AnonState.markPacketFailed();
                    AFLog.e("Failed to sanitize outgoing long message", error);
                    param.setResult(null);
                }
                return;
            }
        }

        private static synchronized void logObservedCommand(String command, Object message) {
            if (observedCommands.size() >= 60 || !observedCommands.add(command)) return;
            int size = -1;
            try {
                byte[] buffer = (byte[]) Reflect.call(message, "getWupBuffer");
                size = buffer == null ? 0 : buffer.length;
            } catch (Throwable ignored) {
            }
            AFLog.i("Observed ToServiceMsg command=" + command + ", bytes=" + size);
        }

        @Override
        public void afterHookedMember(HookBridge.MemberHookParam param) {
        }

        private static void replaceBuffer(Object message, byte[] value) throws ReflectiveOperationException {
            try {
                Reflect.call(message, "putWupBuffer", value);
                return;
            } catch (ReflectiveOperationException ignored) {
            }
            try {
                Reflect.call(message, "setWupBuffer", value);
                return;
            } catch (ReflectiveOperationException ignored) {
            }
            Reflect.setField(message, "wupBuffer", value);
        }
    }
}
