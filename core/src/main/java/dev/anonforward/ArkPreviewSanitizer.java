package dev.anonforward;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ArkPreviewSanitizer {
    static final String ANONYMOUS_TITLE = "匿名聊天记录";
    private static final AliasLookup CURRENT = new AliasLookup() {
        @Override public String aliasFor(String uid, long uin, String name) { return AnonState.aliasFor(uid, uin, name); }
        @Override public String aliasForName(String name) { return AnonState.aliasForName(name); }
        @Override public String replaceKnownPreviewPrefix(String text) { return AnonState.replaceKnownPreviewPrefix(text); }
    };
    private static final Pattern MENTION = Pattern.compile("@([^\\s@，。！？、:：]+)");
    private static final Pattern XML_TITLE = Pattern.compile("(<title\\b[^>]*>)(.*?)(</title>)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern XML_SOURCE = Pattern.compile("(<source\\b[^>]*\\bname=)[\"'][^\"']*[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_ID_ATTRIBUTE = Pattern.compile("(\\b(?:uin|senderUin|peerUin|groupUin|troop_code|groupcode)=)[\"'][^\"']*[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_BRIEF = Pattern.compile("(\\bbrief=)[\"'][^\"']*[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_SUMMARY = Pattern.compile("(<summary\\b[^>]*>).*?(</summary>)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ANONYMOUS_PREFIX = Pattern.compile("^匿名用户[0-9]+\\s*[:：]");
    private static final Pattern XML_MSG_TAG = Pattern.compile("<msg\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_FORWARD_ATTRIBUTE = Pattern.compile("\\b(?:serviceID\\s*=\\s*['\"]35['\"]|action\\s*=\\s*['\"]viewMultiMsg['\"])", Pattern.CASE_INSENSITIVE);

    private ArkPreviewSanitizer() {}

    static String sanitizeArk(String raw) throws JSONException {
        return sanitizeArk(raw, CURRENT);
    }

    static String sanitizeArk(String raw, AliasLookup aliases) throws JSONException {
        JSONObject root = new JSONObject(raw);
        if (!"com.tencent.multimsg".equals(root.optString("app"))) return raw;
        sanitizeObject(root);
        root.put("desc", "[" + ANONYMOUS_TITLE + "]");
        root.put("prompt", "[" + ANONYMOUS_TITLE + "]");
        if (root.has("title")) root.put("title", ANONYMOUS_TITLE);
        JSONObject detail = root.optJSONObject("meta") == null ? null : root.optJSONObject("meta").optJSONObject("detail");
        if (detail != null) {
            JSONArray news = detail.optJSONArray("news");
            if (news != null) {
                for (int index = 0; index < news.length(); index++) {
                    JSONObject item = news.optJSONObject(index);
                    if (item != null) item.put("text", anonymizePreview(item.optString("text"), aliases));
                    else if (news.opt(index) instanceof String text) news.put(index, anonymizePreview(text, aliases));
                }
            }
            detail.put("source", ANONYMOUS_TITLE);
            if (detail.has("title")) detail.put("title", ANONYMOUS_TITLE);
            int count = 0;
            Object extra = root.opt("extra");
            try {
                JSONObject info = extra instanceof JSONObject object ? object : extra instanceof String text ? new JSONObject(text) : null;
                if (info != null) count = info.optInt("tsum");
            } catch (JSONException ignored) {}
            detail.put("summary", count > 0 ? "查看" + count + "条匿名消息" : "查看匿名聊天记录");
        }
        return root.toString();
    }

    static String sanitizeXml(String xml) {
        return sanitizeXml(xml, CURRENT);
    }

    static String sanitizeXml(String xml, AliasLookup aliases) {
        if (!isForwardXml(xml)) return xml;
        Matcher matcher = XML_TITLE.matcher(xml);
        StringBuffer output = new StringBuffer();
        boolean firstTitle = true;
        while (matcher.find()) {
            String content = matcher.group(2);
            String text = firstTitle ? ANONYMOUS_TITLE : anonymizePreview(content, aliases);
            firstTitle = false;
            matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group(1) + text + matcher.group(3)));
        }
        matcher.appendTail(output);
        String result = XML_SOURCE.matcher(output.toString()).replaceAll("$1\"匿名聊天记录\"");
        result = XML_BRIEF.matcher(result).replaceAll("$1\"[匿名聊天记录]\"");
        result = XML_SUMMARY.matcher(result).replaceAll("$1查看匿名聊天记录$2");
        return XML_ID_ATTRIBUTE.matcher(result).replaceAll("$1\"0\"");
    }

    static boolean isForwardXml(String xml) {
        if (xml == null) return false;
        Matcher tag = XML_MSG_TAG.matcher(xml);
        return tag.find() && XML_FORWARD_ATTRIBUTE.matcher(tag.group()).find();
    }

    static String anonymizePreview(String text) {
        return anonymizePreview(text, CURRENT);
    }

    static String anonymizePreview(String text, AliasLookup aliases) {
        if (text == null || text.isEmpty()) return text;
        if (!ANONYMOUS_PREFIX.matcher(text).find()) {
            String known = aliases.replaceKnownPreviewPrefix(text);
            if (!known.equals(text)) text = known;
        }
        int colon = text.indexOf(':');
        int chineseColon = text.indexOf('：');
        if (colon < 0 || (chineseColon >= 0 && chineseColon < colon)) colon = chineseColon;
        String result = text;
        if (colon > 0) {
            String name = text.substring(0, colon).trim();
            if (!name.contains("聊天记录") && !name.startsWith("匿名用户")) {
                result = aliases.aliasForName(name) + text.substring(colon);
            }
        }
        Matcher mention = MENTION.matcher(result);
        StringBuffer replaced = new StringBuffer();
        while (mention.find()) {
            String target = mention.group(1);
            String alias = target.startsWith("匿名用户") ? target : aliases.aliasForName(target);
            mention.appendReplacement(replaced, Matcher.quoteReplacement("@" + alias));
        }
        mention.appendTail(replaced);
        return replaced.toString();
    }

    private static void sanitizeObject(JSONObject object) throws JSONException {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.opt(key);
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("avatar") || lower.contains("faceurl") || lower.equals("fromfaceurl")) {
                object.put(key, "");
            } else if (lower.equals("senderuid") || lower.equals("peeruid") || lower.equals("uid")) {
                object.put(key, AnonState.PLACEHOLDER_UID);
            } else if (lower.equals("senderuin") || lower.equals("uin") || lower.equals("user_id")) {
                object.put(key, AnonState.PLACEHOLDER_UIN);
            } else if (lower.equals("peeruin") || lower.equals("groupuin") || lower.equals("group_id") || lower.equals("troop_code")) {
                object.put(key, AnonState.PLACEHOLDER_GROUP);
            } else if (value instanceof JSONObject child) {
                sanitizeObject(child);
            } else if (value instanceof JSONArray array) {
                for (int index = 0; index < array.length(); index++) {
                    Object item = array.opt(index);
                    if (item instanceof JSONObject child) sanitizeObject(child);
                }
            }
        }
    }

}
