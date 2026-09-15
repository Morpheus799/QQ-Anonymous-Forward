package dev.anonforward;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ArkPreviewSanitizer {
    private static final Pattern MENTION = Pattern.compile("@([^\\s@，。！？、:：]+)");
    private static final Pattern XML_TITLE = Pattern.compile("(<title\\b[^>]*>)(.*?)(</title>)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern XML_SOURCE = Pattern.compile("(<source\\b[^>]*\\bname=)[\"'][^\"']*[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_ID_ATTRIBUTE = Pattern.compile("(\\b(?:uin|senderUin|peerUin|groupUin|troop_code|groupcode)=)[\"'][^\"']*[\"']", Pattern.CASE_INSENSITIVE);

    private ArkPreviewSanitizer() {}

    static String sanitizeArk(String raw) throws JSONException {
        JSONObject root = new JSONObject(raw);
        if (!"com.tencent.multimsg".equals(root.optString("app"))) return raw;
        sanitizeObject(root);
        JSONObject detail = root.optJSONObject("meta") == null ? null : root.optJSONObject("meta").optJSONObject("detail");
        if (detail != null) {
            JSONArray news = detail.optJSONArray("news");
            if (news != null) {
                for (int index = 0; index < news.length(); index++) {
                    JSONObject item = news.optJSONObject(index);
                    if (item != null) item.put("text", anonymizePreview(item.optString("text")));
                }
            }
            detail.put("source", "匿名聊天记录");
        }
        return root.toString();
    }

    static String sanitizeXml(String xml) {
        Matcher matcher = XML_TITLE.matcher(xml);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String content = matcher.group(2);
            matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group(1) + anonymizePreview(content) + matcher.group(3)));
        }
        matcher.appendTail(output);
        String result = XML_SOURCE.matcher(output.toString()).replaceAll("$1\"匿名聊天记录\"");
        return XML_ID_ATTRIBUTE.matcher(result).replaceAll("$1\"0\"");
    }

    static String anonymizePreview(String text) {
        if (text == null || text.isEmpty()) return text;
        String known = AnonState.replaceKnownPreviewPrefix(text);
        if (!known.equals(text)) text = known;
        int colon = text.indexOf(':');
        int chineseColon = text.indexOf('：');
        if (colon < 0 || (chineseColon >= 0 && chineseColon < colon)) colon = chineseColon;
        String result = text;
        if (colon > 0) {
            String name = text.substring(0, colon).trim();
            if (!name.contains("聊天记录") && !name.startsWith("匿名用户")) {
                result = AnonState.aliasForName(name) + text.substring(colon);
            }
        }
        Matcher mention = MENTION.matcher(result);
        StringBuffer replaced = new StringBuffer();
        while (mention.find()) {
            String target = mention.group(1);
            String alias = target.startsWith("匿名用户") ? target : AnonState.aliasForName(target);
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
