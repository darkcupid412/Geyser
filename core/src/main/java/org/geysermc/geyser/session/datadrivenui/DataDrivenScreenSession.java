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

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 *       first edit. The counts are unsigned and only climb, so a write at or below what a path
 *       has already had is a repeat and is dropped; a resent button packet must not press the
 *       button twice.
 *   <li>A property name is never reused. Vanilla keeps a property's counters after its removal,
 *       so a reused name would inherit them; every showing gets a fresh instance id, and the
 *       property carries its unsigned rendering.
 * </ul>
 */
final class DataDrivenScreenSession {

    /** The unsigned maximum, which the schema excludes: its stated maximum stops one short. */
    private static final int COUNT_EXHAUSTED = -1;

    /** The longest string the client accepts in a data store value. */
    private static final int MAX_STRING_LENGTH = 5000;

    /** How a session gets its packets out; implemented over the Bedrock connection. */
    interface Host {
        /** Seeds the property with the full tree, opening its epoch, before the screen is shown. */
        void sendSeed(String property, Map<String, Object> tree, int updateCount);

        /** Tells the client to show the screen reading that property. */
        void sendShow(String screenId, int formId, int dataInstanceId);

        /** Pushes one path's new value into the open screen. */
        void sendUpdate(String property, String path, Object value, int updateCount, int pathUpdateCount);

        /** Tells the client to close this screen. */
        void sendCloseScreen(int formId);

        /** Tears the property down: a change to null at the given epoch. */
        void sendTeardown(String property, int updateCount);
    }

    /**
     * What a client write to a bound path flows into, after validation. Only client writes reach
     * it, never pushes, which is what makes it the place for anything inbound-only; it gets the
     * raw value too, because that is what the client is showing.
     */
    interface WriteHandler {
        void handle(Object rawValue, Object normalized);
    }

    /**
     * Checks and normalizes a client-written value. Returns what the write should become (a
     * slider write clamps into its range, for example), or null to drop the write entirely.
     */
    interface Validator {
        Validator ANY = value -> value;

        @Nullable Object normalize(Object value);
    }

    /** The primitive types a path can carry; the update packet knows exactly these three. */
    enum ValueType {
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
        /** Close sent, waiting for the client to report the screen closed before tearing down. */
        CLOSE_REQUESTED,
        CLOSED
    }

    private final Host host;
    private final String screenId;
    private final String propertyName;
    private final int formId;
    private final int dataInstanceId;
    private final Map<String, Object> seed = new LinkedHashMap<>();
    private final Map<String, Binding> bindings = new HashMap<>();
    /**
     * The highest count each path has reached, whichever side moved it there. One sequence per
     * path means one floor: a count at or below this has already been spent, whether it was spent
     * by a push or by the client.
     */
    private final Map<String, Integer> pathUpdateCounts = new HashMap<>();
    private int updateCount = 1;
    private boolean exhausted;
    private State state = State.NEW;

    DataDrivenScreenSession(Host host, String screenId, String propertyBase, int formId, int dataInstanceId) {
        this.host = Objects.requireNonNull(host, "host");
        this.screenId = Objects.requireNonNull(screenId, "screenId");
        this.formId = formId;
        this.dataInstanceId = dataInstanceId;
        // The instance id travels as a signed int but names the property in its unsigned form.
        this.propertyName = propertyBase + "_" + Integer.toUnsignedString(dataInstanceId);
    }

    int formId() {
        return formId;
    }

    String propertyName() {
        return propertyName;
    }

    boolean closed() {
        return state == State.CLOSED;
    }

    /**
     * Sets the tree the property is seeded with. Only possible before the screen is shown. Values
     * the client cannot hold are refused here the way {@link #push} refuses them, but by throwing:
     * a bad value in the seed takes the whole property down, and unlike a push there is a caller
     * to tell.
     */
    void seed(Map<String, Object> tree) {
        requireNew();
        checkTree("", tree);
        seed.putAll(tree);
    }

    private static void checkTree(String path, Map<String, Object> tree) {
        for (Map.Entry<String, Object> entry : tree.entrySet()) {
            checkValue(path + entry.getKey(), entry.getValue());
        }
    }

    private static void checkValue(String path, Object value) {
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> child = (Map<String, Object>) value;
            checkTree(path + ".", child);
        } else if (value instanceof List) {
            // Rawtext parts arrive as lists. Text's own limits already cap everything a list can
            // hold, but the guarantee here must not depend on a different file keeping its.
            for (Object element : (List<?>) value) {
                checkValue(path, element);
            }
        } else if (!acceptable(value)) {
            throw new IllegalArgumentException("the client cannot hold the seeded value at " + path);
        }
    }

    /**
     * Allows one path to exist: outbound pushes may target it, and when {@code clientWritable}
     * the client may write it. Every other path the client writes is dropped. The vanilla server
     * keeps the same kind of per-path allowlist.
     */
    void bind(String path, ValueType type, boolean clientWritable, Validator validator, WriteHandler handler) {
        requireNew();
        Objects.requireNonNull(path, "path");
        if (bindings.putIfAbsent(path, new Binding(type, clientWritable, validator, handler)) != null) {
            throw new IllegalArgumentException("path " + path + " is already bound");
        }
    }

    /** Seeds the property and shows the screen. A session shows exactly once. */
    void show() {
        requireNew();
        state = State.SHOWN;
        host.sendSeed(propertyName, seed, updateCount);
        host.sendShow(screenId, formId, dataInstanceId);
    }

    /**
     * Pushes a new value for a bound path into the open screen, continuing the path's shared
     * sequence. Quietly does nothing once closed, since observables may fire while a close is
     * already on its way. Returns whether an update actually went out.
     */
    boolean push(String path, Object value) {
        if (state != State.SHOWN) {
            return false;
        }
        Binding binding = bindings.get(path);
        if (binding == null) {
            throw new IllegalArgumentException("path " + path + " is not bound");
        }
        if (!binding.type.matches(value)) {
            throw new IllegalArgumentException(
                    "path " + path + " carries " + binding.type + ", not " + value.getClass().getSimpleName());
        }
        // A value the server sends goes through the same normalization a value from the client
        // does, and what was normalized is what is sent: a form is driven by whatever a plugin
        // puts in its observables, so it can hold a string too long for the client or a number
        // outside the range the component was built with, and the client rejects the whole
        // property rather than the one bad value.
        Object normalized = binding.validator.normalize(value);
        if (normalized == null || !acceptable(normalized)) {
            return false;
        }
        int pathCount = pathUpdateCounts.getOrDefault(path, 0) + 1;
        if (pathCount == COUNT_EXHAUSTED) {
            // The schema stops one short of the unsigned maximum, and there is no way to keep
            // counting past it. The stored count stays where it was, so later pushes are refused
            // here again rather than wrapping the sequence around.
            exhausted = true;
            return false;
        }
        pathUpdateCounts.put(path, pathCount);
        host.sendUpdate(propertyName, path, normalized, updateCount, pathCount);
        return true;
    }

    /**
     * Applies one client write. The path has to be bound and writable, the epoch current, the
     * count newer than anything already seen for that path, the type right, and the validator
     * willing; anything else drops the write. Returns whether it was applied.
     */
    boolean handleClientWrite(String path, Object value, int clientUpdateCount, int clientPathUpdateCount) {
        if (state != State.SHOWN) {
            return false;
        }
        Binding binding = bindings.get(path);
        if (binding == null) {
            return false;
        }
        if (clientUpdateCount != updateCount) {
            // A write from an epoch this property no longer lives in.
            return false;
        }
        if (clientPathUpdateCount == COUNT_EXHAUSTED) {
            // The schema excludes this count; taking it would leave the sequence nowhere to go.
            return false;
        }
        Integer spent = pathUpdateCounts.get(path);
        if (spent != null && Integer.compareUnsigned(clientPathUpdateCount, spent) <= 0) {
            // Counts only ever climb, so anything at or below what this path has already reached
            // is a repeat, including a count a server push just used. Acting on one would press a
            // button twice.
            return false;
        }
        if (!binding.clientWritable) {
            // The client sequences only what it may write. Its count for anything else is not
            // part of that path's sequence, and taking it would let one packet pin a push-only
            // path at the ceiling and exhaust the screen.
            return false;
        }
        // Within a path it does write, the client's sequence moved whether or not the value is
        // taken, so the floor rises before the value is judged; a push after a dropped write must
        // still land ahead of it.
        pathUpdateCounts.put(path, clientPathUpdateCount);
        if (!binding.type.matches(value)) {
            return false;
        }
        Object normalized = binding.validator.normalize(value);
        // What the validator made of the value faces the same holdability check either way round.
        if (normalized == null || !acceptable(value) || !acceptable(normalized)) {
            return false;
        }
        binding.handler.handle(value, normalized);
        return true;
    }

    /**
     * Whether a path's count has run out. The screen has to be closed and shown again; nothing
     * more can be pushed to it.
     */
    boolean exhausted() {
        return exhausted;
    }

    /**
     * Asks the client to close the screen. The property is torn down once the client reports it
     * closed, which is the order vanilla uses; {@link #closedByClient()} finishes the job.
     */
    void close() {
        if (state != State.SHOWN) {
            if (state == State.NEW) {
                state = State.CLOSED;
            }
            return;
        }
        state = State.CLOSE_REQUESTED;
        host.sendCloseScreen(formId);
    }

    /** The client reported this screen closed; the property is still ours to tear down. */
    void closedByClient() {
        if (state == State.CLOSED) {
            return;
        }
        boolean shown = state != State.NEW;
        state = State.CLOSED;
        if (shown) {
            host.sendTeardown(propertyName, ++updateCount);
        }
    }

    /** Whether a close was asked for and the client has not reported back yet. */
    boolean closing() {
        return state == State.CLOSE_REQUESTED;
    }

    /** Forgets the screen without sending anything, for when the connection is gone. */
    void discard() {
        state = State.CLOSED;
    }

    /** Whether a value is one the client can hold at all, whichever direction it came from. */
    private static boolean acceptable(Object value) {
        if (value instanceof Double) {
            return Double.isFinite((Double) value);
        }
        if (value instanceof String) {
            return ((String) value).length() <= MAX_STRING_LENGTH;
        }
        return true;
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
