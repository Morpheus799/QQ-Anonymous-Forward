package dev.anonforward;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;

import dev.anonforward.runtime.HookBridge;
import dev.anonforward.runtime.RuntimeEnvironment;

/**
 * Observes QQNT's native multi-forward entry points. Mobile QQ asks the kernel
 * to package selected message IDs through these methods, so sendMsg alone does
 * not see the source records.
 */
final class ForwardSendHook {
    private static boolean installed;
    private static int hookCount;

    private ForwardSendHook() {}

    static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        try {
            HookBridge bridge = RuntimeEnvironment.hookBridge();
            ClassLoader host = RuntimeEnvironment.hostClassLoader();
            int count = 0;
            for (String className : new String[]{
                    "com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService$CppProxy",
                    "com.tencent.qqnt.kernelpublic.nativeinterface.IKernelMsgService$CppProxy"
            }) {
                try {
                    Class<?> proxy = host.loadClass(className);
                    for (Method method : proxy.getDeclaredMethods()) {
                        String name = method.getName().toLowerCase(Locale.ROOT);
                        if (!name.contains("forward") || Modifier.isAbstract(method.getModifiers())) continue;
                        method.setAccessible(true);
                        bridge.hookMethod(method, new ForwardCallback(context), HookBridge.PRIORITY_HIGHEST - 30);
                        count++;
                        AFLog.i("Hooked QQNT forward method " + method);
                    }
                } catch (ClassNotFoundException ignored) {
                }
            }
            hookCount = count;
            if (count == 0) AFLog.w("No QQNT forward method found");
            else AFLog.i("Hooked QQNT forward methods=" + count);
        } catch (Throwable error) {
            AFLog.e("Forward hook installation failed", error);
        }
    }

    static int hookCount() {
        return hookCount;
    }

    private static final class ForwardCallback implements HookBridge.MemberHookCallback {
        private final Context context;

        ForwardCallback(Context context) {
            this.context = context;
        }

        @Override
        public void beforeHookedMember(HookBridge.MemberHookParam param) {
            if (!AnonState.isArmed()) return;
            if (param.getMember().getName().toLowerCase(Locale.ROOT).contains("multiforward")) {
                AnonState.markForwardStarted();
            }
            int infoCount = 0;
            StringBuilder shape = new StringBuilder();
            Object[] arguments = param.getArgs();
            for (int index = 0; index < arguments.length; index++) {
                Object argument = arguments[index];
                if (index > 0) shape.append(',');
                shape.append(index).append('=').append(describe(argument));
                if (argument instanceof List<?> list) infoCount += sanitizeMultiMsgInfos(list);
            }
            String methodName = param.getMember().getDeclaringClass().getName()
                    + "." + param.getMember().getName();
            AFLog.i("Intercepted QQNT forward entry " + methodName
                    + ", aliases=" + infoCount + ", args=[" + shape + "]");
            if (AnonState.markForwardEntrySeen()) {
                int finalInfoCount = infoCount;
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(
                        context,
                        "已进入匿名合并转发\n昵称字段 " + finalInfoCount + " 个",
                        Toast.LENGTH_LONG
                ).show());
            }
        }

        @Override
        public void afterHookedMember(HookBridge.MemberHookParam param) {
        }
    }

    private static int sanitizeMultiMsgInfos(List<?> list) {
        int changed = 0;
        for (Object item : list) {
            if (item == null || !item.getClass().getSimpleName().equals("MultiMsgInfo")) continue;
            try {
                Object value;
                try {
                    value = Reflect.call(item, "getSenderShowName");
                } catch (ReflectiveOperationException ignored) {
                    value = Reflect.getField(item, "senderShowName");
                }
                String alias = AnonState.aliasForName(value == null ? null : value.toString());
                try {
                    Reflect.call(item, "setSenderShowName", alias);
                } catch (ReflectiveOperationException ignored) {
                    Reflect.setField(item, "senderShowName", alias);
                }
                changed++;
            } catch (Throwable error) {
                AFLog.e("Failed to sanitize MultiMsgInfo", error);
            }
        }
        return changed;
    }

    private static String describe(Object value) {
        if (value == null) return "null";
        if (value instanceof List<?> list) {
            String itemType = list.isEmpty() || list.get(0) == null
                    ? "?" : list.get(0).getClass().getName();
            return value.getClass().getName() + "(size=" + list.size() + ",item=" + itemType + ")";
        }
        return value.getClass().getName();
    }
}
