package dev.anonforward;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Copy-on-write at the cache boundary. Source records and media objects are never edited. */
final class CachedMessageSanitizer {
    private static final Pattern MENTION = Pattern.compile("@([^\\s@，。！？、:：]+)");
    private final AnonymousCacheIndex.Entry entry;
    private final Map<Object, Object> copies = new IdentityHashMap<>();
    private final Set<Object> neutralizedStyles = Collections.newSetFromMap(new IdentityHashMap<>());
    private int recordCount;

    CachedMessageSanitizer(AnonymousCacheIndex.Entry entry) { this.entry = entry; }

    ArrayList<Object> copyRecords(List<?> records) throws Exception {
        ArrayList<Object> result = new ArrayList<>(records.size());
        for (Object record : records) result.add(record(record, 0));
        return result;
    }

    /** The card is sent by the real forwarding account. Only its embedded preview is private. */
    Object outerCard(Object source) throws Exception {
        if (source == null) return null;
        if (!source.getClass().getSimpleName().equals("MsgRecord")) throw new IllegalArgumentException("Unknown card record type");
        Object copy = shallowCopy(source);
        Object elements = value(source, "elements");
        if (elements instanceof List<?> list) {
            ArrayList<Object> result = new ArrayList<>(list.size());
            for (Object originalElement : list) {
                if (originalElement == null) { result.add(null); continue; }
                Object replacement = originalElement;
                for (String field : new String[]{"arkElement", "multiForwardMsgElement", "structMsgElement"}) {
                    Object original = value(originalElement, field);
                    if (original == null) continue;
                    String property = field.equals("arkElement") ? "bytesData" : "xmlContent";
                    String raw = string(original, property);
                    String sanitized = field.equals("arkElement") ? ArkPreviewSanitizer.sanitizeArk(raw, entry)
                            : ArkPreviewSanitizer.sanitizeXml(raw, entry);
                    if (raw.equals(sanitized)) continue;
                    if (replacement == originalElement) replacement = shallowCopy(originalElement);
                    Object rich = shallowCopy(original);
                    set(rich, property, sanitized);
                    set(replacement, field, rich);
                }
                result.add(replacement);
            }
            set(copy, "elements", result);
        }
        Object nested = value(source, "records");
        if (nested instanceof List<?> list && !list.isEmpty()) {
            ArrayList<Object> records = new ArrayList<>();
            for (Object child : list) records.add(record(child, 1));
            set(copy, "records", records);
        }
        return copy;
    }

    static Object copyBean(Object source) throws Exception {
        java.lang.reflect.Constructor<?> ctor = source.getClass().getDeclaredConstructor();
        ctor.setAccessible(true);
        Object copy = ctor.newInstance();
        for (Class<?> type = source.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                field.set(copy, field.get(source));
            }
        }
        return copy;
    }

    static void setProperty(Object object, String name, Object value) throws Exception { setRequired(object, name, value); }

    Object record(Object source, int depth) throws Exception {
        if (source == null) return null;
        if (copies.containsKey(source)) return copies.get(source);
        if (depth > 16 || ++recordCount > 10000) throw new IllegalArgumentException("Nested cached records exceed limit");
        if (!source.getClass().getSimpleName().equals("MsgRecord")) throw new IllegalArgumentException("Unknown cached message type");
        Object copy = shallowCopy(source);
        long msgId = number(source, "msgId");
        entry.recordNode(msgId);
        String name = firstName(source, "sendMemberName", "sendNickName", "sendRemarkName");
        String alias = entry.aliasFor(string(source, "senderUid"), number(source, "senderUin"), name);
        setRequired(copy, "senderUin", AnonState.PLACEHOLDER_UIN);
        setRequired(copy, "senderUid", AnonState.PLACEHOLDER_UID);
        for (String field : new String[]{"sendNickName", "sendMemberName", "sendRemarkName"}) set(copy, field, alias);
        boolean group = number(source, "chatType") == 2;
        setRequired(copy, "peerUin", group ? AnonState.PLACEHOLDER_GROUP : AnonState.PLACEHOLDER_UIN);
        setRequired(copy, "peerUid", group ? Long.toString(AnonState.PLACEHOLDER_GROUP) : AnonState.PLACEHOLDER_UID);
        set(copy, "peerName", "匿名聊天记录");
        set(copy, "fromUid", 0L);
        for (String nameField : new String[]{"avatarMeta", "avatarPendant"}) {
            Field avatar = field(copy.getClass(), nameField);
            if (avatar != null) avatar.set(copy, avatar.getType() == String.class ? "" : null);
        }
        for (String field : new String[]{"personalMedal", "anonymousExtInfo"}) set(copy, field, null);
        set(copy, "avatarFlag", 0);
        sanitizeAttributes(source, copy);
        Object flags = value(source, "generalFlags");
        if (flags instanceof byte[] bytes) set(copy, "generalFlags", MessageMetadataSanitizer.localGeneralFlags(bytes));
        // Preserve the kernel-assigned virtual msgId/sequence: reply links and forward-sub-message
        // operations use those local indexes. The wire sanitizer handles protocol identities separately.
        Object nested = value(source, "records");
        if (nested instanceof List<?> list) {
            ArrayList<Object> children = new ArrayList<>();
            for (Object child : list) children.add(record(child, depth + 1));
            set(copy, "records", children);
        }
        Object elements = value(source, "elements");
        if (elements instanceof List<?> list) {
            ArrayList<Object> sanitized = new ArrayList<>();
            for (Object element : list) sanitized.add(element(element));
            set(copy, "elements", sanitized);
        }
        return copy;
    }

    private void sanitizeAttributes(Object source, Object copy) throws Exception {
        Object original = value(source, "msgAttrs");
        if (!(original instanceof Map<?, ?> attributes)) return;
        HashMap<Object, Object> sanitized = new HashMap<>();
        for (Map.Entry<?, ?> attribute : attributes.entrySet()) {
            Object data = attribute.getValue();
            if (data == null) { sanitized.put(attribute.getKey(), null); continue; }
            Object replacement = shallowCopy(data);
            // These are presentation/profile metadata trees, not media or message content.
            for (String field : new String[]{"vasMsgInfo", "vasPersonalInfo", "groupHonor", "kingHonor"}) {
                Object style = value(data, field);
                if (style != null) set(replacement, field, neutralStyle(style, 0));
            }
            Object originalUin = value(data, "uinInfoAttr");
            if (originalUin != null) {
                Object uin = shallowCopy(originalUin);
                set(uin, "uin", AnonState.PLACEHOLDER_UIN);
                set(replacement, "uinInfoAttr", uin);
            }
            sanitized.put(attribute.getKey(), replacement);
        }
        set(copy, "msgAttrs", sanitized);
    }

    private Object neutralStyle(Object source, int depth) throws Exception {
        if (depth > 12) throw new IllegalArgumentException("Style metadata nesting exceeds limit");
        Object copy = shallowCopy(source);
        if (!neutralizedStyles.add(source)) return copy;
        for (Class<?> type = source.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Class<?> valueType = field.getType();
                Object neutral;
                if (valueType == String.class) neutral = "";
                else if (valueType == boolean.class || valueType == Boolean.class) neutral = false;
                else if (valueType == int.class || valueType == Integer.class) neutral = 0;
                else if (valueType == long.class || valueType == Long.class) neutral = 0L;
                else if (valueType == short.class || valueType == Short.class) neutral = (short) 0;
                else if (valueType == byte.class || valueType == Byte.class) neutral = (byte) 0;
                else if (valueType == float.class || valueType == Float.class) neutral = 0f;
                else if (valueType == double.class || valueType == Double.class) neutral = 0d;
                else if (valueType == char.class || valueType == Character.class) neutral = '\0';
                else if (valueType.isArray()) neutral = java.lang.reflect.Array.newInstance(valueType.getComponentType(), 0);
                else if (List.class.isAssignableFrom(valueType)) neutral = new ArrayList<>();
                else if (Set.class.isAssignableFrom(valueType)) neutral = new HashSet<>();
                else if (Map.class.isAssignableFrom(valueType)) neutral = new HashMap<>();
                else if (valueType.isEnum()) neutral = valueType.getEnumConstants().length == 0 ? null : valueType.getEnumConstants()[0];
                else {
                    Object nested = field.get(source);
                    neutral = nested == null ? null : neutralStyle(nested, depth + 1);
                }
                field.set(copy, neutral);
            }
        }
        return copy;
    }

    private Object element(Object source) throws Exception {
        if (source == null) return null;
        if (copies.containsKey(source)) return copies.get(source);
        Object copy = shallowCopy(source);
        Object originalText = value(source, "textElement");
        if (originalText != null) {
            Object text = shallowCopy(originalText);
            String content = string(originalText, "content");
            boolean structuredAt = number(originalText, "atType") != 0 || number(originalText, "atUid") != 0
                    || !string(originalText, "atNtUid").isEmpty();
            if (structuredAt && content.startsWith("@")) {
                content = "@" + entry.aliasFor(string(originalText, "atNtUid"), number(originalText, "atUid"), content.substring(1).trim());
            } else content = mentions(content);
            set(text, "content", content);
            for (String field : new String[]{"atUid", "atTinyId", "atType", "atRoleId", "atChannelId", "atRoleColor"}) set(text, field, 0L);
            set(text, "atNtUid", ""); set(text, "atRoleName", "");
            if (structuredAt) set(text, "linkInfo", null);
            set(copy, "textElement", text);
        }
        Object originalReply = value(source, "replyElement");
        if (originalReply != null) {
            Object reply = shallowCopy(originalReply);
            String alias = entry.aliasFor(string(originalReply, "senderUidStr"), number(originalReply, "senderUid"), string(originalReply, "anonymousNickName"));
            set(reply, "senderUid", AnonState.PLACEHOLDER_UIN);
            set(reply, "senderUin", AnonState.PLACEHOLDER_UIN);
            set(reply, "senderUidStr", AnonState.PLACEHOLDER_UID);
            set(reply, "anonymousNickName", alias);
            set(reply, "sourceMsgText", mentions(string(originalReply, "sourceMsgText")));
            set(copy, "replyElement", reply);
        }
        for (String field : new String[]{"arkElement", "multiForwardMsgElement", "structMsgElement"}) {
            Object original = value(source, field);
            if (original == null) continue;
            Object rich = shallowCopy(original);
            if (field.equals("arkElement")) {
                set(rich, "bytesData", ArkPreviewSanitizer.sanitizeArk(string(original, "bytesData"), entry));
            } else {
                set(rich, "xmlContent", ArkPreviewSanitizer.sanitizeXml(string(original, "xmlContent"), entry));
            }
            set(copy, field, rich);
        }
        // picElement/videoElement/pttElement/faceElement and their resource bytes are shared read-only.
        return copy;
    }

    private String mentions(String content) {
        Matcher matcher = MENTION.matcher(content);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(out, Matcher.quoteReplacement("@" + entry.aliasForName(matcher.group(1))));
        matcher.appendTail(out);
        return out.toString();
    }

    private Object shallowCopy(Object source) throws Exception {
        Object existing = copies.get(source);
        if (existing != null) return existing;
        Object copy = copyBean(source);
        copies.put(source, copy);
        return copy;
    }

    static Object value(Object object, String name) throws ReflectiveOperationException {
        if (object == null) return null;
        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Method method = Reflect.findMethod(object.getClass(), getter, 0);
        if (method != null) return method.invoke(object);
        Field field = field(object.getClass(), name);
        return field == null ? null : field.get(object);
    }

    static String string(Object object, String name) throws ReflectiveOperationException {
        Object value = value(object, name);
        return value == null ? "" : String.valueOf(value);
    }

    static long number(Object object, String name) throws ReflectiveOperationException {
        Object value = value(object, name);
        if (value == null) return 0;
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(value.toString()); } catch (NumberFormatException ignored) { return 0; }
    }

    private static String firstName(Object object, String... names) throws ReflectiveOperationException {
        for (String name : names) {
            String value = string(object, name);
            if (!value.isBlank()) return value;
        }
        return "QQ用户";
    }

    private static Field field(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try { Field field = c.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static void setRequired(Object object, String name, Object value) throws Exception {
        if (!set(object, name, value)) throw new NoSuchFieldException(object.getClass().getName() + "." + name);
    }

    private static boolean set(Object object, String name, Object value) throws Exception {
        Field field = field(object.getClass(), name);
        if (field == null) return false;
        Class<?> type = field.getType();
        if (value == null && type.isPrimitive()) throw new IllegalArgumentException("Cannot clear primitive " + name);
        if (value instanceof Number n) {
            if (type == int.class || type == Integer.class) value = n.intValue();
            else if (type == long.class || type == Long.class) value = n.longValue();
            else if (type == String.class) value = value.toString();
        }
        field.set(object, value);
        return true;
    }
}
