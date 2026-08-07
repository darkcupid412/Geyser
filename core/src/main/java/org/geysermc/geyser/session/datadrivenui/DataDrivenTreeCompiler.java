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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.cumulus.datadrivenui.DataDrivenForm;
import org.geysermc.cumulus.datadrivenui.DataDrivenMessageBox;
import org.geysermc.cumulus.datadrivenui.component.Button;
import org.geysermc.cumulus.datadrivenui.component.Dropdown;
import org.geysermc.cumulus.datadrivenui.component.DataDrivenComponent;
import org.geysermc.cumulus.datadrivenui.component.Slider;
import org.geysermc.cumulus.datadrivenui.util.Observable;
import org.geysermc.cumulus.datadrivenui.util.Text;
import org.geysermc.cumulus.datadrivenui.util.Value;
import org.geysermc.geyser.session.datadrivenui.DataDrivenScreenSession.ValueType;
import org.geysermc.geyser.session.datadrivenui.DataDrivenScreenSession.Validator;

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
 * <p>The bridge suppresses echoes with a counter per binding: a client write flows into the form's
 * observable, whose notification would otherwise flow straight back out as a push. The counter is
 * incremented before the observable is set (only when the set will actually notify), and each
 * suppressed notification consumes one increment, in order, on the session thread.
 *
 * <p>Live text pushes can only carry strings, because the update packet has no tree in it. A
 * reactive text that changes to a non-literal value while the form is open cannot be pushed and
 * is skipped. Seeds carry full trees; only later pushes have this limit.
 */
public final class DataDrivenTreeCompiler {

    private final DataDrivenScreenSession session;
    private final Executor onSessionThread;
    private final List<Observable.Subscription> subscriptions = new ArrayList<>();

    private DataDrivenTreeCompiler(DataDrivenScreenSession session, Executor onSessionThread) {
        this.session = session;
        this.onSessionThread = onSessionThread;
    }

    /** What compiling produced: the subscriptions to close when the showing ends. */
    public static final class Compiled implements AutoCloseable {
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
    public static Compiled compile(DataDrivenForm form, DataDrivenScreenSession session, Executor onSessionThread) {
        DataDrivenTreeCompiler compiler = new DataDrivenTreeCompiler(session, onSessionThread);
        compiler.compileForm(form);
        return new Compiled(compiler.subscriptions);
    }

    /**
     * Compiles a message box onto the given session. Button presses are reported to {@code
     * clickSink} as 1 or 2; turning that plus the close into a result is the manager's job.
     */
    public static Compiled compile(DataDrivenMessageBox box, DataDrivenScreenSession session, Executor onSessionThread,
                                   IntConsumer clickSink) {
        DataDrivenTreeCompiler compiler = new DataDrivenTreeCompiler(session, onSessionThread);
        compiler.compileMessageBox(box, clickSink);
        return new Compiled(compiler.subscriptions);
    }

    private void compileForm(DataDrivenForm form) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("title", seedText(form.title().get()));
        bindPushedText("title", form.title());

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
            closeButton.put("label", "Close");
            closeButton.put("onClick", 0.0d);
            tree.put("closeButton", closeButton);
            // The press already arrives as the screen-closed packet; the write itself needs no
            // handler, only permission to exist.
            session.bind("closeButton.onClick", ValueType.DOUBLE, true, Validator.ANY, value -> { });
        }

        session.seed(tree);
    }

    private Map<String, Object> slot(DataDrivenComponent component, String prefix) {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("visible", component.visible().get());
        bindPushedBoolean(prefix + "visible", component.visible());

        switch (component.kind()) {
            case HEADER:
            case LABEL: {
                DataDrivenComponent.Textual textual = (DataDrivenComponent.Textual) component;
                slot.put(component.kind() == DataDrivenComponent.Kind.HEADER ? "header_visible" : "label_visible", true);
                slot.put("text", seedText(textual.text().get()));
                bindPushedText(prefix + "text", textual.text());
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
                bridge(prefix + "text", ValueType.STRING, input.value(),
                        value -> ((String) value).length() <= 5000 ? value : null);
                return slot;
            }
            case TOGGLE: {
                @SuppressWarnings("unchecked")
                DataDrivenComponent.Input<Boolean> input = (DataDrivenComponent.Input<Boolean>) component;
                slot.put("toggle_visible", true);
                inputCommon(slot, input, prefix);
                slot.put("toggled", input.value().get());
                bridge(prefix + "toggled", ValueType.BOOLEAN, input.value(), Validator.ANY);
                return slot;
            }
            case SLIDER: {
                Slider slider = (Slider) component;
                slot.put("slider_visible", true);
                inputCommon(slot, slider, prefix);
                slot.put("value", slider.value().get());
                slot.put("minValue", slider.min().get());
                slot.put("maxValue", slider.max().get());
                bindPushedDouble(prefix + "minValue", slider.min());
                bindPushedDouble(prefix + "maxValue", slider.max());
                // A step nobody asked for is left out, so the client picks. No capture covers that
                // case: the probe always set one. LeviLamina writes 1.0 rather than omitting
                // (ll/core/ui/form/CustomFormModel.cpp), so if a slider without a step ever renders
                // wrong, seed 1.0 here and take the client default out of the picture.
                if (slider.step() != null) {
                    slot.put("step", slider.step().get());
                    bindPushedDouble(prefix + "step", slider.step());
                }
                // The range can move while the form is open, so the clamp reads it at write time.
                bridge(prefix + "value", ValueType.DOUBLE, slider.value(),
                        value -> Math.max(slider.min().get(), Math.min(slider.max().get(), (Double) value)));
                return slot;
            }
            case DROPDOWN: {
                Dropdown dropdown = (Dropdown) component;
                slot.put("dropdown_visible", true);
                inputCommon(slot, dropdown, prefix);
                slot.put("value", dropdown.value().get());

                Map<String, Object> items = new LinkedHashMap<>();
                List<Dropdown.Item> entries = dropdown.items();
                for (int i = 0; i < entries.size(); i++) {
                    Dropdown.Item entry = entries.get(i);
                    Map<String, Object> item = new LinkedHashMap<>();
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
                });
                return slot;
            }
            case BUTTON: {
                Button button = (Button) component;
                slot.put("button_visible", true);
                slot.put("label", seedText(button.label().get()));
                slot.put("onClick", 0.0d);
                bindPushedText(prefix + "label", button.label());
                bindPushedBoolean(prefix + "disabled", button.disabled());
                slot.put("disabled", button.disabled().get());
                if (button.tooltip() != null) {
                    slot.put("tooltip", seedText(button.tooltip().get()));
                    bindPushedText(prefix + "tooltip", button.tooltip());
                }
                // The value is a press counter; any write is a press.
                session.bind(prefix + "onClick", ValueType.DOUBLE, true, Validator.ANY,
                        value -> button.onClick().run());
                return slot;
            }
            default:
                throw new AssertionError("unhandled component kind " + component.kind());
        }
    }

    private void inputCommon(Map<String, Object> slot, DataDrivenComponent.Input<?> input, String prefix) {
        slot.put("label", seedText(input.label().get()));
        slot.put("disabled", input.disabled().get());
        bindPushedText(prefix + "label", input.label());
        bindPushedBoolean(prefix + "disabled", input.disabled());
        if (input.description() != null) {
            slot.put("description", seedText(input.description().get()));
            bindPushedText(prefix + "description", input.description());
        }
    }

    private void compileMessageBox(DataDrivenMessageBox box, IntConsumer clickSink) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("title", seedText(box.title().get()));
        tree.put("body", seedText(box.body().get()));
        bindPushedText("title", box.title());
        bindPushedText("body", box.body());
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
        if (tooltip != null) {
            button.put("tooltip", seedText(tooltip.get()));
            bindPushedText(name + ".tooltip", tooltip);
        }
        session.bind(name + ".onClick", ValueType.DOUBLE, true, Validator.ANY,
                value -> clickSink.accept(number));
        return button;
    }

    /**
     * Connects one client-writable path to the form's observable, both directions, echoes
     * suppressed.
     */
    private <T> void bridge(String path, ValueType type, Observable<T> observable, Validator validator) {
        EchoGate gate = new EchoGate();
        session.bind(path, type, true, validator, value -> {
            @SuppressWarnings("unchecked")
            T typed = (T) value;
            if (typed.equals(observable.get())) {
                // The observable will not notify for an equal value; nothing to suppress.
                return;
            }
            gate.suppressed++;
            observable.set(typed);
        });
        subscriptions.add(observable.subscribe(value ->
                onSessionThread.execute(() -> {
                    if (gate.suppressed > 0) {
                        gate.suppressed--;
                        return;
                    }
                    session.push(path, value);
                })));
    }

    private void bindPushedText(String path, Value<Text> value) {
        session.bind(path, ValueType.STRING, false, Validator.ANY, ignored -> { });
        Observable<Text> observable = value.observable();
        if (observable != null) {
            subscriptions.add(observable.subscribe(text ->
                    onSessionThread.execute(() -> {
                        String pushed = pushableText(text);
                        if (pushed != null) {
                            session.push(path, pushed);
                        }
                    })));
        }
    }

    private void bindPushedBoolean(String path, Value<Boolean> value) {
        session.bind(path, ValueType.BOOLEAN, false, Validator.ANY, ignored -> { });
        Observable<Boolean> observable = value.observable();
        if (observable != null) {
            subscriptions.add(observable.subscribe(pushed ->
                    onSessionThread.execute(() -> session.push(path, pushed))));
        }
    }

    private void bindPushedDouble(String path, Value<Double> value) {
        session.bind(path, ValueType.DOUBLE, false, Validator.ANY, ignored -> { });
        Observable<Double> observable = value.observable();
        if (observable != null) {
            subscriptions.add(observable.subscribe(pushed ->
                    onSessionThread.execute(() -> {
                        if (Double.isFinite(pushed)) {
                            session.push(path, pushed);
                        }
                    })));
        }
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
                List<Object> with = new ArrayList<>();
                for (Text arg : translatable.args()) {
                    if (arg instanceof Text.Literal) {
                        with.add(((Text.Literal) arg).text());
                    } else {
                        with.add(seedText(arg));
                    }
                }
                part.put("with", with);
            }
        }
        parts.add(part);
    }

    private static final class EchoGate {
        private int suppressed;
    }
}
