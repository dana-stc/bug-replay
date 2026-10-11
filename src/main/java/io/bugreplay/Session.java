package io.bugreplay;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Records the calls one request makes to its dependencies.
 *
 * <pre>{@code
 * Session session = Session.start("checkout-500");
 * PaymentGateway gateway = session.record(PaymentGateway.class, realGateway);
 * try {
 *     handler.handle(request, gateway);
 * } catch (Throwable t) {
 *     session.failed(t);
 *     throw t;
 * } finally {
 *     session.finish().save(Path.of("recordings/checkout-500.json"));
 * }
 * }</pre>
 */
public final class Session {

    private final String scenario;
    private final Masker masker;
    private final List<Interaction> interactions = new ArrayList<>();
    private Recording.Failure failure;

    private Session(String scenario, Masker masker) {
        this.scenario = scenario;
        this.masker = masker;
    }

    public static Session start(String scenario) {
        return new Session(scenario, Masker.defaults());
    }

    public static Session start(String scenario, Masker masker) {
        return new Session(scenario, masker);
    }

    /** Wraps a real dependency. Every call through the returned proxy is recorded. */
    public <T> T record(Class<T> dependency, T real) {
        if (!dependency.isInterface()) {
            throw new IllegalArgumentException(dependency.getName() + " is not an interface");
        }
        Object proxy = Proxy.newProxyInstance(dependency.getClassLoader(), new Class<?>[] {dependency},
                (p, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(real, args);
                    }
                    Object[] actualArgs = args == null ? new Object[0] : args;
                    method.trySetAccessible();
                    Object result;
                    try {
                        result = method.invoke(real, actualArgs);
                    } catch (InvocationTargetException e) {
                        Throwable cause = e.getCause();
                        add(dependency, method, actualArgs, null, cause);
                        throw cause;
                    }
                    add(dependency, method, actualArgs, result, null);
                    return result;
                });
        return dependency.cast(proxy);
    }

    /** Marks the request as failed with this exception. */
    public synchronized void failed(Throwable t) {
        this.failure = new Recording.Failure(t.getClass().getName(), t.getMessage());
    }

    public synchronized Recording finish() {
        return new Recording(UUID.randomUUID().toString().substring(0, 8), scenario, Instant.now(),
                List.copyOf(interactions), failure);
    }

    private synchronized void add(Class<?> dependency, Method method, Object[] args, Object result,
            Throwable thrown) {
        List<JsonNode> maskedArgs = Arrays.stream(args).map(a -> masker.mask(Json.tree(a)))
                .collect(Collectors.toList());
        List<String> paramTypes = Arrays.stream(method.getParameterTypes()).map(Class::getName)
                .collect(Collectors.toList());
        JsonNode maskedResult = method.getReturnType() == void.class || thrown != null
                ? null : masker.mask(Json.tree(result));
        interactions.add(new Interaction(dependency.getName(), method.getName(), paramTypes, maskedArgs,
                maskedResult, thrown == null ? null : thrown.getClass().getName(),
                thrown == null ? null : thrown.getMessage()));
    }
}
