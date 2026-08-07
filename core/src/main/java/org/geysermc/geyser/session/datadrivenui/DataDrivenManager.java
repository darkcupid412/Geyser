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
import org.cloudburstmc.protocol.bedrock.data.datastore.DataStoreChange;
import org.cloudburstmc.protocol.bedrock.data.datastore.DataStoreUpdate;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataDrivenUICloseScreenPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataDrivenUIShowScreenPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataStorePacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundDataDrivenScreenClosedPacket;
import org.geysermc.cumulus.datadrivenui.DataDrivenCapabilities;
import org.geysermc.cumulus.datadrivenui.DataDrivenCloseReason;
import org.geysermc.cumulus.datadrivenui.DataDrivenForm;
import org.geysermc.cumulus.datadrivenui.DataDrivenMessageBox;
import org.geysermc.cumulus.datadrivenui.DataDrivenSession;
import org.geysermc.cumulus.datadrivenui.component.DataDrivenComponent;
import org.geysermc.geyser.network.GameProtocol;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.util.InventoryUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The data-driven forms of one session: at most one screen open at a time, like the regular form
 * cache, but stateful for as long as the screen stays up.
 *
 * <p>The one piece of behavior that is not a straight mapping is {@code USER_BUSY}. The client
 * refuses to open a screen while any other is up (chat included, which the server cannot see) and
 * reports the refusal through the screen-closed packet, promptly and without showing anything.
 * Regular forms get silently dropped in that spot; this retries a few times with fresh identities
 * (the client already closed the old form id, and a property name is never reused) before giving
 * the form its {@code USER_BUSY} close. That keeps the Java-side assumption that a dialog can
 * always be shown, the same assumption the regular form deferral already protects.
 */
public class DataDrivenManager {
    private static final String DATA_STORE = "minecraft";
    private static final int MAX_BUSY_RETRIES = 3;
    private static final long BUSY_RETRY_MILLIS = 750;
    private static final int MAX_AWAITING_CLOSE = 8;

    /** Forms hold live state, so each instance is shown at most once, across all sessions. */
    private static final Set<Object> CLAIMED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private final GeyserSession session;
    private final AtomicInteger formIds = new AtomicInteger(1);
    /**
     * Names the property each showing writes. Vanilla keeps a property's counters after removal,
     * so a name is never reused; counting up from a random start keeps that true within a
     * connection without making the names predictable across them.
     */
    private final AtomicInteger instanceIds = new AtomicInteger(ThreadLocalRandom.current().nextInt());
    private final WriteBudget writeBudget = new WriteBudget();
    /**
     * Showings that asked the client to close and are waiting for it to say the screen is gone.
     * They are no longer {@link #active} (the next form may already be up) but still own their form
     * id, which is how the acknowledgement finds them again.
     */
    private final Map<Integer, Showing> closing = new LinkedHashMap<>();
    private volatile @Nullable Showing active;
    private boolean shutdown;

    public DataDrivenManager(GeyserSession session) {
        this.session = session;
    }

    /**
     * The components this build can put on screen. Every one of them was verified against 1.26.40,
     * which is also the oldest version data-driven forms are sent to, so the answer is the whole
     * list or nothing. Naming them rather than taking whatever the enum holds keeps a component
     * added to Cumulus later from being claimed before it is compiled here.
     */
    private static final Set<DataDrivenComponent.Kind> SUPPORTED_KINDS = Collections.unmodifiableSet(
            EnumSet.of(DataDrivenComponent.Kind.HEADER, DataDrivenComponent.Kind.LABEL, DataDrivenComponent.Kind.DIVIDER,
                    DataDrivenComponent.Kind.SPACER, DataDrivenComponent.Kind.TEXT_FIELD, DataDrivenComponent.Kind.TOGGLE,
                    DataDrivenComponent.Kind.SLIDER, DataDrivenComponent.Kind.DROPDOWN, DataDrivenComponent.Kind.BUTTON));

    public boolean supported() {
        return GameProtocol.is26_40orHigher(session.protocolVersion());
    }

    /** What this session can show, for a caller deciding between a data-driven and regular form. */
    public DataDrivenCapabilities capabilities() {
        return supported() ? DataDrivenCapabilities.of(SUPPORTED_KINDS) : DataDrivenCapabilities.unsupported();
    }

    public @Nullable DataDrivenSession show(DataDrivenForm form) {
        if (!supported()) {
            return null;
        }
        for (DataDrivenComponent component : form.components()) {
            if (!SUPPORTED_KINDS.contains(component.kind())) {
                throw new IllegalArgumentException(
                        "this connection cannot show a " + component.kind() + " component");
            }
        }
        claim(form);
        Showing showing = new Showing(form, null);
        begin(showing);
        return showing;
    }

    public @Nullable DataDrivenSession show(DataDrivenMessageBox box) {
        if (!supported()) {
            return null;
        }
        claim(box);
        Showing showing = new Showing(null, box);
        begin(showing);
        return showing;
    }

    private static void claim(Object form) {
        if (!CLAIMED.add(form)) {
            throw new IllegalStateException("this form has already been shown; build a new one per showing");
        }
    }

    /** Whether a data-driven screen is showing or on its way to the client. */
    public boolean hasScreenOpen() {
        return active != null;
    }

    /** Closes whatever data-driven screen this session has, if any. */
    public void close() {
        Showing showing = active;
        if (showing != null) {
            showing.close();
        }
    }

    private void begin(Showing showing) {
        session.ensureInEventLoop(() -> {
            if (shutdown) {
                showing.disconnected();
                return;
            }
            Showing previous = active;
            active = showing;
            if (previous != null) {
                // The client shows one screen at a time, so a new one replaces whatever was up.
                previous.serverClose();
            }
            // A regular form would keep its own screen; ours has just taken its place.
            session.getFormCache().closeForms();
            showing.attempt();
        });
    }

    /** A client write to the data store; routed to the open screen, dropped everywhere else. */
    public void handleDataStore(@Nullable DataStoreUpdate update) {
        if (update == null || update.getPath() == null || update.getData() == null) {
            return;
        }
        Showing showing = active;
        if (showing == null || showing.engine == null) {
            return;
        }
        if (!DATA_STORE.equals(update.getDataStoreName())
                || !showing.engine.propertyName().equals(update.getProperty())) {
            return;
        }
        if (!writeBudget.allow()) {
            // Dragging a slider is already a burst of writes, so the budget only catches a client
            // sending far more than a player can produce. The screen is closed rather than the
            // writes merely dropped: a client at this rate is not one a form is any use to.
            session.getGeyser().getLogger().debug(session,
                    "Closing a data-driven form after too many writes from the client");
            showing.close();
            return;
        }
        showing.engine.handleClientWrite(
                update.getPath(), update.getData(), update.getUpdateCount(), update.getPathUpdateCount());
        if (showing.engine.exhausted()) {
            // Reaching the ceiling takes more packets than the write budget and the count-jump
            // window allow, so this is the last line rather than the first. A screen that cannot
            // take updates anymore is closed rather than left half-working.
            showing.close();
        }
    }

    /** The client reported a screen closed. */
    public void handleScreenClosed(int formId, ServerboundDataDrivenScreenClosedPacket.@Nullable CloseReason reason) {
        Showing showing = active;
        if (showing != null && showing.engine != null && showing.engine.formId() == formId) {
            if (reason == ServerboundDataDrivenScreenClosedPacket.CloseReason.USER_BUSY) {
                // A refusal to open. Only the screen being shown can be refused; one already on
                // its way out is past the point of being retried.
                showing.busyRefused();
                return;
            }
            active = null;
        } else {
            showing = closing.remove(formId);
            if (showing == null) {
                return;
            }
        }
        // A programmatic reason acknowledges a close we asked for, and is what lets the property
        // be torn down; anything else is the player closing the screen themselves.
        showing.clientClosed(reason == ServerboundDataDrivenScreenClosedPacket.CloseReason.CLIENT_CANCELED
                ? DataDrivenCloseReason.CLIENT_CLOSED
                : DataDrivenCloseReason.SERVER_CLOSED);
    }

    /** Shows the pending screen once the client can accept one; the regular form resend spots call this. */
    public void resendPending() {
        session.ensureInEventLoop(() -> {
            Showing showing = active;
            if (showing != null) {
                showing.showIfPending();
            }
        });
    }

    /**
     * The connection is gone. Nothing can be sent anymore, and a showing that was still being set
     * up must not start one; the flag is read by every transition, which all run on the session
     * thread.
     */
    public void disconnected() {
        session.ensureInEventLoop(() -> {
            shutdown = true;
            Showing showing = active;
            active = null;
            if (showing != null) {
                showing.disconnected();
            }
            List<Showing> waiting = new ArrayList<>(closing.values());
            closing.clear();
            for (Showing pending : waiting) {
                pending.disconnected();
            }
        });
    }

    /**
     * How many writes a client may send. A player dragging a slider produces a steady stream of
     * them, so the allowance is generous and only a client sending more than a person could
     * reaches it.
     */
    private static final class WriteBudget {
        private static final int PER_SECOND = 200;

        private long windowStartedAt;
        private int writes;

        private boolean allow() {
            long now = System.nanoTime();
            if (now - windowStartedAt >= TimeUnit.SECONDS.toNanos(1)) {
                windowStartedAt = now;
                writes = 0;
            }
            return ++writes <= PER_SECOND;
        }
    }

    private final class Showing implements DataDrivenSession {
        private final @Nullable DataDrivenForm form;
        private final @Nullable DataDrivenMessageBox box;
        private final AtomicBoolean closeReported = new AtomicBoolean();
        private @Nullable DataDrivenScreenSession engine;
        private DataDrivenTreeCompiler.@Nullable Compiled compiled;
        private @Nullable Integer lastClicked;
        private int busyRetries;
        private boolean pendingShow;
        private volatile boolean done;

        private Showing(@Nullable DataDrivenForm form, @Nullable DataDrivenMessageBox box) {
            this.form = form;
            this.box = box;
        }

        /**
         * Shows the screen, or waits for the moment the client will accept one. An open inventory
         * makes the client refuse, so it is closed first, the same thing a regular form does
         * before it sends.
         */
        private void attempt() {
            if (done) {
                return;
            }
            discardAttempt();
            if (session.getInventoryHolder() != null) {
                InventoryUtils.sendJavaContainerClose(session.getInventoryHolder());
                InventoryUtils.closeInventory(session, session.getInventoryHolder(), true);
            }
            if (ready()) {
                try {
                    buildAndShow();
                } catch (Throwable throwable) {
                    failed(throwable);
                }
            } else {
                // Sent when the client confirms the inventory closed, or when it initializes, the
                // same moments the regular form cache resends at.
                pendingShow = true;
            }
        }

        /** The one way a showing ends because it could not be built. */
        private void failed(Throwable throwable) {
            session.getGeyser().getLogger().error("Could not show a data-driven form", throwable);
            if (active == this) {
                active = null;
            }
            // The engine may have seeded the property before it threw, so it is torn down rather
            // than left behind on the client.
            if (engine != null) {
                engine.closedByClient();
            }
            discardAttempt();
            finish(DataDrivenCloseReason.SERVER_CLOSED);
        }

        private void showIfPending() {
            if (done || !pendingShow || !ready()) {
                return;
            }
            pendingShow = false;
            // The same failure the first attempt guards against: compiling reads whatever the
            // form holds now, and a value it cannot show throws here just as readily.
            try {
                buildAndShow();
            } catch (Throwable throwable) {
                failed(throwable);
            }
        }

        private boolean ready() {
            return session.getUpstream().isInitialized() && !session.isClosingInventory();
        }

        /**
         * Builds the screen and sends it. The form is compiled here rather than when the showing
         * was asked for, so a screen that waited for the client reads the values the form holds
         * now instead of the ones it held while waiting.
         */
        private void buildAndShow() {
            String screenId = form != null ? "minecraft:custom_form" : "minecraft:message_box";
            String propertyBase = form != null ? "custom_form_data" : "message_box_data";
            engine = new DataDrivenScreenSession(new PacketHost(), screenId, propertyBase,
                    formIds.getAndIncrement(), instanceIds.getAndIncrement());
            if (form != null) {
                compiled = DataDrivenTreeCompiler.compile(form, engine, session::ensureInEventLoop);
            } else {
                compiled = DataDrivenTreeCompiler.compile(box, engine, session::ensureInEventLoop,
                        button -> lastClicked = button);
            }
            engine.show();
            pendingShow = false;
        }

        /** Drops whatever a previous attempt built, so its observables stop being watched. */
        private void discardAttempt() {
            if (compiled != null) {
                compiled.close();
                compiled = null;
            }
            engine = null;
        }

        private void busyRefused() {
            if (done || engine == null) {
                return;
            }
            engine.closedByClient(); // vanilla tears the refused property down too
            if (busyRetries < MAX_BUSY_RETRIES) {
                busyRetries++;
                long delay = BUSY_RETRY_MILLIS * busyRetries;
                session.scheduleInEventLoop(this::attempt, delay, TimeUnit.MILLISECONDS);
                return;
            }
            if (active == this) {
                active = null;
            }
            finish(DataDrivenCloseReason.USER_BUSY);
        }

        private void clientClosed(DataDrivenCloseReason reason) {
            if (engine != null) {
                engine.closedByClient();
            }
            finish(reason);
        }

        private void serverClose() {
            if (engine == null) {
                finish(DataDrivenCloseReason.SERVER_CLOSED);
                return;
            }
            engine.close();
            if (engine.closing()) {
                // The teardown and the close handler wait for the client's acknowledgement, which
                // arrives as a screen-closed packet with a programmatic reason. A client that never
                // sends one would hold the showing forever, so the oldest is given up on once more
                // than a handful are outstanding.
                if (closing.size() >= MAX_AWAITING_CLOSE) {
                    Iterator<Showing> oldest = closing.values().iterator();
                    Showing abandoned = oldest.next();
                    oldest.remove();
                    // Given up on, so the property it seeded has to go with it; waiting for an
                    // acknowledgement that is never coming would leave it on the client forever.
                    abandoned.giveUp();
                }
                closing.put(engine.formId(), this);
                // Closed as far as the caller is concerned; the teardown, and with it the
                // subscriptions finish() releases, wait for the acknowledgement.
                done = true;
                return;
            }
            finish(DataDrivenCloseReason.SERVER_CLOSED);
        }

        /** Stops waiting for an acknowledgement, and tears down what was left behind. */
        private void giveUp() {
            if (engine != null) {
                engine.closedByClient();
            }
            finish(DataDrivenCloseReason.SERVER_CLOSED);
        }

        private void disconnected() {
            if (engine != null) {
                engine.discard();
            }
            finish(DataDrivenCloseReason.SERVER_CLOSED);
        }

        private void finish(DataDrivenCloseReason reason) {
            done = true;
            if (compiled != null) {
                compiled.close();
            }
            if (!closeReported.compareAndSet(false, true)) {
                return;
            }
            try {
                if (form != null) {
                    Consumer<DataDrivenCloseReason> handler = form.closeHandler();
                    if (handler != null) {
                        handler.accept(reason);
                    }
                } else if (box != null) {
                    Consumer<DataDrivenMessageBox.Result> handler = box.resultHandler();
                    if (handler != null) {
                        Integer selection = reason == DataDrivenCloseReason.CLIENT_CLOSED ? lastClicked : null;
                        handler.accept(DataDrivenMessageBox.Result.of(selection, reason));
                    }
                }
            } catch (Throwable throwable) {
                session.getGeyser().getLogger().error("Error in a data-driven form close handler", throwable);
            }
        }

        @Override
        public boolean open() {
            return !done;
        }

        @Override
        public void close() {
            session.ensureInEventLoop(() -> {
                if (done) {
                    return;
                }
                if (active == this) {
                    active = null;
                }
                serverClose();
            });
        }
    }

    /** The engine's sends, as actual packets on this session. */
    private final class PacketHost implements DataDrivenScreenSession.Host {
        @Override
        public void sendSeed(String property, Map<String, Object> tree, int updateCount) {
            ClientboundDataStorePacket packet = new ClientboundDataStorePacket();
            DataStoreChange change = new DataStoreChange();
            change.setDataStoreName(DATA_STORE);
            change.setProperty(property);
            change.setUpdateCount(updateCount);
            change.setNewValue(tree);
            packet.setUpdates(Collections.singletonList(change));
            session.sendUpstreamPacket(packet);
        }

        @Override
        public void sendShow(String screenId, int formId, int dataInstanceId) {
            ClientboundDataDrivenUIShowScreenPacket packet = new ClientboundDataDrivenUIShowScreenPacket();
            packet.setScreenId(screenId);
            packet.setFormId(formId);
            packet.setDataInstanceId(dataInstanceId);
            session.sendUpstreamPacket(packet);
        }

        @Override
        public void sendUpdate(String property, String path, Object value, int updateCount, int pathUpdateCount) {
            ClientboundDataStorePacket packet = new ClientboundDataStorePacket();
            DataStoreUpdate update = new DataStoreUpdate();
            update.setDataStoreName(DATA_STORE);
            update.setProperty(property);
            update.setPath(path);
            update.setData(value);
            update.setUpdateCount(updateCount);
            update.setPathUpdateCount(pathUpdateCount);
            packet.setUpdates(Collections.singletonList(update));
            session.sendUpstreamPacket(packet);
        }

        @Override
        public void sendCloseScreen(int formId) {
            ClientboundDataDrivenUICloseScreenPacket packet = new ClientboundDataDrivenUICloseScreenPacket();
            packet.setFormId(formId);
            session.sendUpstreamPacket(packet);
        }

        @Override
        public void sendTeardown(String property, int updateCount) {
            ClientboundDataStorePacket packet = new ClientboundDataStorePacket();
            DataStoreChange change = new DataStoreChange();
            change.setDataStoreName(DATA_STORE);
            change.setProperty(property);
            change.setUpdateCount(updateCount);
            change.setNewValue(null);
            packet.setUpdates(Collections.singletonList(change));
            session.sendUpstreamPacket(packet);
        }
    }
}
