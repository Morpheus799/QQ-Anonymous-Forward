package dev.anonforward;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import dev.anonforward.runtime.HookBridge;
import dev.anonforward.runtime.RuntimeEnvironment;
import io.github.qauxv.chainloader.api.ChainLoaderAgent;
import io.github.qauxv.loader.hookapi.IHookBridge;

final class QAuxRuntimeBackend implements RuntimeEnvironment.Backend {
    private final HookBridge hookBridge = new QAuxHookBridge(ChainLoaderAgent.getHookBridge());

    @Override public ClassLoader moduleClassLoader() { return ChainLoaderAgent.getModuleClassLoader(); }
    @Override public ClassLoader hostClassLoader() { return ChainLoaderAgent.getHostClassLoader(); }
    @Override public HookBridge hookBridge() { return hookBridge; }
    @Override public String processName() { return ChainLoaderAgent.getProcessName(); }

    @Override
    public Object appRuntime() throws ReflectiveOperationException {
        Class<?> helper = Class.forName(
                "io.github.qauxv.bridge.AppRuntimeHelper", false, moduleClassLoader());
        return helper.getMethod("getAppRuntime").invoke(null);
    }

    @Override
    public List<Class<?>> findAdapterImplementations(String interfaceName) throws Exception {
        Object backend = null;
        try {
            ClassLoader qaux = moduleClassLoader();
            Class<?> backendClass = Class.forName(
                    "io.github.qauxv.util.dexkit.impl.DexKitDeobfs", false, qaux);
            backend = backendClass.getMethod("newInstance").invoke(null);
            Object dexBridge = backendClass.getMethod("getDexKitBridge").invoke(backend);
            Class<?> findClassType = Class.forName("org.luckypray.dexkit.query.FindClass", false, qaux);
            Class<?> classMatcherType = Class.forName(
                    "org.luckypray.dexkit.query.matchers.ClassMatcher", false, qaux);
            Object matcher = classMatcherType.getMethod("create").invoke(null);
            classMatcherType.getMethod("addInterface", String.class).invoke(matcher, interfaceName);
            Object query = findClassType.getMethod("create").invoke(null);
            findClassType.getMethod("matcher", classMatcherType).invoke(query, matcher);
            Object result = dexBridge.getClass().getMethod("findClass", findClassType).invoke(dexBridge, query);
            List<Class<?>> classes = new ArrayList<>();
            if (result instanceof Iterable<?> iterable) {
                for (Object classData : iterable) {
                    Class<?> implementation = (Class<?>) classData.getClass()
                            .getMethod("getInstance", ClassLoader.class)
                            .invoke(classData, hostClassLoader());
                    classes.add(implementation);
                }
            }
            return classes;
        } catch (ClassNotFoundException unavailable) {
            return Collections.emptyList();
        } finally {
            if (backend != null) {
                try {
                    backend.getClass().getMethod("close").invoke(backend);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Override
    public void logToFramework(String message, Throwable error) {
        try {
            Class<?> logger = Class.forName(
                    "io.github.qauxv.util.xpcompat.XposedBridge", false, moduleClassLoader());
            logger.getMethod("log", String.class).invoke(null, message);
            if (error != null) logger.getMethod("log", Throwable.class).invoke(null, error);
        } catch (Throwable ignored) {
        }
    }

    private static final class QAuxHookBridge implements HookBridge {
        private final IHookBridge delegate;

        QAuxHookBridge(IHookBridge delegate) {
            this.delegate = delegate;
        }

        @Override
        public UnhookHandle hookMethod(Member member, MemberHookCallback callback, int priority) {
            IHookBridge.IMemberHookCallback adapted = new IHookBridge.IMemberHookCallback() {
                @Override
                public void beforeHookedMember(IHookBridge.IMemberHookParam param) throws Throwable {
                    callback.beforeHookedMember(new QAuxHookParam(param));
                }

                @Override
                public void afterHookedMember(IHookBridge.IMemberHookParam param) throws Throwable {
                    callback.afterHookedMember(new QAuxHookParam(param));
                }
            };
            IHookBridge.MemberUnhookHandle handle = delegate.hookMethod(member, adapted, priority);
            return new UnhookHandle() {
                @Override public Member getMember() { return handle.getMember(); }
                @Override public MemberHookCallback getCallback() { return callback; }
                @Override public boolean isHookActive() { return handle.isHookActive(); }
                @Override public void unhook() { handle.unhook(); }
            };
        }

        @Override public boolean isDeoptimizationSupported() { return delegate.isDeoptimizationSupported(); }
        @Override public boolean deoptimize(Member member) { return delegate.deoptimize(member); }

        @Override
        public Object invokeOriginalMethod(Method method, Object thisObject, Object[] args)
                throws NullPointerException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
            return delegate.invokeOriginalMethod(method, thisObject, args);
        }

        @Override
        public <T> void invokeOriginalConstructor(Constructor<T> ctor, T thisObject, Object[] args)
                throws NullPointerException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
            delegate.invokeOriginalConstructor(ctor, thisObject, args);
        }

        @Override
        public <T> T newInstanceOrigin(Constructor<T> constructor, Object... args)
                throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException {
            return delegate.newInstanceOrigin(constructor, args);
        }

        @Override public long getHookCounter() { return delegate.getHookCounter(); }
        @Override public Set<Member> getHookedMethods() { return delegate.getHookedMethods(); }
    }

    private static final class QAuxHookParam implements HookBridge.MemberHookParam {
        private final IHookBridge.IMemberHookParam delegate;

        QAuxHookParam(IHookBridge.IMemberHookParam delegate) {
            this.delegate = delegate;
        }

        @Override public Member getMember() { return delegate.getMember(); }
        @Override public Object getThisObject() { return delegate.getThisObject(); }
        @Override public Object[] getArgs() { return delegate.getArgs(); }
        @Override public Object getResult() { return delegate.getResult(); }
        @Override public void setResult(Object result) { delegate.setResult(result); }
        @Override public Throwable getThrowable() { return delegate.getThrowable(); }
        @Override public void setThrowable(Throwable throwable) { delegate.setThrowable(throwable); }
        @Override public Object getExtra() { return delegate.getExtra(); }
        @Override public void setExtra(Object extra) { delegate.setExtra(extra); }
    }
}
