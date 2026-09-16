package dev.anonforward;

import java.util.ArrayList;
import java.util.List;

/** Projects only protected records, including root cards, in ordinary history/cache results. */
final class CachedRecordProjection {
    private final AnonymousCacheIndex index;
    private final String account;
    private boolean dirty;

    CachedRecordProjection(AnonymousCacheIndex index, String account) { this.index = index; this.account = account; }

    boolean isDirty() { return dirty; }

    Object record(Object record) throws Exception {
        if (record == null || !record.getClass().getSimpleName().equals("MsgRecord") || account == null) return record;
        long id = CachedMessageSanitizer.number(record, "msgId");
        String contact = AnonymousCacheIndex.digest(CachedMessageSanitizer.number(record, "chatType") + ":"
                + CachedMessageSanitizer.string(record, "peerUid"));
        AnonymousCacheIndex.Entry root = index.root(account, contact, id);
        AnonymousCacheIndex.Entry entry = root != null ? root : index.node(account, id);
        if (entry == null) return record;
        long revision = entry.revision;
        try {
            CachedMessageSanitizer sanitizer = new CachedMessageSanitizer(entry);
            Object result = root != null ? sanitizer.outerCard(record) : sanitizer.record(record, 0);
            dirty |= entry.revision != revision;
            return result;
        } catch (Exception error) {
            AFLog.e("Blocked an unsupported protected cache record", error);
            return null;
        }
    }

    Object value(Object value) throws Exception {
        if (value == null) return null;
        if (value instanceof List<?> list) {
            ArrayList<Object> replacement = null;
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                Object projected = record(item);
                if (projected != item) {
                    if (replacement == null) replacement = new ArrayList<>(list);
                    replacement.set(i, projected);
                }
            }
            if (replacement == null) return value;
            replacement.removeIf(java.util.Objects::isNull);
            return replacement;
        }
        if (value.getClass().getSimpleName().equals("MsgsRsp")) {
            if (CachedMessageSanitizer.number(value, "result") != 0) return value;
            Object records = CachedMessageSanitizer.value(value, "msgList");
            Object projected = value(records);
            if (projected == records) return value;
            Object copy = CachedMessageSanitizer.copyBean(value);
            CachedMessageSanitizer.setProperty(copy, "msgList", projected);
            return copy;
        }
        return record(value);
    }
}
