package dev.anonforward;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persistent protection scope. Stores no plaintext nickname, UIN, UID, or resource ID. */
final class AnonymousCacheIndex {
    final Map<String, Entry> entries = new LinkedHashMap<>();

    static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte value : bytes) out.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
            return out.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static String key(String account, String contact, long root) { return account + ":" + contact + ":" + root; }

    Entry add(String account, String contact, long root, Set<Long> sourceIds) {
        String key = key(account, contact, root);
        Entry existing = entries.get(key);
        if (existing != null) return existing;
        Entry entry = new Entry(account, contact, root);
        entry.sourceIds.addAll(sourceIds);
        entries.put(key, entry);
        return entry;
    }

    Entry root(String account, String contact, long root) { return entries.get(key(account, contact, root)); }

    Entry node(String account, long msgId) {
        for (Entry entry : entries.values()) {
            if (entry.account.equals(account) && entry.nodeIds.contains(msgId) && !entry.sourceIds.contains(msgId)) return entry;
        }
        return null;
    }

    Entry protectedMessage(String account, long msgId) {
        for (Entry entry : entries.values()) {
            if (entry.account.equals(account) && (entry.rootId == msgId || entry.nodeIds.contains(msgId))
                    && !entry.sourceIds.contains(msgId)) return entry;
        }
        return null;
    }

    boolean knownResource(String account, String resId) {
        return resource(account, resId) != null;
    }

    Entry resource(String account, String resId) {
        if (resId == null || resId.isEmpty()) return null;
        String hash = digest(resId);
        for (Entry entry : entries.values()) if (entry.account.equals(account) && entry.resources.contains(hash)) return entry;
        return null;
    }

    String serialize() throws Exception {
        JSONArray array = new JSONArray();
        for (Entry entry : entries.values()) array.put(entry.toJson());
        return new JSONObject().put("version", 1).put("entries", array).toString();
    }

    static AnonymousCacheIndex parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        if (root.getInt("version") != 1) throw new IllegalArgumentException("Unknown cache index version");
        AnonymousCacheIndex result = new AnonymousCacheIndex();
        JSONArray entries = root.getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject object = entries.getJSONObject(i);
            Entry entry = new Entry(object.getString("account"), object.getString("contact"), Long.parseLong(object.getString("root")));
            readLongs(object.getJSONArray("sources"), entry.sourceIds);
            readLongs(object.getJSONArray("nodes"), entry.nodeIds);
            JSONArray resources = object.getJSONArray("resources");
            for (int n = 0; n < resources.length(); n++) entry.resources.add(resources.getString(n));
            JSONObject aliases = object.getJSONObject("aliases");
            for (java.util.Iterator<String> it = aliases.keys(); it.hasNext();) {
                String aliasKey = it.next();
                String alias = aliases.getString(aliasKey);
                entry.aliases.put(aliasKey, alias);
                entry.reserve(alias);
            }
            result.entries.put(key(entry.account, entry.contact, entry.rootId), entry);
        }
        return result;
    }

    private static void readLongs(JSONArray array, Set<Long> output) throws Exception {
        for (int i = 0; i < array.length(); i++) output.add(Long.parseLong(array.getString(i)));
    }

    static final class Entry implements AliasLookup {
        final String account;
        final String contact;
        final long rootId;
        final Set<Long> sourceIds = new HashSet<>();
        final Set<Long> nodeIds = new HashSet<>();
        final Set<String> resources = new HashSet<>();
        final Map<String, String> aliases = new HashMap<>();
        private int counter;
        long revision;

        Entry(String account, String contact, long rootId) {
            this.account = account; this.contact = contact; this.rootId = rootId;
        }

        void importAliases(Map<String, String> snapshot) {
            snapshot.forEach((key, alias) -> { putAlias(digest(key), alias); reserve(alias); });
        }

        void inheritAliases(Entry origin) {
            origin.aliases.forEach((key, alias) -> { putAlias(key, alias); reserve(alias); });
        }

        void recordNode(long id) { if (id != 0 && id != rootId && !sourceIds.contains(id) && nodeIds.add(id)) revision++; }

        @Override public String aliasFor(String uid, long uin, String name) {
            String identity = uid != null && !uid.isBlank() && !uid.equals("0") && !uid.equals(AnonState.PLACEHOLDER_UID)
                    ? "uid:" + uid : uin > 0 && uin != AnonState.PLACEHOLDER_UIN ? "uin:" + Long.toUnsignedString(uin) : "name:" + clean(name);
            String key = digest(identity);
            String alias = aliases.get(key);
            if (alias == null) alias = aliasForName(name);
            putAlias(key, alias);
            return alias;
        }

        @Override public String aliasForName(String name) {
            String normalized = clean(name);
            if (normalized.matches("匿名用户[0-9]+")) { reserve(normalized); return normalized; }
            String key = digest("name:" + normalized);
            String alias = aliases.get(key);
            if (alias == null) { alias = "匿名用户" + (++counter); putAlias(key, alias); }
            return alias;
        }

        private void putAlias(String key, String alias) {
            if (!alias.equals(aliases.put(key, alias))) revision++;
        }

        @Override public String replaceKnownPreviewPrefix(String text) {
            if (text == null) return null;
            // Hash only prefixes immediately preceding a separator; no plaintext name dictionary is needed.
            for (int i = text.length() - 1; i > 0; i--) {
                if (text.charAt(i) != ':' && text.charAt(i) != '：') continue;
                String alias = aliases.get(digest("name:" + text.substring(0, i).trim()));
                if (alias != null) return alias + text.substring(i);
            }
            return text;
        }

        private void reserve(String alias) {
            if (alias.startsWith("匿名用户")) {
                try { counter = Math.max(counter, Integer.parseInt(alias.substring(4))); }
                catch (NumberFormatException ignored) {}
            }
        }

        private static String clean(String name) { return name == null || name.isBlank() ? "QQ用户" : name.trim(); }

        JSONObject toJson() throws Exception {
            JSONObject mapping = new JSONObject();
            aliases.forEach((key, value) -> {
                try { mapping.put(key, value); } catch (Exception error) { throw new IllegalStateException(error); }
            });
            return new JSONObject().put("account", account).put("contact", contact).put("root", Long.toString(rootId))
                    .put("sources", stringIds(sourceIds)).put("nodes", stringIds(nodeIds))
                    .put("resources", new JSONArray(resources)).put("aliases", mapping);
        }

        private static JSONArray stringIds(Set<Long> ids) {
            List<String> values = new ArrayList<>();
            for (long id : ids) values.add(Long.toString(id));
            return new JSONArray(values);
        }
    }
}
