package io.github.qauxv.loader.hookapi;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Set;

public interface IHookBridge {
    int PRIORITY_DEFAULT = 50;
    int PRIORITY_LOWEST = -10000;
    int PRIORITY_HIGHEST = 10000;

    interface IMemberHookCallback {
        void beforeHookedMember(IMemberHookParam param) throws Throwable;
        void afterHookedMember(IMemberHookParam param) throws Throwable;
    }

    interface IMemberHookParam {
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

    interface MemberUnhookHandle {
        Member getMember();
        IMemberHookCallback getCallback();
        boolean isHookActive();
        void unhook();
    }

    int getApiLevel();
    String getFrameworkName();
    String getFrameworkVersion();
    long getFrameworkVersionCode();
    MemberUnhookHandle hookMethod(Member member, IMemberHookCallback callback, int priority);
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
