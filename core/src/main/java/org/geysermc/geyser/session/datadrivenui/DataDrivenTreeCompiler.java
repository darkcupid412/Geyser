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
import org.geysermc.cumulus.datadrivenui.DataDrivenForm;
import org.geysermc.cumulus.datadrivenui.DataDrivenMessageBox;
import org.geysermc.cumulus.datadrivenui.component.Button;
import org.geysermc.cumulus.datadrivenui.component.DataDrivenComponent;
import org.geysermc.cumulus.datadrivenui.component.Dropdown;
import org.geysermc.cumulus.datadrivenui.component.Slider;
import org.geysermc.cumulus.datadrivenui.util.Observable;
import org.geysermc.cumulus.datadrivenui.util.Text;
import org.geysermc.cumulus.datadrivenui.util.Value;
import org.geysermc.geyser.session.datadrivenui.DataDrivenScreenSession.Validator;
import org.geysermc.geyser.session.datadrivenui.DataDrivenScreenSession.ValueType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

/**
 * Compiles a Cumulus form onto a {@link DataDrivenScreenSession}: the seed tree in the exact shape the
 * client's screen reads, one binding per path that may move afterwards, and the bridge between the
 * form's observables and the session in both directions.
 *
 * <p>The tree shapes are the screen's own, captured from vanilla on 1.26.40. Slots are keyed by
 * their position under {@code layout}, with an int64 {@code length} beside them; every other
 * number in the tree is a double, because that is what the client writes and expects. Each slot
 * carries a {@code <kind>_visible} marker; the close button lives outside {@code layout} and does
 * not shift positions. Text is a plain string when literal, and a {@code rawtext} tree when it
 * needs client-side resolution.
 *
 * <p>The bridge suppresses echoes by remembering, per path, what the client is believed to be
 * showing: a client write flows into the form's observable, whose notification would otherwise
 * flow straight back out as a push, and a change is pushed only when the observable no longer
 * agrees with that remembered value. All of it runs on the session thread.
 *
 * <p>A seed carries a whole tree, but an update packet carries one primitive, so text that changes
 * while the form is open reaches the player only while it stays literal. A change to a translation
 * is kept for the next showing rather than sent, which Cumulus says on {@code Value}.
 */
final class DataDrivenTreeCompiler {

    private final DataDrivenScreenSession session;
    private final Executor onSessionThread;
    private final List<Observable.Subscription> subscriptions = new ArrayList<>();
    private final Map<String, PathSync> syncs = new LinkedHashMap<>();

    private DataDrivenTreeCompiler(DataDrivenScreenSession session, Executor onSessionThread) {
        this.session = session;
        this.onSessionThread = onSessionThread;
    }

    /** What compiling produced: the subscriptions to close when the showing ends. */
    static final class Compiled implements AutoCloseable {
        private final List<Observable.Subscription> subscriptions;

        private Compiled(List<Observable.Subscription> subscriptions) {
            this.subscriptions = subscriptions;
        }

        @Override
        public void close() {
            for (Observable.Subscription subscription : subscriptions) {
                subscription.close();
            }
            subscriptions.clear();
        }
    }

    /**
     * Compiles a form onto the given session, which must be new. The executor runs work on the
     * session's event loop; observable changes arrive from any thread and are marshalled through
     * it before touching the session.
     */
    static Compiled compile(DataDrivenForm form, DataDrivenScreenSession session, Executor onSessionThread) {
        DataDrivenTreeCompiler compiler = new DataDrivenTreeCompiler(session, onSessionThread);
        try {
            compiler.compileForm(form);
        } catch (Throwable throwable) {
            // Whatever was subscribed before the throw must not keep watching the form.
            new Compiled(compiler.subscriptions).close();
            throw throwable;
        }
        return new Compiled(compiler.subscriptions);
    }

    /**
     * Compiles a message box onto the given session. Button presses are reported to {@code
     * clickSink} as 1 or 2; turning that plus the close into a result is the manager's job.
     */
    static Compiled compile(DataDrivenMessageBox box, DataDrivenScreenSession session, Executor onSessionThread,
            IntConsumer clickSink) {
        DataDrivenTreeCompiler compiler = new DataDrivenTreeCompiler(session, onSessionThread);
        try {
            compiler.compileMessageBox(box, clickSink);
        } catch (Throwable throwable) {
            new Compiled(compiler.subscriptions).close();
            throw throwable;
        }
        return new Compiled(compiler.subscriptions);
    }

    /**
     * Records what a path was seeded with, so the first change is measured against what the client
     * was actually shown rather than against nothing.
     */
    private void seeded(String path, @Nullable Object wireValue) {
        PathSync sync = syncs.get(path);
        if (sync != null) {
            sync.seeded(wireValue);
        }
    }

    private void compileForm(DataDrivenForm form) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("title", seedText(form.title().get()));
        bindPushedText("title", form.title());
        seeded("title", pushableText(form.title().get()));

        Map<String, Object> layout = new LinkedHashMap<>();
        List<DataDrivenComponent> components = form.components();
        for (int i = 0; i < components.size(); i++) {
            layout.put(String.valueOf(i), slot(components.get(i), "layout[" + i + "]."));
        }
        layout.put("length", (long) components.size());
        tree.put("layout", layout);

        if (form.hasCloseButton()) {
            Map<String, Object> closeButton = new LinkedHashMap<>();
            closeButton.put("button_visible", true);
            closeButton.put("visible", true);
            // Vanilla sends its own locale's literal here; the translate key renders in the
            // player's language instead, which is what Cumulus promises for this button.
            closeButton.put("label", seedText(Text.translatable("gui.close")));
            closeButton.put("onClick", 0.0d);
            tree.put("closeButton", closeButton);
            // The press already arrives as the screen-closed packet, so the write needs no
            // handler, only permission to exist. No capture shows this write; the binding is
            // inferred from the onClick field vanilla seeds the slot with.
            session.bind("closeButton.onClick", ValueType.DOUBLE, true, Validator.ANY,
                    (raw, normalized) -> { });
        }

        session.seed(tree);
    }

    private Map<String, Object> slot(DataDrivenComponent component, String prefix) {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("visible", component.visible().get());
        bindPushedBoolean(prefix + "visible", component.visible());
        seeded(prefix + "visible", component.visible().get());

        switch (component.kind()) {
            case HEADER:
            case LABEL: {
                DataDrivenComponent.Textual textual = (DataDrivenComponent.Textual) component;
                slot.put(component.kind() == DataDrivenComponent.Kind.HEADER ? "header_visible" : "label_visible", true);
                slot.put("text", seedText(textual.text().get()));
                bindPushedText(prefix + "text", textual.text());
                seeded(prefix + "text", pushableText(textual.text().get()));
                return slot;
            }
            case DIVIDER:
                slot.put("divider_visible", true);
                return slot;
            case SPACER:
                slot.put("spacer_visible", true);
                return slot;
            case TEXT_FIELD: {
                @SuppressWarnings("unchecked")
                DataDrivenComponent.Input<String> input = (DataDrivenComponent.Input<String>) component;
                slot.put("textfield_visible", true);
                inputCommon(slot, input, prefix);
                slot.put("text", input.value().get());
                bridge(prefix + "text", ValueType.STRING, input.value(), Validator.ANY,
                        usable(input));
                seeded(prefix + "text", input.value().get());
                return slot;
            }
            case TOGGLE: {
                @SuppressWarnings("unchecked")
                DataDrivenComponent.Input<Boolean> input = (DataDrivenComponent.Input<Boolean>) component;
                slot.put("toggle_visible", true);
                inputCommon(slot, input, prefix);
                slot.put("toggled", input.value().get());
                bridge(prefix + "toggled", ValueType.BOOLEAN, input.value(), Validator.ANY,
                        usable(input));
                seeded(prefix + "toggled", input.value().get());
                return slot;
            }
            case SLIDER: {
                Slider slider = (Slider) component;
                slot.put("slider_visible", true);
                inputCommon(slot, slider, prefix);
                // The same clamp a write faces: the observable may have moved since build time.
                double seedValue = Math.max(slider.min().get(), Math.min(slider.max().get(), slider.value().get()));
                slot.put("value", seedValue);
                slot.put("minValue", slider.min().get());
                slot.put("maxValue", slider.max().get());
                bindPushedDouble(prefix + "minValue", slider.min());
                bindPushedDouble(prefix + "maxValue", slider.max());
                seeded(prefix + "minValue", slider.min().get());
                seeded(prefix + "maxValue", slider.max().get());
                // A step nobody asked for is left out, so the client picks. No capture covers that
                // case: the probe always set one. LeviLamina writes 1.0 rather than omitting
                // (ll/core/ui/form/CustomFormModel.cpp), so if a slider without a step ever renders
                // wrong, seed 1.0 here and take the client default out of the picture.
                if (slider.step() != null) {
                    slot.put("step", slider.step().get());
                    bindPushedDouble(prefix + "step", slider.step());
                    seeded(prefix + "step", slider.step().get());
                }
                // The range can move while the form is open, so the clamp reads it at write time.
                bridge(prefix + "value", ValueType.DOUBLE, slider.value(),
                        value -> Math.max(slider.min().get(), Math.min(slider.max().get(), (Double) value)),
                        usable(slider));
                seeded(prefix + "value", seedValue);
                return slot;
            }
            case DROPDOWN: {
                Dropdown dropdown = (Dropdown) component;
                slot.put("dropdown_visible", true);
                inputCommon(slot, dropdown, prefix);
                double selected = dropdown.value().get();
                // The same membership rule a write faces; only an observable set after build time
                // can get here, since Cumulus checks the value the form was built with.
                if (dropdown.items().stream().noneMatch(entry -> entry.value() == selected)) {
                    throw new IllegalArgumentException("no dropdown item has the value " + selected);
                }
                slot.put("value", selected);

                Map<String, Object> items = new LinkedHashMap<>();
                List<Dropdown.Item> entries = dropdown.items();
                for (int i = 0; i < entries.size(); i++) {
                    Dropdown.Item entry = entries.get(i);
                    Map<String, Object> item = new LinkedHashMap<>();
                    // An entry's text is seeded from whatever it holds now. Changing it later
                    // needs a path inside the items map, which no capture shows, so a reactive
                    // entry label is read once here rather than followed.
                    item.put("label", seedText(entry.label().get()));
                    item.put("value", entry.value());
                    if (entry.description() != null) {
                        item.put("description", seedText(entry.description().get()));
                    }
                    items.put(String.valueOf(i), item);
                }
                items.put("length", (long) entries.size());
                slot.put("items", items);

                // A write that is no entry's value names nothing and is dropped.
                bridge(prefix + "value", ValueType.DOUBLE, dropdown.value(), value -> {
                    for (Dropdown.Item entry : entries) {
                        if (entry.value() == (Double) value) {
                            return value;
                        }
                    }
                    return null;
                }, usable(dropdown));
                seeded(prefix + "value", dropdown.value().get());
                return slot;
            }
            case BUTTON: {
                Button button = (Button) component;
                slot.put("button_visible", true);
                slot.put("label", seedText(button.label().get()));
                slot.put("onClick", 0.0d);
                bindPushedText(prefix + "label", button.label());
                seeded(prefix + "label", pushableText(button.label().get()));
                seedDisabled(slot, button.disabled(), prefix);
                if (button.tooltip() != null) {
                    slot.put("tooltip", seedText(button.tooltip().get()));
                    bindPushedText(prefix + "tooltip", button.tooltip());
                    seeded(prefix + "tooltip", pushableText(button.tooltip().get()));
                }
                // The value is a press counter; any write is a press, unless the screen makes the
                // button unavailable, which only the client would otherwise be enforcing.
                Usable pressable = usable(button);
                session.bind(prefix + "onClick", ValueType.DOUBLE, true, Validator.ANY,
                        (raw, normalized) -> {
                            if (pressable.get()) {
                                button.onClick().run();
                            }
                        });
                return slot;
            }
            default:
                throw new AssertionError("unhandled component kind " + component.kind());
        }
    }

    private void inputCommon(Map<String, Object> slot, DataDrivenComponent.Input<?> input, String prefix) {
        slot.put("label", seedText(input.label().get()));
        bindPushedText(prefix + "label", input.label());
        seeded(prefix + "label", pushableText(input.label().get()));
        seedDisabled(slot, input.disabled(), prefix);
        if (input.description() != null) {
            slot.put("description", seedText(input.description().get()));
            bindPushedText(prefix + "description", input.description());
            seeded(prefix + "description", pushableText(input.description().get()));
        }
    }

    /**
     * Vanilla omits {@code disabled} when the option was never given, and the capture never shows
     * it; the key itself is inferred from its scripting option's name, the same one-to-one mapping
     * every captured option follows. It is seeded only when it can matter: set, or able to change.
     */
    private void seedDisabled(Map<String, Object> slot, Value<Boolean> disabled, String prefix) {
        if (disabled.observable() != null || disabled.get()) {
            slot.put("disabled", disabled.get());
        }
        bindPushedBoolean(prefix + "disabled", disabled);
        seeded(prefix + "disabled", disabled.get());
    }

    private void compileMessageBox(DataDrivenMessageBox box, IntConsumer clickSink) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("title", seedText(box.title().get()));
        tree.put("body", seedText(box.body().get()));
        bindPushedText("title", box.title());
        bindPushedText("body", box.body());
        seeded("title", pushableText(box.title().get()));
        seeded("body", pushableText(box.body().get()));
        tree.put("button1", messageButton("button1", box.button1(), box.button1Tooltip(), 1, clickSink));
        tree.put("button2", messageButton("button2", box.button2(), box.button2Tooltip(), 2, clickSink));
        session.seed(tree);
    }

    private Map<String, Object> messageButton(String name, Value<Text> label, @Nullable Value<Text> tooltip,
                                              int number, IntConsumer clickSink) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("label", seedText(label.get()));
        button.put("onClick", 0.0d);
        bindPushedText(name + ".label", label);
        seeded(name + ".label", pushableText(label.get()));
        if (tooltip != null) {
            button.put("tooltip", seedText(tooltip.get()));
            bindPushedText(name + ".tooltip", tooltip);
            seeded(name + ".tooltip", pushableText(tooltip.get()));
        }
        session.bind(name + ".onClick", ValueType.DOUBLE, true, Validator.ANY,
                (raw, normalized) -> clickSink.accept(number));
        return button;
    }

    /**
     * Connects one client-writable path to the form's observable, in both directions.
     *
     * <p>A write the client made is not sent back to it, and a value the server changes is. Both
     * fall out of remembering what the client is believed to be showing: a change is pushed only
     * when the observable no longer agrees with that. Counting expected echoes instead goes wrong
     * as soon as a plugin change and a player edit cross, because a count says nothing about which
     * value it belongs to.
     *
     * <p>Remembering the value the client sent also puts it back in step after a write that was
     * not taken as sent: a slider dragged past its range leaves the client showing the far value
     * while the observable holds the clamped one, they no longer agree, and the clamped one is
     * pushed. A write rejected outright cannot do that, since nothing changed to react to. The
     * server's value stays right and the client is corrected by the next change.
     */
    private <T> void bridge(String path, ValueType type, Observable<T> observable, Validator validator,
            Usable usable) {
        PathSync sync = new PathSync(path);
        // The validator stays pure: the engine runs it on pushes too, and a push needs the clamp
        // just as a write does. The gate and the believed-client-state recording live in the
        // handler, which only a client write reaches.
        session.bind(path, type, true, validator,
                (raw, normalized) -> {
                    if (!usable.get()) {
                        // Hidden or disabled: the player cannot have produced this.
                        return;
                    }
                    sync.clientShowing(raw);
                    @SuppressWarnings("unchecked")
                    T typed = (T) normalized;
                    observable.set(typed);
                    sync.reconcile(observable.get());
                });
        subscriptions.add(observable.subscribe(ignored ->
                onSessionThread.execute(() -> sync.reconcile(observable.get()))));
        syncs.put(path, sync);
    }

    private void bindPushedText(String path, Value<Text> value) {
        session.bind(path, ValueType.STRING, false, Validator.ANY, (raw, normalized) -> { });
        Observable<Text> observable = value.observable();
        if (observable == null) {
            return;
        }
        PathSync sync = new PathSync(path);
        syncs.put(path, sync);
        subscriptions.add(observable.subscribe(ignored ->
                onSessionThread.execute(() -> sync.reconcile(pushableText(observable.get())))));
    }

    private void bindPushedBoolean(String path, Value<Boolean> value) {
        session.bind(path, ValueType.BOOLEAN, false, Validator.ANY, (raw, normalized) -> { });
        bindPushedValue(path, value);
    }

    private void bindPushedDouble(String path, Value<Double> value) {
        session.bind(path, ValueType.DOUBLE, false, Validator.ANY, (raw, normalized) -> { });
        bindPushedValue(path, value);
    }

    private <T> void bindPushedValue(String path, Value<T> value) {
        Observable<T> observable = value.observable();
        if (observable == null) {
            return;
        }
        PathSync sync = new PathSync(path);
        syncs.put(path, sync);
        subscriptions.add(observable.subscribe(ignored ->
                onSessionThread.execute(() -> sync.reconcile(observable.get()))));
    }

    /** Whether a component is one the player can currently act on. */
    private interface Usable {
        boolean get();
    }

    private static Usable usable(DataDrivenComponent component) {
        if (component instanceof Button) {
            Button button = (Button) component;
            return () -> component.visible().get() && !button.disabled().get();
        }
        if (component instanceof DataDrivenComponent.Input<?>) {
            DataDrivenComponent.Input<?> input = (DataDrivenComponent.Input<?>) component;
            return () -> component.visible().get() && !input.disabled().get();
        }
        return () -> component.visible().get();
    }

    /** A literal pushes as its string; nothing else fits in an update packet. */
    private static @Nullable String pushableText(Text text) {
        if (text instanceof Text.Literal) {
            return ((Text.Literal) text).text();
        }
        return null;
    }

    /**
     * A literal seeds as a plain string; anything else seeds as the {@code rawtext} tree the
     * client resolves itself. A sequence is simply several parts in the list.
     */
    static Object seedText(Text text) {
        if (text instanceof Text.Literal) {
            return ((Text.Literal) text).text();
        }
        Map<String, Object> rawtext = new LinkedHashMap<>();
        List<Object> parts = new ArrayList<>();
        addParts(text, parts);
        rawtext.put("rawtext", parts);
        return rawtext;
    }

    private static void addParts(Text text, List<Object> parts) {
        if (text instanceof Text.Sequence) {
            for (Text part : ((Text.Sequence) text).parts()) {
                addParts(part, parts);
            }
            return;
        }
        Map<String, Object> part = new LinkedHashMap<>();
        if (text instanceof Text.Literal) {
            part.put("text", ((Text.Literal) text).text());
        } else {
            Text.Translatable translatable = (Text.Translatable) text;
            part.put("translate", translatable.key());
            if (!translatable.args().isEmpty()) {
                part.put("with", substitutions(translatable.args()));
            }
        }
        parts.add(part);
    }

    /**
     * The substitutions of a translation. A mixed argument list becomes one message whose parts
     * are the arguments in order - each argument exactly one part, or the positions after it
     * would shift - and that shape is capture-verified. All-literal arguments go as the plain
     * string list rawtext also accepts; no capture shows that shape, so it is the one inference
     * here.
     */
    private static Object substitutions(List<Text> args) {
        List<String> literals = new ArrayList<>(args.size());
        for (Text arg : args) {
            if (!(arg instanceof Text.Literal)) {
                literals = null;
                break;
            }
            literals.add(((Text.Literal) arg).text());
        }
        if (literals != null) {
            return literals;
        }
        List<Object> parts = new ArrayList<>(args.size());
        for (Text arg : args) {
            parts.add(substitution(arg));
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("rawtext", parts);
        return message;
    }

    /** One argument as one rawtext part; a sequence becomes a single nested {@code rawtext}. */
    private static Object substitution(Text arg) {
        if (arg instanceof Text.Sequence) {
            Map<String, Object> nested = new LinkedHashMap<>();
            List<Object> parts = new ArrayList<>();
            addParts(arg, parts);
            nested.put("rawtext", parts);
            return nested;
        }
        List<Object> single = new ArrayList<>(1);
        addParts(arg, single);
        return single.get(0);
    }

    private final class PathSync {
        private final String path;
        private @Nullable Object clientValue;

        private PathSync(String path) {
            this.path = path;
        }

        /** Records what the client shows after a write it made. */
        private void clientShowing(Object value) {
            clientValue = value;
        }

        /** Records what the seed put in front of the client. */
        private void seeded(@Nullable Object value) {
            clientValue = value;
        }

        /** Pushes the current value unless the client is already showing it. */
        private void reconcile(@Nullable Object current) {
            if (current == null || current.equals(clientValue)) {
                // Null is text no update packet can carry; a full tree only fits in a seed.
                return;
            }
            if (session.push(path, current)) {
                clientValue = current;
            }
        }
    }
}
