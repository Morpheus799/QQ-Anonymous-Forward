package dev.anonforward;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

final class OuterPreviewSanitizer {
    static final class Result {
        final byte[] data;
        final int replacements;

        Result(byte[] data, int replacements) {
            this.data = data;
            this.replacements = replacements;
        }
    }

    private static final int MAX_PACKET_BYTES = 2 * 1024 * 1024;
    private static final int MAX_VISITED_OBJECTS = 4000;

    private OuterPreviewSanitizer() {}

    static Result sanitizePacket(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > MAX_PACKET_BYTES) return new Result(raw, 0);
        return sanitizeBlob(raw, 10);
    }

    static int sanitizeObjectGraph(Object root) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return sanitizeObject(root, 7, visited);
    }

    private static Result sanitizeBlob(byte[] data, int depth) {
        if (data == null || data.length == 0 || data.length > MAX_PACKET_BYTES || depth < 0) {
            return new Result(data, 0);
        }

        Result text = sanitizeText(data);
        if (text.replacements > 0) return text;

        if (data.length > 3 && data[0] == 1) {
            try {
                byte[] inflated = inflate(data, 1);
                Result inner = sanitizeBlob(inflated, depth - 1);
                if (inner.replacements > 0) {
                    byte[] compressed = deflate(inner.data);
                    byte[] rebuilt = new byte[compressed.length + 1];
                    rebuilt[0] = 1;
                    System.arraycopy(compressed, 0, rebuilt, 1, compressed.length);
                    return new Result(rebuilt, inner.replacements);
                }
            } catch (Exception ignored) {
            }
        }

        try {
            ProtoMessage message = ProtoMessage.parse(data);
            int replacements = 0;
            for (ProtoMessage.Field field : message.fields()) {
                if (field.wireType != 2 || field.bytes == null || field.bytes.length == 0) continue;
                Result child = sanitizeBlob(field.bytes, depth - 1);
                if (child.replacements > 0) {
                    field.bytes = child.data;
                    replacements += child.replacements;
                }
            }
            if (replacements > 0) return new Result(message.toByteArray(), replacements);
        } catch (Exception ignored) {
        }
        return new Result(data, 0);
    }

    private static Result sanitizeText(byte[] data) {
        String raw = new String(data, StandardCharsets.UTF_8);
        String trimmed = raw.trim();
        try {
            if (trimmed.startsWith("{") && trimmed.contains("com.tencent.multimsg")) {
                String sanitized = ArkPreviewSanitizer.sanitizeArk(trimmed);
                if (!sanitized.equals(trimmed)) {
                    return new Result(sanitized.getBytes(StandardCharsets.UTF_8), 1);
                }
            }
        } catch (Exception ignored) {
        }
        if (trimmed.startsWith("<")
                && (trimmed.contains("serviceID=\"35\"")
                || trimmed.contains("serviceID='35'")
                || trimmed.contains("com.tencent.multimsg"))) {
            String sanitized = ArkPreviewSanitizer.sanitizeXml(trimmed);
            if (!sanitized.equals(trimmed)) {
                return new Result(sanitized.getBytes(StandardCharsets.UTF_8), 1);
            }
        }
        return new Result(data, 0);
    }

    private static int sanitizeObject(Object value, int depth, Set<Object> visited) {
        if (value == null || depth < 0 || visited.size() >= MAX_VISITED_OBJECTS || !visited.add(value)) return 0;
        if (value instanceof byte[] bytes) return 0;
        if (value instanceof List<?> list) return sanitizeList(list, depth, visited);
        if (value instanceof Collection<?> collection) {
            int changed = 0;
            for (Object item : collection) changed += sanitizeObject(item, depth - 1, visited);
            return changed;
        }
        if (value instanceof Map<?, ?> map) {
            int changed = 0;
            for (Object item : map.values()) changed += sanitizeObject(item, depth - 1, visited);
            return changed;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int changed = 0;
            for (int index = 0; index < Array.getLength(value); index++) {
                changed += sanitizeObject(Array.get(value, index), depth - 1, visited);
            }
            return changed;
        }
        String className = type.getName();
        if (type.isPrimitive() || type.isEnum() || className.startsWith("java.lang.")
                || className.startsWith("android.") || className.startsWith("kotlin.")) return 0;

        int changed = 0;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object fieldValue = field.get(value);
                    if (fieldValue instanceof String string) {
                        String replacement = sanitizeStringValue(string);
                        if (!replacement.equals(string)) {
                            field.set(value, replacement);
                            changed++;
                        }
                    } else if (fieldValue instanceof byte[] bytes) {
                        Result result = sanitizePacket(bytes);
                        if (result.replacements > 0) {
                            field.set(value, result.data);
                            changed += result.replacements;
                        }
                    } else {
                        changed += sanitizeObject(fieldValue, depth - 1, visited);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return changed;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int sanitizeList(List<?> list, int depth, Set<Object> visited) {
        int changed = 0;
        try {
            ListIterator iterator = list.listIterator();
            while (iterator.hasNext()) {
                Object item = iterator.next();
                if (item instanceof String string) {
                    String replacement = sanitizeStringValue(string);
                    if (!replacement.equals(string)) {
                        iterator.set(replacement);
                        changed++;
                    }
                } else if (item instanceof byte[] bytes) {
                    Result result = sanitizePacket(bytes);
                    if (result.replacements > 0) {
                        iterator.set(result.data);
                        changed += result.replacements;
                    }
                } else {
                    changed += sanitizeObject(item, depth - 1, visited);
                }
            }
        } catch (Throwable ignored) {
            for (Object item : list) changed += sanitizeObject(item, depth - 1, visited);
        }
        return changed;
    }

    private static String sanitizeStringValue(String value) {
        String trimmed = value.trim();
        try {
            if (trimmed.startsWith("{") && trimmed.contains("com.tencent.multimsg")) {
                return ArkPreviewSanitizer.sanitizeArk(trimmed);
            }
        } catch (Exception ignored) {
        }
        if (trimmed.startsWith("<")
                && (trimmed.contains("serviceID=\"35\"")
                || trimmed.contains("serviceID='35'")
                || trimmed.contains("com.tencent.multimsg"))) {
            return ArkPreviewSanitizer.sanitizeXml(trimmed);
        }
        String alias = AnonState.aliasForKnownName(value);
        if (alias != null) return alias;
        return AnonState.replaceKnownPreviewPrefix(value);
    }

    private static byte[] inflate(byte[] data, int offset) throws Exception {
        try (InflaterInputStream input = new InflaterInputStream(
                new ByteArrayInputStream(data, offset, data.length - offset))) {
            return readAll(input);
        }
    }

    private static byte[] deflate(byte[] data) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(output)) {
            deflater.write(data);
        }
        return output.toByteArray();
    }

    private static byte[] readAll(java.io.InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
