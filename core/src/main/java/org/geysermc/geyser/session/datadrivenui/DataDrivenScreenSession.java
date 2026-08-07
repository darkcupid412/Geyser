/*
 * Copyright (c) 2019-2026 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.session.datadrivenui;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One showing of a data-driven screen: the property it seeds, the paths the client may write, and
 * the update counters both directions have to agree on. This is the generic layer: it knows
 * screens, properties, paths and values, and nothing about forms. The form compiler is one client
 * of it, and packets are the host's job, through {@link Host}.
 *
 * <p>Everything here runs on the session's event loop; nothing is thread-safe by itself.
 *
 * <p>The counter rules are what the client actually does, observed on the wire:
 *
 * <ul>
 *   <li>{@code updateCount} is the property's epoch. It is 1 from the seed onwards, and advances
 *       only when the whole property changes, which happens once more, at teardown. Writes from
 *       an old epoch are stale and dropped.
 *   <li>{@code pathUpdateCount} is a single sequence per path, continued across both directions:
 *       the client counts its own writes, and a server push continues wherever the sequence
 *       currently stands. A push kept on a private counter would fall behind after the player's
 *       first edit.
 *   <li>A property name is never reused. Vanilla keeps a property's counters after its removal,
 *       so a reused name would inherit them; every showing gets a fresh instance id, and the
 *       property carries its unsigned rendering.
 * </ul>
 */
public final class DataDrivenScreenSession {

    /** How a session gets its packets out; implemented over the Bedrock connection. */
    public interface Host {
        /** Seeds the property with the full tree, at epoch 1, before the screen is shown. */
        void sendSeed(String property, Map<String, Object> tree);

        /** Tells the client to show the screen reading that property. */
        void sendShow(String screenId, int formId, int dataInstanceId);

        /** Pushes one path's new value into the open screen. */
        void sendUpdate(String property, String path, Object value, int updateCount, int pathUpdateCount);

        /** Tells the client to close this screen. */
        void sendCloseScreen(int formId);

        /** Tears the property down: a change to null at the given epoch. */
        void sendTeardown(String property, int updateCount);
    }

    /** What a client write to a bound path flows into, after validation. */
    public interface WriteHandler {
        void handle(Object value);
    }

    /**
     * Checks and normalizes a client-written value. Returns what the write should become (a
     * slider write clamps into its range, for example), or null to drop the write entirely.
     */
    public interface Validator {
        Validator ANY = value -> value;

        @Nullable Object normalize(Object value);
    }

    /** The primitive types a path can carry; the update packet knows exactly these three. */
    public enum ValueType {
        STRING(String.class),
        BOOLEAN(Boolean.class),
        DOUBLE(Double.class);

        private final Class<?> javaType;

        ValueType(Class<?> javaType) {
            this.javaType = javaType;
        }

        boolean matches(Object value) {
            return javaType.isInstance(value);
        }
    }

    private enum State {
        NEW,
        SHOWN,
        CLOSED
    }

    private final Host host;
    private final String screenId;
    private final String propertyName;
    private final int formId;
    private final int dataInstanceId;
    private final Map<String, Object> seed = new LinkedHashMap<>();
    private final Map<String, Binding> bindings = new HashMap<>();
    private final Map<String, Integer> pathUpdateCounts = new HashMap<>();
    private int updateCount = 1;
    private State state = State.NEW;

    public DataDrivenScreenSession(Host host, String screenId, String propertyBase, int formId, int dataInstanceId) {
        this.host = Objects.requireNonNull(host, "host");
        this.screenId = Objects.requireNonNull(screenId, "screenId");
        this.formId = formId;
        this.dataInstanceId = dataInstanceId;
        // The instance id travels as a signed int but names the property in its unsigned form.
        this.propertyName = propertyBase + "_" + Integer.toUnsignedString(dataInstanceId);
    }

    public int formId() {
        return formId;
    }

    public String propertyName() {
        return propertyName;
    }

    public boolean closed() {
        return state == State.CLOSED;
    }

    /** Sets the tree the property is seeded with. Only possible before the screen is shown. */
    public void seed(Map<String, Object> tree) {
        requireNew();
        seed.putAll(tree);
    }

    /**
     * Allows one path to exist: outbound pushes may target it, and when {@code clientWritable}
     * the client may write it. Every other path the client writes is dropped. The vanilla server
     * keeps the same kind of per-path allowlist.
     */
    public void bind(String path, ValueType type, boolean clientWritable, Validator validator, WriteHandler handler) {
        requireNew();
        Objects.requireNonNull(path, "path");
        if (bindings.putIfAbsent(path, new Binding(type, clientWritable, validator, handler)) != null) {
            throw new IllegalArgumentException("path " + path + " is already bound");
        }
    }

    /** Seeds the property and shows the screen. A session shows exactly once. */
    public void show() {
        requireNew();
        state = State.SHOWN;
        host.sendSeed(propertyName, seed);
        host.sendShow(screenId, formId, dataInstanceId);
    }

    /**
     * Pushes a new value for a bound path into the open screen, continuing the path's shared
     * sequence. Quietly does nothing once closed, since observables may fire while a close is
     * already on its way.
     */
    public void push(String path, Object value) {
        if (state != State.SHOWN) {
            return;
        }
        Binding binding = bindings.get(path);
        if (binding == null) {
            throw new IllegalArgumentException("path " + path + " is not bound");
        }
        if (!binding.type.matches(value)) {
            throw new IllegalArgumentException(
                    "path " + path + " carries " + binding.type + ", not " + value.getClass().getSimpleName());
        }
        int pathCount = pathUpdateCounts.merge(path, 1, Integer::sum);
        host.sendUpdate(propertyName, path, value, updateCount, pathCount);
    }

    /**
     * Applies one client write. The path has to be bound and writable, the epoch current, the
     * type right, and the validator willing; anything else drops the write. Returns whether it
     * was applied.
     */
    public boolean handleClientWrite(String path, Object value, int clientUpdateCount, int clientPathUpdateCount) {
        if (state != State.SHOWN) {
            return false;
        }
        Binding binding = bindings.get(path);
        if (binding == null || !binding.clientWritable) {
            return false;
        }
        if (clientUpdateCount != updateCount) {
            // A write from an epoch this property no longer lives in.
            return false;
        }
        if (!binding.type.matches(value)) {
            return false;
        }
        if (value instanceof Double && !Double.isFinite((Double) value)) {
            return false;
        }
        Object normalized = binding.validator.normalize(value);
        if (normalized == null) {
            return false;
        }
        // The client counts its own writes; whatever the sequence reaches is where a later push
        // continues from.
        pathUpdateCounts.merge(path, clientPathUpdateCount, Math::max);
        binding.handler.handle(normalized);
        return true;
    }

    /** Closes the screen from the server: the close packet, then the property teardown. */
    public void close() {
        if (state != State.SHOWN) {
            state = State.CLOSED;
            return;
        }
        state = State.CLOSED;
        host.sendCloseScreen(formId);
        host.sendTeardown(propertyName, ++updateCount);
    }

    /** The client reported this screen closed; the property is still ours to tear down. */
    public void closedByClient() {
        if (state != State.SHOWN) {
            state = State.CLOSED;
            return;
        }
        state = State.CLOSED;
        host.sendTeardown(propertyName, ++updateCount);
    }

    /** Forgets the screen without sending anything, for when the connection is gone. */
    public void discard() {
        state = State.CLOSED;
    }

    private void requireNew() {
        if (state != State.NEW) {
            throw new IllegalStateException("this session has already been shown");
        }
    }

    private static final class Binding {
        private final ValueType type;
        private final boolean clientWritable;
        private final Validator validator;
        private final WriteHandler handler;

        private Binding(ValueType type, boolean clientWritable, Validator validator, WriteHandler handler) {
            this.type = Objects.requireNonNull(type, "type");
            this.clientWritable = clientWritable;
            this.validator = Objects.requireNonNull(validator, "validator");
            this.handler = Objects.requireNonNull(handler, "handler");
        }
    }
}
