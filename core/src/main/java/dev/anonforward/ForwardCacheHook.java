package dev.anonforward;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

import dev.anonforward.runtime.HookBridge;
import dev.anonforward.runtime.RuntimeEnvironment;

/** Protects marked virtual forward records at local cache read/update boundaries. */
final class ForwardCacheHook {
    private static final Object LOCK = new Object();
    private static final Set<Method> HOOKED = new HashSet<>();
    private static AnonymousCacheIndex index = new AnonymousCacheIndex();
    private static AtomicFile store;
    private static volatile Operation operation;
    private static volatile long automaticGeneration = -1;
    private static int hookCount;
    private static final Set<String> HISTORY_READS = Set.of(
            "getMsgs", "getMsgsExt", "getMsgsByMsgId", "getMsgsBySeqList", "getMsgsBySeqRange",
            "getMsgsBySeqAndCount", "getMsgsIncludeSelf", "getMsgsByTypeFilter", "getMsgsByTypeFilters",
            "getMsgsWithStatus", "getMsgsWithMsgTimeAndClientSeqForC2C", "getSingleMsg", "getMsgByClientSeqAndTime",
            "getLatestDbMsgs", "getLastMessageList", "getLastLiveMsgs", "getAioFirstViewLatestMsgs",
            "getAioFirstViewLatestMsgsForAioPopup");

    private ForwardCacheHook() {}

    static void install(String hostDataDir) {
        synchronized (LOCK) {
            if (store != null) return;
            store = new AtomicFile(new File(new File(hostDataDir, "files"), "anon_forward_cache_v1.json"));
            try {
                if (store.getBaseFile().length() > 32 * 1024 * 1024) throw new IllegalStateException("Cache index too large");
                index = AnonymousCacheIndex.parse(new String(store.readFully(), StandardCharsets.UTF_8));
            } catch (java.io.FileNotFoundException firstRun) {
                // AtomicFile restores a backup before reporting a genuinely absent index.
            } catch (Exception error) { AFLog.e("Could not restore anonymous cache protection", error); }
        }
        ClassLoader host = RuntimeEnvironment.hostClassLoader();
        for (String prefix : new String[]{"com.tencent.qqnt.kernel.nativeinterface.", "com.tencent.qqnt.kernelpublic.nativeinterface."}) {
            try {
                Class<?> proxy = host.loadClass(prefix + "IKernelMsgService$CppProxy");
                for (Method method : proxy.getDeclaredMethods()) {
                    if (method.getName().equals("getMultiMsg") && method.getParameterCount() == 4) hookRead(method);
                    if (HISTORY_READS.contains(method.getName())) hookHistoryRead(method);
                    if (method.getName().equals("addKernelMsgListener") && method.getParameterCount() == 1) {
                        hook(method, new HookBridge.MemberHookCallback() {
                            @Override public void beforeHookedMember(HookBridge.MemberHookParam param) {
                                if (param.getArgs()[0] != null) hookListener(param.getArgs()[0].getClass());
                            }
                            @Override public void afterHookedMember(HookBridge.MemberHookParam param) {}
                        });
                    }
                }
            } catch (ClassNotFoundException ignored) {}
        }
        // These implementations are present on the tested QQ build. Registration also discovers
        // future/obfuscated listener classes without depending on Frida or the original QAux hook.
        for (String name : new String[]{"com.tencent.qqnt.kernel.api.impl.MsgService$b", "com.tencent.qqnt.kernel.api.impl.MsgService$d"}) {
            try { hookListener(host.loadClass(name)); } catch (ClassNotFoundException ignored) {}
        }
        ForwardPageTitleHook.install();
        AFLog.i("Anonymous local cache hooks installed=" + hookCount + ", protected roots=" + index.entries.size());
    }

    static int hookCount() { return hookCount; }

    static boolean protectsRoot(long rootId) {
        if (rootId == 0) return false;
        String account = account();
        if (account == null) return false;
        synchronized (LOCK) {
            for (AnonymousCacheIndex.Entry entry : index.entries.values()) {
                if (entry.account.equals(account) && entry.rootId == rootId) return true;
            }
            return false;
        }
    }

    static boolean protectsResource(String resId) {
        String account = account();
        if (account == null) return false;
        synchronized (LOCK) { return index.knownResource(account, resId); }
    }

    private static void hook(Method method, HookBridge.MemberHookCallback callback) {
        synchronized (LOCK) {
            if (Modifier.isAbstract(method.getModifiers()) || !HOOKED.add(method)) return;
            try {
                method.setAccessible(true);
                RuntimeEnvironment.hookBridge().hookMethod(method, callback, HookBridge.PRIORITY_HIGHEST - 50);
                hookCount++;
            } catch (Exception error) { HOOKED.remove(method); AFLog.e("Cache hook installation failed", error); }
        }
    }

    private static void hookRead(Method method) {
        hook(method, new HookBridge.MemberHookCallback() {
            @Override public void beforeHookedMember(HookBridge.MemberHookParam param) {
                boolean protectedRead = false;
                try {
                    Object[] args = param.getArgs();
                    String account = account();
                    if (account == null || !(args[1] instanceof Long root)) return;
                    AnonymousCacheIndex.Entry entry;
                    synchronized (LOCK) { entry = index.root(account, contact(args[0]), root); }
                    if (entry == null) return;
                    protectedRead = true;
                    Object original = args[3];
                    Class<?> callbackType = method.getParameterTypes()[3];
                    args[3] = CachedReadCallback.wrap(callbackType, original, entry, LOCK, ForwardCacheHook::save);
                } catch (Exception error) {
                    AFLog.e("Could not bind cached MultiMsg callback", error);
                    if (protectedRead) {
                        param.setResult(null);
                        try {
                            Object callback = param.getArgs()[3];
                            Method failure = Reflect.findMethod(callback.getClass(), "onResult", 3);
                            if (failure != null) failure.invoke(callback, -1, "匿名记录读取失败", new ArrayList<>());
                        } catch (Exception callbackError) { AFLog.e("Could not report cached read failure", callbackError); }
                    }
                }
            }
            @Override public void afterHookedMember(HookBridge.MemberHookParam param) {}
        });
    }

    private static void hookHistoryRead(Method method) {
        Class<?>[] types = method.getParameterTypes();
        int callbackIndex = -1;
        for (int i = 0; i < types.length; i++) {
            if (!types[i].isInterface() || !types[i].getSimpleName().endsWith("Callback")) continue;
            for (Method callback : types[i].getMethods()) {
                if (callback.getName().equals("onResult")) { callbackIndex = i; break; }
            }
        }
        if (callbackIndex < 0) return;
        final int slot = callbackIndex;
        hook(method, new HookBridge.MemberHookCallback() {
            @Override public void beforeHookedMember(HookBridge.MemberHookParam param) {
                String account = account();
                Object original = param.getArgs()[slot];
                if (account == null || original == null) return;
                param.getArgs()[slot] = CachedHistoryCallback.wrap(types[slot], original,
                        values -> projectCacheArguments(account, values));
            }
            @Override public void afterHookedMember(HookBridge.MemberHookParam param) {}
        });
    }

    private static Object[] projectCacheArguments(String account, Object[] args) throws Exception {
        for (Object value : args) observeReturnedRoots(value);
        synchronized (LOCK) {
            CachedRecordProjection projection = new CachedRecordProjection(index, account);
            Object[] result = args.clone();
            boolean changed = false;
            for (int i = 0; i < args.length; i++) {
                result[i] = projection.value(args[i]);
                changed |= result[i] != args[i];
                if (args[i] != null && args[i].getClass().getSimpleName().equals("MsgRecord") && result[i] == null
                        && args.length > 0 && args[0] instanceof Number) {
                    result[0] = -1;
                    if (args.length > 1 && args[1] instanceof String) result[1] = "匿名预览读取失败";
                }
            }
            if (projection.isDirty()) save();
            if (changed) AFLog.i("Protected anonymous card/history cache result");
            return changed ? result : args;
        }
    }

    private static void observeReturnedRoots(Object value) throws Exception {
        if (value == null) return;
        if (value instanceof List<?> list) {
            for (Object item : list) if (item != null && item.getClass().getSimpleName().equals("MsgRecord")) observeRoot(item);
        } else if (value.getClass().getSimpleName().equals("MsgRecord")) observeRoot(value);
        else if (value.getClass().getSimpleName().equals("MsgsRsp") && CachedMessageSanitizer.number(value, "result") == 0) {
            observeReturnedRoots(CachedMessageSanitizer.value(value, "msgList"));
        }
    }

    private static void hookListener(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                String name = method.getName();
                if (method.getParameterCount() != 1 || (!name.equals("onAddSendMsg")
                        && !name.equals("onMsgInfoListAdd") && !name.equals("onMsgInfoListUpdate"))) continue;
                hook(method, new HookBridge.MemberHookCallback() {
                    @Override public void beforeHookedMember(HookBridge.MemberHookParam param) {
                        try {
                            Object value = param.getArgs()[0];
                            String account = account();
                            if (account == null || value == null) return;
                            Object projected = projectCacheArguments(account, new Object[]{value})[0];
                            if (projected == null) param.setResult(null);
                            else param.getArgs()[0] = projected;
                        } catch (Exception error) { AFLog.e("Cache message notification failed", error); }
                    }
                    @Override public void afterHookedMember(HookBridge.MemberHookParam param) {}
                });
            }
        }
    }

    /** Runs before the existing forward entry hook; virtual cached children remain protected on re-forward. */
    static void beforeForward(HookBridge.MemberHookParam param) {
        String method = param.getMember().getName();
        if (method.startsWith("native_") || !method.toLowerCase(java.util.Locale.ROOT).contains("forward")) return;
        try {
            String account = account();
            if (account == null) return;
            Set<Long> ids = new HashSet<>();
            Object source = null;
            Object destination = null;
            for (Object argument : param.getArgs()) {
                if (argument instanceof List<?> values) {
                    for (Object value : values) {
                        if (value instanceof Number number) ids.add(number.longValue());
                        else if (value != null && value.getClass().getSimpleName().equals("MultiMsgInfo")) ids.add(CachedMessageSanitizer.number(value, "msgId"));
                    }
                } else if (argument != null && argument.getClass().getSimpleName().equals("Contact")) {
                    if (source == null) source = argument;
                    else if (destination == null) destination = argument;
                }
            }
            boolean protectedSource = false;
            synchronized (LOCK) {
                for (long id : ids) if (index.protectedMessage(account, id) != null) { protectedSource = true; break; }
            }
            if (automaticGeneration == AnonState.generation() && !protectedSource) {
                AnonState.disarm();
                automaticGeneration = -1;
            }
            if (protectedSource && !AnonState.isArmed()) {
                AnonState.arm();
                long generation = AnonState.generation();
                automaticGeneration = generation;
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (automaticGeneration == generation && AnonState.generation() == generation) {
                        AnonState.disarm(); automaticGeneration = -1;
                    }
                }, 60_000);
                AFLog.i("Preserving anonymous cached content during re-forward");
            }
            if (AnonState.isArmed() && method.toLowerCase(java.util.Locale.ROOT).startsWith("multiforward")
                    && destination != null && !ids.isEmpty()) {
                operation = new Operation(account, contact(destination), ids, AnonState.generation(), SystemClock.elapsedRealtime());
            }
        } catch (Exception error) { AFLog.e("Could not identify cached forwarding scope", error); }
    }

    static void onSanitizedUpload() {
        Operation op = operation;
        if (op == null || op.generation != AnonState.generation()) return;
        Map<String, String> snapshot = AnonState.aliasSnapshot();
        synchronized (LOCK) {
            op.aliases = snapshot;
            if (op.entry != null) {
                op.entry.importAliases(snapshot);
                save();
            }
        }
    }

    private static void observeRoot(Object record) throws Exception {
        long id = CachedMessageSanitizer.number(record, "msgId");
        String account = account();
        if (account == null || id == 0) return;
        String contact = contact(record);
        synchronized (LOCK) {
            AnonymousCacheIndex.Entry entry = index.root(account, contact, id);
            boolean changed = false;
            List<String> resources = resourceIds(record);
            if (entry == null) {
                for (String resId : resources) {
                    AnonymousCacheIndex.Entry origin = index.resource(account, resId);
                    if (origin == null) continue;
                    entry = index.add(account, contact, id, origin.sourceIds);
                    entry.inheritAliases(origin);
                    changed = true;
                    break;
                }
            }
            Operation op = operation;
            if (entry == null && op != null && op.account.equals(account) && op.contact.equals(contact)
                    && op.generation == AnonState.generation() && SystemClock.elapsedRealtime() - op.started < 60_000
                    && op.entry == null && !op.sources.contains(id) && CachedMessageSanitizer.number(record, "sendType") == 5
                    && CachedMessageSanitizer.number(record, "sendStatus") == 1 && !resources.isEmpty()) {
                entry = index.add(account, contact, id, op.sources);
                entry.importAliases(op.aliases);
                op.entry = entry;
                changed = true;
                AFLog.i("Registered anonymous forwarding cache scope");
            }
            if (entry == null) return;
            for (String resId : resources) if (!resId.isEmpty()) changed |= entry.resources.add(AnonymousCacheIndex.digest(resId));
            if (changed) save();
            if (op != null && op.entry == entry && !entry.resources.isEmpty()) operation = null;
        }
    }

    static boolean knownAnonymousPayload(String raw) {
        if (store == null || raw == null) return false;
        try {
            String account = account();
            if (account == null) return false;
            String resId;
            if (raw.trim().startsWith("{")) {
                JSONObject card = new JSONObject(raw);
                if (!"com.tencent.multimsg".equals(card.optString("app"))) return false;
                resId = card.getJSONObject("meta").getJSONObject("detail").optString("resid");
            } else {
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\bm_resid=['\"]([^'\"]+)['\"]").matcher(raw);
                if (!matcher.find()) return false;
                resId = matcher.group(1);
            }
            synchronized (LOCK) { return index.knownResource(account, resId); }
        } catch (Exception ignored) { return false; }
    }

    private static List<String> resourceIds(Object record) throws Exception {
        ArrayList<String> ids = new ArrayList<>();
        Object value = CachedMessageSanitizer.value(record, "elements");
        if (!(value instanceof List<?> elements)) return ids;
        for (Object element : elements) {
            Object ark = CachedMessageSanitizer.value(element, "arkElement");
            if (ark != null) try {
                JSONObject card = new JSONObject(CachedMessageSanitizer.string(ark, "bytesData"));
                if ("com.tencent.multimsg".equals(card.optString("app"))) ids.add(card.getJSONObject("meta").getJSONObject("detail").optString("resid"));
            } catch (org.json.JSONException ignored) {}
            Object multi = CachedMessageSanitizer.value(element, "multiForwardMsgElement");
            if (multi != null) ids.add(CachedMessageSanitizer.string(multi, "resId"));
            Object struct = CachedMessageSanitizer.value(element, "structMsgElement");
            if (struct != null) {
                String xml = CachedMessageSanitizer.string(struct, "xmlContent");
                if (xml.contains("serviceID=\"35\"") || xml.contains("serviceID='35'")) {
                    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\bm_resid=['\"]([^'\"]*)['\"]").matcher(xml);
                    if (matcher.find()) ids.add(matcher.group(1));
                }
            }
        }
        return ids;
    }

    private static String account() {
        try {
            Object runtime = RuntimeEnvironment.appRuntime();
            for (String name : new String[]{"getCurrentAccountUin", "getAccount"}) {
                Method method = Reflect.findMethod(runtime.getClass(), name, 0);
                if (method == null) continue;
                String account = String.valueOf(method.invoke(runtime));
                if (account.matches("[1-9][0-9]{4,}")) return AnonymousCacheIndex.digest(account);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String contact(Object object) throws Exception {
        return AnonymousCacheIndex.digest(CachedMessageSanitizer.number(object, "chatType") + ":" + CachedMessageSanitizer.string(object, "peerUid"));
    }

    private static void save() {
        if (store == null) return;
        FileOutputStream output = null;
        try {
            byte[] data = index.serialize().getBytes(StandardCharsets.UTF_8);
            output = store.startWrite();
            output.write(data);
            store.finishWrite(output);
        } catch (Exception error) {
            store.failWrite(output);
            AFLog.e("Could not persist anonymous cache scope", error);
        }
    }

    private static final class Operation {
        final String account;
        final String contact;
        final Set<Long> sources;
        final long generation;
        final long started;
        Map<String, String> aliases = Map.of();
        AnonymousCacheIndex.Entry entry;
        Operation(String account, String contact, Set<Long> sources, long generation, long started) {
            this.account = account; this.contact = contact; this.sources = new HashSet<>(sources);
            this.generation = generation; this.started = started;
        }
    }
}
