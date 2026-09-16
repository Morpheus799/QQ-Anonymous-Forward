package dev.anonforward;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

final class CachedHistoryCallback {
    interface Transform { Object[] apply(Object[] args) throws Exception; }

    private CachedHistoryCallback() {}

    static Object wrap(Class<?> type, Object original, Transform transform) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "AnonForwardHistoryCallback";
                    default -> null;
                };
            }
            Object[] forwarded = args;
            if (method.getName().equals("onResult") && args != null
                    && !(args.length > 0 && args[0] instanceof Number code && code.intValue() != 0)) {
                try { forwarded = transform.apply(args); }
                catch (Exception error) {
                    AFLog.e("Protected history projection failed", error);
                    forwarded = args.clone();
                    if (forwarded.length > 0 && forwarded[0] instanceof Number) forwarded[0] = -1;
                    if (forwarded.length > 1 && forwarded[1] instanceof String) forwarded[1] = "匿名预览读取失败";
                    for (int i = 0; i < forwarded.length; i++) {
                        Object value = forwarded[i];
                        if (value instanceof List<?>) forwarded[i] = new ArrayList<>();
                        else if (value != null && value.getClass().getSimpleName().equals("MsgsRsp")) {
                            Object response = CachedMessageSanitizer.copyBean(value);
                            CachedMessageSanitizer.setProperty(response, "result", -1);
                            CachedMessageSanitizer.setProperty(response, "errMsg", "匿名预览读取失败");
                            CachedMessageSanitizer.setProperty(response, "msgList", new ArrayList<>());
                            forwarded[i] = response;
                        } else if (value != null && value.getClass().getSimpleName().equals("MsgRecord")) forwarded[i] = null;
                    }
                }
            }
            try { method.setAccessible(true); return method.invoke(original, forwarded); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }
}
