package dev.anonforward;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

final class MultiMsgSanitizer {
    private static final Pattern VISIBLE_MENTION = Pattern.compile("@([^\\s@，。！？、:：]+)");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_EXPANDED_BYTES = 16 * 1024 * 1024;

    private MultiMsgSanitizer() {}

    static byte[] sanitizeRequest(byte[] raw) throws Exception {
        Framing framing = detectFraming(raw);
        ProtoMessage request = ProtoMessage.parse(framing.body);
        ProtoMessage info = parseChild(request, 2);
        if (info == null) throw new IllegalArgumentException("SsoSendLongMsg.info is missing");
        byte[] gzipPayload = info.bytes(4);
        if (gzipPayload == null) throw new IllegalArgumentException("SsoSendLongMsg payload is missing");

        ProtoMessage longMessage = ProtoMessage.parse(gunzip(gzipPayload));
        RewriteContext context = new RewriteContext();
        int bodyCount = 0;
        for (ProtoMessage.Field actionField : longMessage.all(2)) {
            ProtoMessage action = ProtoMessage.parse(actionField.bytes);
            ProtoMessage actionData = parseChild(action, 2);
            if (actionData == null) continue;
            for (ProtoMessage.Field bodyField : actionData.all(1)) {
                ProtoMessage body = ProtoMessage.parse(bodyField.bytes);
                sanitizeMessageBody(body, context, 0);
                bodyField.bytes = body.toByteArray();
                bodyCount++;
            }
            action.setBytes(2, actionData.toByteArray());
            actionField.bytes = action.toByteArray();
        }
        if (bodyCount == 0) throw new IllegalArgumentException("No message bodies were anonymized");
        info.setBytes(4, gzip(longMessage.toByteArray()));
        request.setBytes(2, info.toByteArray());
        if (bodyCount > 0) AnonState.markPacketSanitized();
        if (bodyCount > 0) ForwardCacheHook.onSanitizedUpload();
        AFLog.i("Sanitized SsoSendLongMsg bodies=" + bodyCount);
        return framing.wrap(request.toByteArray());
    }

    private static SyntheticIds sanitizeMessageBody(ProtoMessage body, RewriteContext context, int depth) throws Exception {
        if (depth > 16) throw new IllegalArgumentException("Reply nesting exceeds limit");
        ProtoMessage responseHead = parseChild(body, 1);
        ProtoMessage contentHead = parseChild(body, 2);
        SyntheticIds ids = context.identity(responseHead, contentHead);
        String alias = null;
        if (responseHead != null) {
            String uid = responseHead.string(2);
            long uin = responseHead.varint(1, 0);
            ProtoMessage group = parseChild(responseHead, 8);
            ProtoMessage forward = parseChild(responseHead, 7);
            String name = group != null ? group.string(4) : forward != null ? forward.string(6) : "QQ用户";
            alias = AnonState.aliasFor(uid, uin, name);
            responseHead.setVarint(1, AnonState.PLACEHOLDER_UIN);
            responseHead.setString(2, AnonState.PLACEHOLDER_UID);
            responseHead.setVarint(5, 0);
            responseHead.remove(6);
            if (group != null) {
                group.setVarint(1, AnonState.PLACEHOLDER_GROUP);
                group.setString(4, alias);
                group.remove(7);
                responseHead.setBytes(8, group.toByteArray());
            }
            if (forward != null) {
                forward.setString(6, alias);
                responseHead.setBytes(7, forward.toByteArray());
            }
            body.setBytes(1, responseHead.toByteArray());
        }

        if (contentHead != null) {
            contentHead.setVarint(5, ids.sequence);
            if (contentHead.first(4) != null) contentHead.setVarint(4, ids.random);
            if (contentHead.first(12) != null) contentHead.setVarint(12, ids.messageId);
            ProtoMessage forward = parseChild(contentHead, 15);
            if (forward != null) {
                forward.remove(5);
                forward.remove(6);
                contentHead.setBytes(15, forward.toByteArray());
            }
            body.setBytes(2, contentHead.toByteArray());
        }

        ProtoMessage payload = parseChild(body, 3);
        if (payload != null) {
            ProtoMessage richText = parseChild(payload, 1);
            if (richText != null) {
                sanitizeRichText(richText, alias, context, depth);
                payload.setBytes(1, richText.toByteArray());
            }
            body.setBytes(3, payload.toByteArray());
        }
        return ids;
    }

    private static void sanitizeRichText(ProtoMessage richText, String alias, RewriteContext context, int depth) throws Exception {
        // Optional legacy typography includes its own random/time/reserve fields.
        richText.remove(1);
        ProtoMessage voice = parseChild(richText, 4);
        if (voice != null) {
            if (voice.first(2) != null) voice.setVarint(2, AnonState.PLACEHOLDER_UIN);
            richText.setBytes(4, voice.toByteArray());
        }
        for (ProtoMessage.Field elementField : richText.all(2)) {
            ProtoMessage element = ProtoMessage.parse(elementField.bytes);
            sanitizeElement(element, alias, context, depth);
            if (element.fields().isEmpty()) richText.removeField(elementField);
            else elementField.bytes = element.toByteArray();
        }
    }

    private static void sanitizeElement(ProtoMessage element, String alias, RewriteContext context, int depth) throws Exception {
        // ElemFlags2 is metadata: original msgId, bubble/font/VIP, instance/location identifiers.
        element.remove(9);
        // QQ anonymous-group profile contains a separate nick/id/avatar/bubble, no message content.
        element.remove(21);
        ProtoMessage text = parseChild(element, 1);
        if (text != null) {
            sanitizeText(text);
            element.setBytes(1, text.toByteArray());
        }

        ProtoMessage extraInfo = parseChild(element, 16);
        if (extraInfo != null) {
            String extraAlias = alias != null ? alias : AnonState.aliasFor(null, extraInfo.varint(9, 0),
                    extraInfo.string(2) != null ? extraInfo.string(2) : extraInfo.string(1));
            MessageMetadataSanitizer.extraInfo(extraInfo, extraAlias);
            element.setBytes(16, extraInfo.toByteArray());
        }

        ProtoMessage generalFlags = parseChild(element, 37);
        if (generalFlags != null) {
            MessageMetadataSanitizer.generalFlags(generalFlags);
            element.setBytes(37, generalFlags.toByteArray());
        }

        ProtoMessage source = parseChild(element, 45);
        if (source != null) {
            sanitizeSourceMessage(source, context, depth + 1);
            element.setBytes(45, source.toByteArray());
        }

        ProtoMessage richMessage = parseChild(element, 12);
        if (richMessage != null) {
            byte[] template = richMessage.bytes(1);
            if (template != null && template.length > 2 && template[0] == 1) {
                String xml = new String(inflate(Arrays.copyOfRange(template, 1, template.length)), StandardCharsets.UTF_8);
                byte[] compressed = deflate(ArkPreviewSanitizer.sanitizeXml(xml).getBytes(StandardCharsets.UTF_8));
                byte[] rebuilt = new byte[compressed.length + 1];
                rebuilt[0] = 1;
                System.arraycopy(compressed, 0, rebuilt, 1, compressed.length);
                richMessage.setBytes(1, rebuilt);
                element.setBytes(12, richMessage.toByteArray());
            }
        }

        ProtoMessage lightApp = parseChild(element, 51);
        if (lightApp != null) {
            byte[] data = lightApp.bytes(1);
            if (data != null && data.length > 2 && data[0] == 1) {
                String json = new String(inflate(Arrays.copyOfRange(data, 1, data.length)), StandardCharsets.UTF_8);
                byte[] compressed = deflate(ArkPreviewSanitizer.sanitizeArk(json).getBytes(StandardCharsets.UTF_8));
                byte[] rebuilt = new byte[compressed.length + 1];
                rebuilt[0] = 1;
                System.arraycopy(compressed, 0, rebuilt, 1, compressed.length);
                lightApp.setBytes(1, rebuilt);
                element.setBytes(51, lightApp.toByteArray());
            }
        }
    }

    private static void sanitizeText(ProtoMessage text) {
        String content = text.string(1);
        byte[] attr6 = text.bytes(3);
        ProtoMessage reserve = parseChild(text, 12);
        long targetUin = 0;
        String targetUid = null;
        if (attr6 != null && attr6.length >= 11) {
            targetUin = ((long) attr6[7] & 0xff) << 24
                    | ((long) attr6[8] & 0xff) << 16
                    | ((long) attr6[9] & 0xff) << 8
                    | ((long) attr6[10] & 0xff);
        }
        if (reserve != null) {
            if (targetUin == 0) targetUin = reserve.varint(4, 0);
            targetUid = reserve.string(9);
        }
        boolean structuredAt = (attr6 != null && attr6.length > 0)
                || (targetUid != null && !targetUid.isBlank())
                || targetUin != 0;
        if (structuredAt && content != null && content.startsWith("@")) {
            String visible = content.substring(1).trim();
            text.setString(1, "@" + AnonState.aliasFor(targetUid, targetUin, visible));
            text.remove(3);
            text.remove(4);
            text.remove(12);
        } else if (content != null && content.contains("@")) {
            text.setString(1, sanitizeVisibleMentions(content));
            text.remove(3);
            text.remove(4);
        }
    }

    private static String sanitizeVisibleMentions(String content) {
        Matcher matcher = VISIBLE_MENTION.matcher(content);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String alias = name.startsWith("匿名用户") ? name : AnonState.aliasForName(name);
            matcher.appendReplacement(output, Matcher.quoteReplacement("@" + alias));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static void sanitizeSourceMessage(ProtoMessage source, RewriteContext context, int depth) throws Exception {
        if (depth > 16) throw new IllegalArgumentException("Reply nesting exceeds limit");
        ProtoMessage reserve = parseChild(source, 8);
        if (reserve == null) reserve = new ProtoMessage();
        ProtoMessage sourceMessage = parseChild(source, 9);
        String alias;
        SyntheticIds ids;
        if (sourceMessage != null) {
            ids = sanitizeMessageBody(sourceMessage, context, depth);
            ProtoMessage head = parseChild(sourceMessage, 1);
            ProtoMessage group = head == null ? null : parseChild(head, 8);
            ProtoMessage forward = head == null ? null : parseChild(head, 7);
            alias = group != null ? group.string(4) : forward != null ? forward.string(6) : null;
            source.setBytes(9, sourceMessage.toByteArray());
        } else {
            ids = context.sparseReply(source, reserve);
            alias = AnonState.aliasFor(reserve.string(6), source.varint(2, 0), "QQ用户");
        }
        source.remove(1);
        source.setVarint(1, ids.sequence);
        source.setVarint(2, AnonState.PLACEHOLDER_UIN);
        source.setVarint(10, 0);
        source.remove(11);
        for (ProtoMessage.Field elementField : source.all(5)) {
            ProtoMessage element = ProtoMessage.parse(elementField.bytes);
            sanitizeElement(element, alias, context, depth);
            if (element.fields().isEmpty()) source.removeField(elementField);
            else elementField.bytes = element.toByteArray();
        }
        reserve.setVarint(3, ids.messageId);
        reserve.setString(6, AnonState.PLACEHOLDER_UID);
        reserve.setString(7, AnonState.PLACEHOLDER_UID);
        reserve.setVarint(8, ids.sequence);
        source.setBytes(8, reserve.toByteArray());
    }

    private static ProtoMessage parseChild(ProtoMessage parent, int field) {
        byte[] bytes = parent.bytes(field);
        if (bytes == null) {
            if (parent.first(field) != null) throw new IllegalArgumentException("Wrong wire type for message field " + field);
            return null;
        }
        return ProtoMessage.parse(bytes);
    }

    private static byte[] gunzip(byte[] data) throws Exception {
        return readAll(new GZIPInputStream(new ByteArrayInputStream(data)));
    }

    private static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(data);
        }
        return output.toByteArray();
    }

    private static byte[] inflate(byte[] data) throws Exception {
        return readAll(new InflaterInputStream(new ByteArrayInputStream(data)));
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
        while ((count = input.read(buffer)) >= 0) {
            if (output.size() + count > MAX_EXPANDED_BYTES) throw new IllegalArgumentException("Expanded message exceeds limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static Framing detectFraming(byte[] raw) {
        try {
            ProtoMessage parsed = ProtoMessage.parse(raw);
            ProtoMessage info = parseChild(parsed, 2);
            if (info != null && info.bytes(4) != null) return new Framing(raw, raw, false, 0);
        } catch (Exception ignored) {
        }
        if (raw.length > 4 && raw[0] == 0) {
            int declared = ByteBuffer.wrap(raw, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
            if (declared != raw.length && declared != raw.length - 4) throw new IllegalArgumentException("Invalid packet length prefix");
            return new Framing(raw, Arrays.copyOfRange(raw, 4, raw.length), true, declared);
        }
        throw new IllegalArgumentException("Unknown SsoSendLongMsg framing");
    }

    private record SyntheticIds(long sequence, long messageId, long random) {}

    private static final class RewriteContext {
        final Map<String, SyntheticIds> identities = new HashMap<>();

        SyntheticIds identity(ProtoMessage head, ProtoMessage content) {
            if (head == null || content == null) return create(0);
            ProtoMessage group = parseChild(head, 8);
            String sender = head.string(2) != null ? head.string(2) : Long.toUnsignedString(head.varint(1, 0));
            long sequence = content.varint(5, 0), random = content.varint(4, 0), id = content.varint(12, 0);
            if (sequence == 0 && random == 0 && id == 0) return create(random);
            String key = sender + ":" + (group == null ? head.varint(5, 0) : group.varint(1, 0))
                    + ":" + sequence + ":" + random + ":" + id;
            return identities.computeIfAbsent(key, ignored -> create(random));
        }

        SyntheticIds sparseReply(ProtoMessage source, ProtoMessage reserve) {
            String key = "reply:" + source.varint(2, 0) + ":" + reserve.string(6)
                    + ":" + source.varint(1, 0) + ":" + reserve.varint(3, 0);
            return identities.computeIfAbsent(key, ignored -> create(0));
        }

        private SyntheticIds create(long oldRandom) {
            long random;
            do { random = Integer.toUnsignedLong(RANDOM.nextInt()); } while (random == 0 || random == oldRandom);
            return new SyntheticIds(AnonState.nextSyntheticSequence(), AnonState.nextSyntheticMessageId(), random);
        }
    }

    private static final class Framing {
        final byte[] original;
        final byte[] body;
        final boolean prefixed;
        final int declaredLength;

        Framing(byte[] original, byte[] body, boolean prefixed, int declaredLength) {
            this.original = original;
            this.body = body;
            this.prefixed = prefixed;
            this.declaredLength = declaredLength;
        }

        byte[] wrap(byte[] value) {
            if (!prefixed) return value;
            int newDeclared = declaredLength == original.length - 4 ? value.length : value.length + 4;
            ByteBuffer output = ByteBuffer.allocate(value.length + 4).order(ByteOrder.BIG_ENDIAN);
            output.putInt(newDeclared);
            output.put(value);
            return output.array();
        }
    }
}
