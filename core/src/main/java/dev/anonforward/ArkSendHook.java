package dev.anonforward;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.List;

import dev.anonforward.runtime.HookBridge;
import dev.anonforward.runtime.RuntimeEnvironment;

final class ArkSendHook {
    private static boolean installed;
    private static int hookCount;

    private ArkSendHook() {}

    static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            HookBridge bridge = RuntimeEnvironment.hookBridge();
            int count = 0;
            for (String className : new String[]{
                    "com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService$CppProxy",
                    "com.tencent.qqnt.kernelpublic.nativeinterface.IKernelMsgService$CppProxy"
            }) {
                try {
                    Class<?> proxy = RuntimeEnvironment.hostClassLoader().loadClass(className);
                    for (Method method : proxy.getDeclaredMethods()) {
                        if (!method.getName().equals("sendMsg")) continue;
                        method.setAccessible(true);
                        bridge.hookMethod(method, new SendCallback(), HookBridge.PRIORITY_HIGHEST - 10);
                        count++;
                    }
                } catch (ClassNotFoundException ignored) {
                }
            }
            hookCount = count;
            AFLog.i("Hooked IKernelMsgService$CppProxy.sendMsg overloads=" + count);
        } catch (Throwable error) {
            AFLog.e("Ark send hook installation failed", error);
        }
    }

    static int hookCount() {
        return hookCount;
    }

    private static final class SendCallback implements HookBridge.MemberHookCallback {
        @Override
        public void beforeHookedMember(HookBridge.MemberHookParam param) {
            if (!AnonState.isArmed()) return;
            boolean changed = false;
            for (Object argument : param.getArgs()) {
                if (!(argument instanceof List<?> elements)) continue;
                for (Object element : elements) {
                    if (element == null) continue;
                    changed |= sanitizeArkElement(element);
                    changed |= sanitizeXmlElement(element, "getMultiForwardMsgElement");
                    changed |= sanitizeXmlElement(element, "getStructMsgElement");
                }
            }
            if (changed) {
                if (!AnonState.hasSanitizedPacket()) {
                    AFLog.w("Blocked multimsg card because no SsoSendLongMsg packet was sanitized");
                    param.setResult(null);
                    AnonState.disarm();
                    return;
                }
                param.setExtra(Boolean.TRUE);
            }
        }

        @Override
        public void afterHookedMember(HookBridge.MemberHookParam param) {
            if (Boolean.TRUE.equals(param.getExtra())) AnonState.disarm();
        }

        private static boolean sanitizeArkElement(Object element) {
            try {
                Object ark = Reflect.call(element, "getArkElement");
                if (ark == null) return false;
                String raw = String.valueOf(Reflect.call(ark, "getBytesData"));
                JSONObject object = new JSONObject(raw);
                if (!"com.tencent.multimsg".equals(object.optString("app"))) return false;
                String sanitized = ArkPreviewSanitizer.sanitizeArk(raw);
                try {
                    Reflect.call(ark, "setBytesData", sanitized);
                } catch (ReflectiveOperationException ignored) {
                    Reflect.setField(ark, "bytesData", sanitized);
                }
                AFLog.i("Sanitized outgoing multimsg Ark preview");
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static boolean sanitizeXmlElement(Object element, String getter) {
            try {
                Object rich = Reflect.call(element, getter);
                if (rich == null) return false;
                String xml = String.valueOf(Reflect.call(rich, "getXmlContent"));
                if (!xml.contains("serviceID=\"35\"") && !xml.contains("serviceID='35'")) return false;
                String sanitized = ArkPreviewSanitizer.sanitizeXml(xml);
                try {
                    Reflect.call(rich, "setXmlContent", sanitized);
                } catch (ReflectiveOperationException ignored) {
                    Reflect.setField(rich, "xmlContent", sanitized);
                }
                AFLog.i("Sanitized outgoing multimsg XML preview");
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }
}
