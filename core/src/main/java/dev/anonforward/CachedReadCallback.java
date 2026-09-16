package dev.anonforward;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

final class CachedReadCallback {
    private CachedReadCallback() {}

    static Object wrap(Class<?> type, Object original, AnonymousCacheIndex.Entry entry, Object lock, Runnable save) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, invoked, values) -> {
            Object[] forwarded = values;
            if (invoked.getName().equals("onResult") && values != null && values.length == 3
                    && values[0] instanceof Number code && code.intValue() == 0 && values[2] instanceof List<?> records) {
                forwarded = values.clone();
                try {
                    synchronized (lock) {
                        long revision = entry.revision;
                        forwarded[2] = new CachedMessageSanitizer(entry).copyRecords(records);
                        if (entry.revision != revision) save.run();
                    }
                    AFLog.i("Protected cached MultiMsg callback, records=" + records.size());
                } catch (Exception error) {
                    AFLog.e("Cached MultiMsg anonymization failed; blocked real-name result", error);
                    forwarded[0] = -1;
                    forwarded[1] = "匿名记录读取失败";
                    forwarded[2] = new ArrayList<>();
                }
            }
            try { invoked.setAccessible(true); return invoked.invoke(original, forwarded); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }
}
