package dev.anonforward.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Set;

public interface HookBridge {
    int PRIORITY_DEFAULT = 50;
    int PRIORITY_LOWEST = -10000;
    int PRIORITY_HIGHEST = 10000;

    interface MemberHookCallback {
        void beforeHookedMember(MemberHookParam param) throws Throwable;
        void afterHookedMember(MemberHookParam param) throws Throwable;
    }

    interface MemberHookParam {
        Member getMember();
        Object getThisObject();
        Object[] getArgs();
        Object getResult();
        void setResult(Object result);
        Throwable getThrowable();
        void setThrowable(Throwable throwable);
        Object getExtra();
        void setExtra(Object extra);
    }

    interface UnhookHandle {
        Member getMember();
        MemberHookCallback getCallback();
        boolean isHookActive();
        void unhook();
    }

    UnhookHandle hookMethod(Member member, MemberHookCallback callback, int priority);
    boolean isDeoptimizationSupported();
    boolean deoptimize(Member member);
    Object invokeOriginalMethod(Method method, Object thisObject, Object[] args)
            throws NullPointerException, IllegalAccessException, IllegalArgumentException, InvocationTargetException;
    <T> void invokeOriginalConstructor(Constructor<T> ctor, T thisObject, Object[] args)
            throws NullPointerException, IllegalAccessException, IllegalArgumentException, InvocationTargetException;
    <T> T newInstanceOrigin(Constructor<T> constructor, Object... args)
            throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException;
    long getHookCounter();
    Set<Member> getHookedMethods();
}
