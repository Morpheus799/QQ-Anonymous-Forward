package dev.anonforward;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class Reflect {
    private Reflect() {}

    static Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    static Object call(Object receiver, String name, Object... args) throws ReflectiveOperationException {
        for (Class<?> current = receiver.getClass(); current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                    method.setAccessible(true);
                    return method.invoke(receiver, args);
                }
            }
        }
        throw new NoSuchMethodException(receiver.getClass().getName() + "." + name);
    }

    static Object getField(Object receiver, String name) throws ReflectiveOperationException {
        for (Class<?> current = receiver.getClass(); current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(receiver);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(receiver.getClass().getName() + "." + name);
    }

    static void setField(Object receiver, String name, Object value) throws ReflectiveOperationException {
        for (Class<?> current = receiver.getClass(); current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                field.set(receiver, value);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(receiver.getClass().getName() + "." + name);
    }
}
