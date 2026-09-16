package dev.anonforward;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.lang.reflect.Method;
import dev.anonforward.runtime.HookBridge;
import dev.anonforward.runtime.RuntimeEnvironment;

/** Only the page for a marked root gets an anonymous title; contact/routing extras stay intact. */
final class ForwardPageTitleHook {
    private static final String PAGE = "com.tencent.mobileqq.activity.MultiForwardActivity";
    private static boolean installed;

    private ForwardPageTitleHook() {}

    static synchronized void install() {
        if (installed) return;
        try {
            Class<?> page = RuntimeEnvironment.hostClassLoader().loadClass(PAGE);
            hookLifecycle(find(page, "onCreate", Bundle.class));
            hookLifecycle(find(page, "onResume"));
            hookLifecycle(find(page, "onNewIntent", Intent.class));
            Method setTitle = find(page, "setTitle", CharSequence.class);
            if (setTitle == null) throw new NoSuchMethodException("MultiForwardActivity.setTitle");
            RuntimeEnvironment.hookBridge().hookMethod(setTitle, new HookBridge.MemberHookCallback() {
                @Override public void beforeHookedMember(HookBridge.MemberHookParam param) {
                    if (param.getThisObject() instanceof Activity activity && isPage(activity) && protectedIntent(activity.getIntent())) {
                        param.getArgs()[0] = ArkPreviewSanitizer.ANONYMOUS_TITLE;
                    }
                }
                @Override public void afterHookedMember(HookBridge.MemberHookParam param) {}
            }, HookBridge.PRIORITY_HIGHEST - 50);
            installed = true;
            AFLog.i("Anonymous forwarding page title hooks installed");
        } catch (Exception error) { AFLog.e("Could not install forwarding page title hooks", error); }
    }

    private static void hookLifecycle(Method method) {
        if (method == null) return;
        RuntimeEnvironment.hookBridge().hookMethod(method, new HookBridge.MemberHookCallback() {
            @Override public void beforeHookedMember(HookBridge.MemberHookParam param) {
                if (!(param.getThisObject() instanceof Activity activity) || !isPage(activity)) return;
                Intent intent = param.getArgs().length > 0 && param.getArgs()[0] instanceof Intent value ? value : activity.getIntent();
                if (!protectedIntent(intent)) return;
                intent.putExtra("key_chat_name", ArkPreviewSanitizer.ANONYMOUS_TITLE);
                param.setExtra(Boolean.TRUE);
            }
            @Override public void afterHookedMember(HookBridge.MemberHookParam param) {
                if (!Boolean.TRUE.equals(param.getExtra()) || param.getThrowable() != null) return;
                Activity activity = (Activity) param.getThisObject();
                activity.setTitle(ArkPreviewSanitizer.ANONYMOUS_TITLE);
                AFLog.i("Protected anonymous forwarding page title");
            }
        }, HookBridge.PRIORITY_HIGHEST - 50);
    }

    private static boolean isPage(Activity activity) { return activity.getClass().getName().equals(PAGE); }

    private static boolean protectedIntent(Intent intent) {
        if (intent == null) return false;
        try {
            Bundle extras = intent.getExtras();
            if (extras == null) return false;
            Object value = extras.get("key_multiforward_root_msgid");
            if (value != null) {
                long root = value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
                if (ForwardCacheHook.protectsRoot(root)) return true;
            }
            return ForwardCacheHook.protectsResource(extras.getString("multi_url"));
        } catch (Exception ignored) { return false; }
    }

    private static Method find(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Method method = current.getDeclaredMethod(name, parameters); method.setAccessible(true); return method; }
            catch (NoSuchMethodException ignored) {}
        }
        return null;
    }
}
