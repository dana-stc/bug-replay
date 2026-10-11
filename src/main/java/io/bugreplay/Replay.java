package io.bugreplay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves a {@link Recording} back as mock dependencies.
 *
 * <p>Calls are matched per dependency, in recorded order, by method and (masked) arguments.
 * Anything unexpected fails with a {@link ReplayMismatchException} that shows expected vs actual.
 */
public final class Replay {

    private final Map<String, Deque<Interaction>> remaining = new LinkedHashMap<>();
    private final Masker masker;

    private Replay(Recording recording, Masker masker) {
        this.masker = masker;
        for (Interaction i : recording.interactions()) {
            remaining.computeIfAbsent(i.dependency(), k -> new ArrayDeque<>()).addLast(i);
        }
    }

    public static Replay of(Recording recording) {
        return new Replay(recording, Masker.defaults());
    }

    /** Use the same masker that was used while recording. */
    public static Replay of(Recording recording, Masker masker) {
        return new Replay(recording, masker);
    }

    public <T> T mock(Class<T> dependency) {
        if (!dependency.isInterface()) {
            throw new IllegalArgumentException(dependency.getName() + " is not an interface");
        }
        Object proxy = Proxy.newProxyInstance(dependency.getClassLoader(), new Class<?>[] {dependency},
                (p, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "ReplayMock(" + dependency.getSimpleName() + ")";
                            case "hashCode" -> System.identityHashCode(p);
                            default -> p == args[0];
                        };
                    }
                    return answer(dependency, method, args == null ? new Object[0] : args);
                });
        return dependency.cast(proxy);
    }

    /** Fails if some recorded interactions were never requested by the code under test. */
    public synchronized void verifyAllConsumed() {
        List<String> left = new ArrayList<>();
        remaining.forEach((dep, queue) -> queue.forEach(i -> left.add(describe(i))));
        if (!left.isEmpty()) {
            throw new ReplayMismatchException("Recorded interactions were never replayed:\n  "
                    + String.join("\n  ", left));
        }
    }

    private synchronized Object answer(Class<?> dependency, Method method, Object[] args) throws Throwable {
        Deque<Interaction> queue = remaining.get(dependency.getName());
        Interaction next = queue == null ? null : queue.peekFirst();
        List<JsonNode> actualArgs = new ArrayList<>();
        for (Object arg : args) {
            actualArgs.add(masker.mask(Json.tree(arg)));
        }
        String actual = dependency.getSimpleName() + "." + method.getName() + actualArgs;
        if (next == null) {
            throw new ReplayMismatchException("Unexpected call, nothing more was recorded for this dependency: "
                    + actual);
        }
        if (!next.method().equals(method.getName()) || !sameTypes(next, method)
                || !sameArgs(next.args(), actualArgs)) {
            throw new ReplayMismatchException("Call differs from the recording.\n  expected: "
                    + describe(next) + "\n  actual:   " + actual);
        }
        queue.pollFirst();

        if (next.threw()) {
            throw toThrow(method, rebuild(next.thrownType(), next.thrownMessage()));
        }
        if (method.getReturnType() == void.class || next.result() == null || next.result().isNull()) {
            return null;
        }
        Type returnType = method.getGenericReturnType();
        return Json.MAPPER.convertValue(next.result(), Json.MAPPER.constructType(returnType));
    }

    private static boolean sameTypes(Interaction i, Method method) {
        Class<?>[] types = method.getParameterTypes();
        if (types.length != i.paramTypes().size()) {
            return false;
        }
        for (int k = 0; k < types.length; k++) {
            if (!types[k].getName().equals(i.paramTypes().get(k))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameArgs(List<JsonNode> expected, List<JsonNode> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int k = 0; k < expected.size(); k++) {
            JsonNode e = expected.get(k) == null ? NullNode.instance : expected.get(k);
            if (!e.equals(actual.get(k))) {
                return false;
            }
        }
        return true;
    }

    private static String describe(Interaction i) {
        String dep = i.dependency().substring(Math.max(i.dependency().lastIndexOf('.'),
                i.dependency().lastIndexOf('$')) + 1);
        return dep + "." + i.method() + i.args();
    }

    private static Throwable rebuild(String type, String message) {
        try {
            Class<?> c = Class.forName(type);
            if (Throwable.class.isAssignableFrom(c)) {
                try {
                    return (Throwable) c.getConstructor(String.class).newInstance(message);
                } catch (ReflectiveOperationException e) {
                    return (Throwable) c.getConstructor().newInstance();
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // fall through to the stand-in
        }
        return new ReplayedException(type, message);
    }

    private static Throwable toThrow(Method method, Throwable t) {
        if (t instanceof RuntimeException || t instanceof Error) {
            return t;
        }
        for (Class<?> declared : method.getExceptionTypes()) {
            if (declared.isInstance(t)) {
                return t;
            }
        }
        return new ReplayedException(t.getClass().getName(), t.getMessage());
    }
}
