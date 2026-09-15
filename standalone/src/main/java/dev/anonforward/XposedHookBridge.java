package dev.anonforward;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import dev.anonforward.runtime.HookBridge;

final class XposedHookBridge implements HookBridge {
    private final AtomicLong hookCounter = new AtomicLong();
    private final Set<Member> hookedMethods = Collections.synchronizedSet(new LinkedHashSet<>());

    @Override
    public UnhookHandle hookMethod(Member member, MemberHookCallback callback, int priority) {
        Map<XC_MethodHook.MethodHookParam, HookParam> params =
                Collections.synchronizedMap(new WeakHashMap<>());
        XC_MethodHook xposedCallback = new XC_MethodHook(priority) {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                HookParam wrapper = new HookParam(param);
                params.put(param, wrapper);
                callback.beforeHookedMember(wrapper);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                HookParam wrapper = params.remove(param);
                if (wrapper == null) wrapper = new HookParam(param);
                callback.afterHookedMember(wrapper);
            }
        };
        XC_MethodHook.Unhook unhook = XposedBridge.hookMethod(member, xposedCallback);
        hookedMethods.add(member);
        hookCounter.incrementAndGet();
        return new UnhookHandle(member, callback, unhook);
    }

    @Override public boolean isDeoptimizationSupported() { return false; }
    @Override public boolean deoptimize(Member member) { return false; }

    @Override
    public Object invokeOriginalMethod(Method method, Object thisObject, Object[] args)
            throws NullPointerException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        try {
            return XposedBridge.invokeOriginalMethod(method, thisObject, args);
        } catch (InvocationTargetException error) {
            throw error;
        } catch (Throwable error) {
            throw new InvocationTargetException(error);
        }
    }

    @Override
    public <T> void invokeOriginalConstructor(Constructor<T> ctor, T thisObject, Object[] args)
            throws NullPointerException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        throw new UnsupportedOperationException("Original constructor invocation is not used by AnonForward");
    }

    @Override
    public <T> T newInstanceOrigin(Constructor<T> constructor, Object... args)
            throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException {
        try {
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (InvocationTargetException error) {
            throw error;
        }
    }

    @Override public long getHookCounter() { return hookCounter.get(); }
    @Override public Set<Member> getHookedMethods() { return Collections.unmodifiableSet(hookedMethods); }

    private static final class HookParam implements MemberHookParam {
        private final XC_MethodHook.MethodHookParam delegate;
        private Object extra;

        HookParam(XC_MethodHook.MethodHookParam delegate) {
            this.delegate = delegate;
        }

        @Override public Member getMember() { return delegate.method; }
        @Override public Object getThisObject() { return delegate.thisObject; }
        @Override public Object[] getArgs() { return delegate.args; }
        @Override public Object getResult() { return delegate.getResult(); }
        @Override public void setResult(Object result) { delegate.setResult(result); }
        @Override public Throwable getThrowable() { return delegate.getThrowable(); }
        @Override public void setThrowable(Throwable throwable) { delegate.setThrowable(throwable); }
        @Override public Object getExtra() { return extra; }
        @Override public void setExtra(Object extra) { this.extra = extra; }
    }

    private static final class UnhookHandle implements HookBridge.UnhookHandle {
        private final Member member;
        private final MemberHookCallback callback;
        private final XC_MethodHook.Unhook unhook;
        private boolean active = true;

        UnhookHandle(Member member, MemberHookCallback callback, XC_MethodHook.Unhook unhook) {
            this.member = member;
            this.callback = callback;
            this.unhook = unhook;
        }

        @Override public Member getMember() { return member; }
        @Override public MemberHookCallback getCallback() { return callback; }
        @Override public boolean isHookActive() { return active; }

        @Override
        public void unhook() {
            if (!active) return;
            unhook.unhook();
            active = false;
        }
    }
}
