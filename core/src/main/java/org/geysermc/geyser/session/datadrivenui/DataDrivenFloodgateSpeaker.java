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
import org.geysermc.cumulus.datadrivenui.DataDrivenCapabilities;
import org.geysermc.cumulus.datadrivenui.DataDrivenCloseReason;
import org.geysermc.cumulus.datadrivenui.DataDrivenForm;
import org.geysermc.cumulus.datadrivenui.DataDrivenMessageBox;
import org.geysermc.cumulus.datadrivenui.DataDrivenSession;
import org.geysermc.cumulus.datadrivenui.codec.DataDrivenCodec;
import org.geysermc.cumulus.datadrivenui.codec.DataDrivenDecodeException;
import org.geysermc.cumulus.datadrivenui.codec.DataDrivenProperty;
import org.geysermc.cumulus.datadrivenui.component.Button;
import org.geysermc.cumulus.datadrivenui.component.DataDrivenComponent;
import org.geysermc.cumulus.datadrivenui.component.Slider;
import org.geysermc.cumulus.datadrivenui.util.Observable;
import org.geysermc.cumulus.datadrivenui.util.Text;
import org.geysermc.cumulus.datadrivenui.util.Value;
import org.geysermc.geyser.session.GeyserSession;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Geyser end of Floodgate's {@code floodgate:ddui} channel: a backend describes a form, this
 * shows it, and what the player does goes back as events.
 *
 * <p>The backend holds the real form and its callbacks, so the copy decoded here is a stand-in
 * whose observables exist only to be driven from either side. A press or an edit is reported
 * rather than acted on; the backend's own copy runs the callback.
 *
 * <p>Everything here runs on the session thread, which is where the plugin-message translator
 * hands work off, and where the screen session expects to be touched from.
 */
public final class DataDrivenFloodgateSpeaker {
    private static final int ENVELOPE_VERSION = 1;

    private static final int OPCODE_CAPS_REQUEST = 0;
    private static final int OPCODE_CAPS = 1;
    private static final int OPCODE_OPEN = 2;
    private static final int OPCODE_PUSH = 3;
    private static final int OPCODE_EVENT = 4;
    private static final int OPCODE_CLOSE = 5;

    private static final int HEADER_LENGTH = 1 + 1 + 4;

    private final GeyserSession session;
    /**
     * Screens a backend asked for. Keyed by epoch and session id together: a backend hands out
     * ids from zero again after a switch, so an id alone does not say which generation a message
     * belongs to, and a late one would otherwise land on whatever has since reused it.
     *
     * <p>Insertion-ordered, because reaching {@link #MAX_SHOWINGS} evicts the front of it and the
     * one worth giving up is the one that has been open longest.
     */
    private final Map<Long, Showing> showings = new LinkedHashMap<>();

    public DataDrivenFloodgateSpeaker(GeyserSession session) {
        this.session = session;
    }

    /** Handles one message from the backend; an opcode this build does not know is logged. */
    public void handle(byte[] data, Sender sender) {
        if (data.length < HEADER_LENGTH) {
            return;
        }
        int opcode = data[0] & 0xFF;
        int epoch = data[1] & 0xFF;
        int sessionId = sessionId(data);

        switch (opcode) {
            case OPCODE_CAPS_REQUEST -> sendCapabilities(sender, epoch);
            case OPCODE_OPEN -> open(data, epoch, sessionId, sender);
            case OPCODE_PUSH -> push(data, key(epoch, sessionId));
            case OPCODE_CLOSE -> close(epoch, sessionId);
            default -> session.getGeyser().getLogger().debug(session,
                    "Unknown data-driven form opcode " + opcode + " from the server");
        }
    }

    /** The connection is gone; the map only drops, since nothing can be sent anymore. */
    public void reset() {
        showings.clear();
    }

    /**
     * Answers with the envelope and codec this build speaks, and the components it can put on
     * screen. A backend that hears nothing keeps using regular forms.
     */
    private void sendCapabilities(Sender sender, int epoch) {
        DataDrivenCapabilities capabilities = session.getDataDrivenManager().capabilities();
        int mask = 0;
        for (DataDrivenComponent.Kind kind : DataDrivenComponent.Kind.values()) {
            if (capabilities.supports(kind)) {
                mask |= 1 << kind.ordinal();
            }
        }
        byte[] message = new byte[HEADER_LENGTH + 6];
        writeHeader(message, OPCODE_CAPS, epoch, 0);
        message[HEADER_LENGTH] = (byte) ENVELOPE_VERSION;
        message[HEADER_LENGTH + 1] = (byte) DataDrivenCodec.CODEC_VERSION;
        message[HEADER_LENGTH + 2] = (byte) (mask >> 24);
        message[HEADER_LENGTH + 3] = (byte) (mask >> 16);
        message[HEADER_LENGTH + 4] = (byte) (mask >> 8);
        message[HEADER_LENGTH + 5] = (byte) mask;
        sender.send(message);
    }

    /** More outstanding backend screens than this and the oldest is given up on. */
    private static final int MAX_SHOWINGS = 16;

    private void open(byte[] data, int epoch, int sessionId, Sender sender) {
        if (sessionId == 0) {
            // Reserved: it addresses every session at once in a close, so nothing may own it.
            return;
        }
        byte[] payload = new byte[data.length - HEADER_LENGTH];
        System.arraycopy(data, HEADER_LENGTH, payload, 0, payload.length);

        // A backend whose Floodgate restarted hands out the same epochs and ids again, and no
        // proxy may exist to sweep the old generation; whatever held this key is gone either way.
        Showing collided = showings.remove(key(epoch, sessionId));
        if (collided != null) {
            collided.closeFromServer();
        }
        if (showings.size() >= MAX_SHOWINGS) {
            Long oldest = showings.keySet().iterator().next();
            showings.remove(oldest).closeFromServer();
        }

        Showing showing = new Showing(sessionId, epoch, sender);
        DataDrivenCodec.Decoded decoded;
        try {
            decoded = DataDrivenCodec.decode(payload, showing);
        } catch (DataDrivenDecodeException exception) {
            session.getGeyser().getLogger().debug(session,
                    "Could not decode a data-driven form from the server: " + exception.getMessage());
            // Silence would look to the backend like a form that never closes.
            showing.reportClose(DataDrivenCloseReason.SERVER_CLOSED);
            return;
        }

        // Registered before showing: a form that dies while being shown reports through its
        // close handler, which is this showing's reportClose, and that removal must find the
        // entry it is removing.
        showings.put(key(epoch, sessionId), showing);
        DataDrivenSession opened;
        try {
            opened = decoded.form() != null
                    ? session.getDataDrivenManager().show(decoded.form())
                    : session.getDataDrivenManager().show(decoded.messageBox());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            // A component this client cannot show, or a form that was somehow shown before: the
            // backend's mistake, not a reason to blow up the packet pipeline.
            session.getGeyser().getLogger().debug(session,
                    "Refused a data-driven form from the server: " + exception.getMessage());
            opened = null;
        }
        if (opened == null) {
            showings.remove(key(epoch, sessionId));
            showing.reportClose(DataDrivenCloseReason.SERVER_CLOSED);
            return;
        }
        if (!opened.open()) {
            // Died while being shown; its close handler already reported and removed it.
            return;
        }
        showing.bind(decoded, opened);
    }

    private void push(byte[] data, long key) {
        Showing showing = showings.get(key);
        if (showing == null) {
            return;
        }
        try {
            showing.applyPush(data);
        } catch (Exception exception) {
            session.getGeyser().getLogger().debug(session,
                    "Could not apply a data-driven form update: " + exception.getMessage());
        }
    }

    private void close(int epoch, int sessionId) {
        if (sessionId == 0) {
            // A backend switch: everything the old one opened goes, whatever it was called.
            List<Showing> gone = new ArrayList<>(showings.values());
            showings.clear();
            for (Showing showing : gone) {
                showing.closeFromServer();
            }
            return;
        }
        Showing showing = showings.remove(key(epoch, sessionId));
        if (showing != null) {
            showing.closeFromServer();
        }
    }

    private static long key(int epoch, int sessionId) {
        return (long) epoch << 32 | sessionId & 0xFFFFFFFFL;
    }

    /** How a message leaves; the translator supplies the downstream write. */
    public interface Sender {
        void send(byte[] data);
    }

    /**
     * The wire id of a close reason. Fixed rather than the ordinal: Cumulus keeps three reasons
     * where Bedrock names five, so this enum will grow, and an inserted constant must not
     * silently reinterpret messages already in flight. Floodgate reads the same mapping.
     */
    private static int closeReasonId(DataDrivenCloseReason reason) {
        switch (reason) {
            case CLIENT_CLOSED:
                return 0;
            case SERVER_CLOSED:
                return 1;
            case USER_BUSY:
                return 2;
            default:
                throw new AssertionError("unknown close reason " + reason);
        }
    }

    private static int sessionId(byte[] data) {
        return (data[2] & 0xFF) << 24
                | (data[3] & 0xFF) << 16
                | (data[4] & 0xFF) << 8
                | data[5] & 0xFF;
    }

    private static void writeHeader(byte[] data, int opcode, int epoch, int sessionId) {
        data[0] = (byte) opcode;
        data[1] = (byte) epoch;
        data[2] = (byte) (sessionId >> 24);
        data[3] = (byte) (sessionId >> 16);
        data[4] = (byte) (sessionId >> 8);
        data[5] = (byte) sessionId;
    }

    /**
     * One backend-owned screen. It is the decoder's callback source too: a press on the decoded
     * copy means telling the backend, whose copy holds the real callback.
     */
    private final class Showing implements DataDrivenCodec.Callbacks {
        private final int sessionId;
        private final int epoch;
        private final Sender sender;
        private final List<Observable.Subscription> subscriptions = new ArrayList<>();
        private final Map<Integer, Observable<?>> values = new HashMap<>();
        /** What the backend last sent for a value, so its own change is not reported back to it. */
        private final Map<Integer, Object> fromServer = new HashMap<>();
        private @Nullable DataDrivenForm decodedForm;
        private @Nullable DataDrivenMessageBox decodedBox;
        private @Nullable DataDrivenSession opened;
        private boolean reported;

        private Showing(int sessionId, int epoch, Sender sender) {
            this.sessionId = sessionId;
            this.epoch = epoch;
            this.sender = sender;
        }

        @Override
        public Runnable buttonClicked(int componentIndex) {
            return () -> event(componentIndex, DataDrivenProperty.CLICK, null);
        }

        @Override
        public Consumer<DataDrivenCloseReason> formClosed() {
            return this::reportClose;
        }

        @Override
        public Consumer<DataDrivenMessageBox.Result> messageBoxClosed() {
            return result -> {
                if (result.selection() != null) {
                    event(result.selection(), DataDrivenProperty.CLICK, null);
                }
                reportClose(result.reason());
            };
        }

        /**
         * Watches the decoded copy for the player's edits. Only a component's own value can change
         * from this side; everything else moves the other way.
         */
        private void bind(DataDrivenCodec.Decoded decoded, DataDrivenSession opened) {
            this.opened = opened;
            this.decodedBox = decoded.messageBox();
            DataDrivenForm form = decoded.form();
            this.decodedForm = form;
            if (form == null) {
                return;
            }
            List<DataDrivenComponent> components = form.components();
            for (int index = 0; index < components.size(); index++) {
                if (components.get(index) instanceof DataDrivenComponent.Input<?> input) {
                    int componentIndex = index;
                    Observable<?> value = input.value();
                    values.put(componentIndex, value);
                    subscriptions.add(value.subscribe(changed -> {
                        if (changed.equals(fromServer.remove(componentIndex))) {
                            // This is the backend's own push arriving back; only the player's
                            // changes are events.
                            return;
                        }
                        event(componentIndex, DataDrivenProperty.VALUE, changed);
                    }));
                }
            }
        }

        /** A push from the backend, applied to the decoded copy so the screen follows it. */
        private void applyPush(byte[] data) throws IOException, DataDrivenDecodeException {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(
                    data, HEADER_LENGTH, data.length - HEADER_LENGTH));
            int componentIndex = in.readShort();
            DataDrivenProperty property = DataDrivenProperty.fromId(in.readUnsignedByte());
            if (property == null) {
                return;
            }
            Object value = DataDrivenCodec.readValue(in);
            apply(componentIndex, property, value);
        }

        /** Whether a decoded value is the kind the target holds, since the payload chose it. */
        private static boolean fits(@Nullable Object current, Object value) {
            if (current == null) {
                return true;
            }
            if (current instanceof Text) {
                // Text's variants are different classes; a literal label may become translatable.
                return value instanceof Text;
            }
            return current.getClass().isInstance(value);
        }

        @SuppressWarnings("unchecked")
        private void apply(int componentIndex, DataDrivenProperty property, Object value) {
            if (property == DataDrivenProperty.VALUE) {
                Observable<?> target = values.get(componentIndex);
                if (target != null) {
                    if (!fits(target.get(), value)) {
                        return;
                    }
                    // The mark-and-set only suppresses the echo because everything that sets a
                    // decoded observable runs on this session thread, so the notification lands
                    // before set returns. A second setter thread would break it.
                    fromServer.put(componentIndex, value);
                    ((Observable<Object>) target).set(value);
                    // An equal value notifies nobody, so nothing consumed the mark.
                    fromServer.remove(componentIndex);
                }
                return;
            }
            Observable<Object> target = (Observable<Object>) observableFor(componentIndex, property);
            if (target != null && fits(target.get(), value)) {
                target.set(value);
            }
        }

        /**
         * The observable behind one of a component's other properties. Every property of a decoded
         * form is observable-backed, which is what lets a push move any of them.
         */
        private @Nullable Observable<?> observableFor(int componentIndex, DataDrivenProperty property) {
            DataDrivenMessageBox box = decodedBox;
            if (box != null) {
                // A message box has no components; every reactive field belongs to the box itself.
                switch (property) {
                    case TITLE:
                        return box.title().observable();
                    case BODY:
                        return box.body().observable();
                    case BUTTON1:
                        return box.button1().observable();
                    case BUTTON2:
                        return box.button2().observable();
                    case BUTTON1_TOOLTIP:
                        return observableOf(box.button1Tooltip());
                    case BUTTON2_TOOLTIP:
                        return observableOf(box.button2Tooltip());
                    default:
                        return null;
                }
            }
            DataDrivenForm form = decodedForm;
            if (form == null) {
                return null;
            }
            if (componentIndex < 0) {
                return property == DataDrivenProperty.TITLE ? form.title().observable() : null;
            }
            List<DataDrivenComponent> components = form.components();
            if (componentIndex >= components.size()) {
                return null;
            }
            DataDrivenComponent component = components.get(componentIndex);
            switch (property) {
                case VISIBLE:
                    return component.visible().observable();
                case TEXT:
                    if (component instanceof DataDrivenComponent.Textual textual) {
                        return textual.text().observable();
                    }
                    if (component instanceof Button button) {
                        return button.label().observable();
                    }
                    return component instanceof DataDrivenComponent.Input<?> input
                            ? input.label().observable() : null;
                case DESCRIPTION:
                    return component instanceof DataDrivenComponent.Input<?> input
                            ? observableOf(input.description()) : null;
                case DISABLED:
                    if (component instanceof Button button) {
                        return button.disabled().observable();
                    }
                    return component instanceof DataDrivenComponent.Input<?> input
                            ? input.disabled().observable() : null;
                case TOOLTIP:
                    return component instanceof Button button
                            ? observableOf(button.tooltip()) : null;
                case MIN:
                    return component instanceof Slider slider ? slider.min().observable() : null;
                case MAX:
                    return component instanceof Slider slider ? slider.max().observable() : null;
                case STEP:
                    return component instanceof Slider slider && slider.step() != null
                            ? slider.step().observable() : null;
                default:
                    return null;
            }
        }

        private @Nullable Observable<Text> observableOf(@Nullable Value<Text> value) {
            return value == null ? null : value.observable();
        }

        private void event(int componentIndex, DataDrivenProperty property, @Nullable Object value) {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                byte[] header = new byte[HEADER_LENGTH];
                writeHeader(header, OPCODE_EVENT, epoch, sessionId);
                out.write(header);
                out.writeShort(componentIndex);
                out.writeByte(property.id());
                if (value != null) {
                    DataDrivenCodec.writeValue(out, value);
                }
                sender.send(bytes.toByteArray());
            } catch (IOException exception) {
                session.getGeyser().getLogger().debug(session,
                        "Could not encode a data-driven form event: " + exception.getMessage());
            }
        }

        private void closeFromServer() {
            unwatch();
            if (opened != null) {
                opened.close();
            }
        }

        /** Tells the backend the screen is gone, whichever side ended it. */
        private void reportClose(DataDrivenCloseReason reason) {
            if (reported) {
                return;
            }
            reported = true;
            unwatch();
            showings.remove(key(epoch, sessionId));
            byte[] message = new byte[HEADER_LENGTH + 1];
            writeHeader(message, OPCODE_CLOSE, epoch, sessionId);
            message[HEADER_LENGTH] = (byte) closeReasonId(reason);
            sender.send(message);
        }

        private void unwatch() {
            for (Observable.Subscription subscription : subscriptions) {
                subscription.close();
            }
            subscriptions.clear();
        }
    }
}
