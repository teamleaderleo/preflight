package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.JsonTree;
import dev.starsector.preflight.core.JsonTreeSink;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent's only door into the game's {@code org.json} classes.
 *
 * <p>Preflight is compiled without {@code json.jar} on its classpath -- deliberately, because the
 * agent has to load beside whatever the installation ships rather than beside a copy we chose. So
 * everything here is reflective, resolved once against the thread's context loader and then reused.
 *
 * <p>Reading and writing are not symmetric on purpose. {@link #toTree} runs on a learning launch
 * and can afford to build ordinary maps and lists; the sink runs on every cache hit of every later
 * launch and writes straight into the game's own objects, so a hit allocates nothing it does not
 * return.
 *
 * <p>A JSON null is not a Java null. {@code JSONObject.put} with a Java null <em>removes</em> the
 * key, which would turn "present and null" into "absent" and change what {@code has} answers, so the
 * sentinel is carried through both directions instead.
 */
final class GameJson {
    private static volatile GameJson resolved;

    private final Class<?> objectType;
    private final Class<?> arrayType;
    private final Object nullValue;
    private final Constructor<?> objectConstructor;
    private final Constructor<?> arrayConstructor;
    private final Method objectPut;
    private final Method arrayPut;
    private final Method sortedKeys;
    private final Method objectGet;
    private final Method arrayLength;
    private final Method arrayGet;
    private final Sink sink = new Sink();

    private GameJson(ClassLoader loader) throws ReflectiveOperationException {
        objectType = Class.forName("org.json.JSONObject", true, loader);
        arrayType = Class.forName("org.json.JSONArray", true, loader);
        nullValue = objectType.getField("NULL").get(null);
        objectConstructor = objectType.getConstructor();
        arrayConstructor = arrayType.getConstructor();
        objectPut = objectType.getMethod("put", String.class, Object.class);
        arrayPut = arrayType.getMethod("put", Object.class);
        sortedKeys = objectType.getMethod("sortedKeys");
        objectGet = objectType.getMethod("get", String.class);
        arrayLength = arrayType.getMethod("length");
        arrayGet = arrayType.getMethod("get", int.class);
    }

    static GameJson bridge() throws ReflectiveOperationException {
        GameJson existing = resolved;
        if (existing != null) {
            return existing;
        }
        GameJson bridge = new GameJson(Thread.currentThread().getContextClassLoader());
        resolved = bridge;
        return bridge;
    }

    /** Forgets the resolved classes so a new session resolves against its own loader. */
    static void forget() {
        resolved = null;
    }

    boolean isGameJson(Object value) {
        return value != null && (objectType.isInstance(value) || arrayType.isInstance(value));
    }

    /** Encodes what the game produced, or throws if it holds something the format cannot carry. */
    byte[] encode(Object value) throws ReflectiveOperationException {
        return JsonTree.encode(toTree(value));
    }

    /** Rebuilds a game JSON value from a tagged tree. */
    Object decode(byte[] tree) {
        return JsonTree.decode(tree, sink);
    }

    /**
     * Converts one game JSON value into plain maps, lists and boxed primitives.
     *
     * <p>Object members are taken in sorted key order so that an artifact is a function of what the
     * merge produced and not of the order the merge happened to insert things in.
     */
    private Object toTree(Object value) throws ReflectiveOperationException {
        if (value == null || value == nullValue) {
            return JsonTree.NULL;
        }
        if (objectType.isInstance(value)) {
            Map<String, Object> members = new LinkedHashMap<>();
            Iterator<?> keys = (Iterator<?>) invoke(sortedKeys, value);
            while (keys.hasNext()) {
                Object key = keys.next();
                if (!(key instanceof String name)) {
                    throw new IllegalArgumentException("A JSON object key was not a string");
                }
                members.put(name, toTree(invoke(objectGet, value, name)));
            }
            return members;
        }
        if (arrayType.isInstance(value)) {
            int length = (Integer) invoke(arrayLength, value);
            List<Object> elements = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                elements.add(toTree(invoke(arrayGet, value, index)));
            }
            return elements;
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Integer
                || value instanceof Long || value instanceof Double || value instanceof Float
                || value instanceof Short || value instanceof Byte) {
            return value;
        }
        // Anything else -- a BigDecimal, a bean the game wrapped, some mod's own type -- would have
        // to be approximated to be stored, so it costs a cache entry instead.
        throw new IllegalArgumentException(
                "A JSON value was a " + value.getClass().getName() + ", which is not storable");
    }

    private static Object invoke(Method method, Object receiver, Object... arguments)
            throws ReflectiveOperationException {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw failed;
        }
    }

    /** Writes decoded values directly into the game's containers. */
    private final class Sink implements JsonTreeSink {
        @Override
        public Object nullValue() {
            return GameJson.this.nullValue;
        }

        @Override
        public Object newObject() {
            try {
                return objectConstructor.newInstance();
            } catch (ReflectiveOperationException failed) {
                throw new IllegalStateException("Could not create a game JSON object", failed);
            }
        }

        @Override
        public void put(Object object, String key, Object value) {
            try {
                objectPut.invoke(object, key, value);
            } catch (ReflectiveOperationException failed) {
                throw new IllegalStateException("Could not populate a game JSON object", failed);
            }
        }

        @Override
        public Object newArray() {
            try {
                return arrayConstructor.newInstance();
            } catch (ReflectiveOperationException failed) {
                throw new IllegalStateException("Could not create a game JSON array", failed);
            }
        }

        @Override
        public void add(Object array, Object value) {
            try {
                arrayPut.invoke(array, value);
            } catch (ReflectiveOperationException failed) {
                throw new IllegalStateException("Could not populate a game JSON array", failed);
            }
        }
    }
}
