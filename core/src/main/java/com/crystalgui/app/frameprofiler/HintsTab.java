package com.crystalgui.app.frameprofiler;

import com.crystalgraphics.trace.CgTraceHints;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.widget.text.UIText;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * What the selection is accused of, and a way to each thing accused.
 *
 * <pre>{@code
 * HintsTab tab = new HintsTab();
 * tab.onZone(model::selectZone);
 * tab.onCounter(name -> countersTab.highlight(name));
 * tab.show(model.hintsOfSelection(), model.selectionFrameCount());
 * }</pre>
 *
 * <p>First among the tabs, because it is the answer the others are the working for. Each row says what
 * was found with the numbers behind it and carries its link as a button: a zone selects that zone, a
 * counter opens its track, and anything else — a class, a document — is shown as written. Over a range
 * a rule appears once, with how many frames it fired in and a button to the first of them.</p>
 */
public class HintsTab extends UIElement {

    public static final Name NAME = Name.of("hintstab");

    public static final String HINT_CLASS = "__hint__";
    public static final String HEAD_CLASS = "__hint-head__";
    public static final String CODE_CLASS = "__hint-code__";
    public static final String META_CLASS = "__hint-meta__";
    public static final String TEXT_CLASS = "__hint-text__";
    public static final String LINKS_CLASS = "__hint-links__";
    public static final String LINK_CLASS = "__hint-link__";
    public static final String REF_CLASS = "__hint-ref__";
    public static final String EMPTY_CLASS = "__empty__";

    private final ScrollerView list = new ScrollerView();
    private final List<UIElement> rows = new ArrayList<>();

    private Consumer<String> zoneListener = name -> { };
    private Consumer<String> counterListener = name -> { };
    private IntConsumer frameListener = position -> { };

    public HintsTab() {
        super(NAME);
        append(list);
    }

    public HintsTab onZone(Consumer<String> listener) {
        zoneListener = listener;
        return this;
    }

    public HintsTab onCounter(Consumer<String> listener) {
        counterListener = listener;
        return this;
    }

    /** Called with a frame's place in the ring when a range row's "first in" button is pressed. */
    public HintsTab onFrame(IntConsumer listener) {
        frameListener = listener;
        return this;
    }

    /** The rows now shown — what a scripted run clicks. */
    public List<UIElement> rows() {
        return List.copyOf(rows);
    }

    /** @param frames how many frames the selection is, so a range row can say "in 3 of 40" */
    public void show(List<ProfilerModel.HintRow> hints, int frames) {
        // THE SAME ROWS WHEN THE SAME RULES FIRE, with only their texts rewritten: the window shows on a clock,
        // and a rebuild changed the element tree every time, which restyles and re-syncs it on the next frame.
        if (hints.isEmpty()) {
            String empty = frames > 1
                    ? "Nothing to accuse these " + frames + " frames of."
                    : "Nothing to accuse this frame of.";
            if (empty.equals(shownEmpty)) return;
            list.removeAll();
            rows.clear();
            shownShapes = List.of();
            shownEmpty = empty;
            UIText none = new UIText(empty);
            none.addClass(EMPTY_CLASS);
            list.append(none);
            return;
        }
        List<Shape> shapes = new ArrayList<>(hints.size());
        for (ProfilerModel.HintRow hint : hints) shapes.add(Shape.of(hint, frames));
        if (shapes.equals(shownShapes)) {
            for (int i = 0; i < hints.size(); i++) {
                setText(texts.get(i), hints.get(i).hint().text());
                if (frames > 1) setText(metas.get(i), meta(hints.get(i), frames));
            }
            return;
        }
        list.removeAll();
        rows.clear();
        texts.clear();
        metas.clear();
        shownShapes = shapes;
        shownEmpty = null;
        for (ProfilerModel.HintRow hint : hints) {
            UIElement row = row(hint, frames);
            rows.add(row);
            list.append(row);
        }
    }

    /** What decides a row's elements: its rule, where its links go, and whether it speaks for a range. */
    private record Shape(String code, @Nullable String zone, @Nullable String counter, @Nullable String link,
                         int first, boolean range) {
        static Shape of(ProfilerModel.HintRow row, int frames) {
            CgTraceHints.Hint hint = row.hint();
            return new Shape(hint.code(), hint.linkedZone(), hint.linkedCounter(), hint.link(),
                    frames > 1 ? row.firstPosition() : -1, frames > 1);
        }
    }

    private List<Shape> shownShapes = List.of();
    @Nullable
    private String shownEmpty;
    /** Each shown row's text and, for a range, its "in N of M" line, in row order. */
    private final List<UIText> texts = new ArrayList<>();
    private final List<UIText> metas = new ArrayList<>();

    private static String meta(ProfilerModel.HintRow row, int frames) {
        return "in " + row.frames() + " of " + frames + " frames";
    }

    private static void setText(UIText label, String text) {
        if (!label.getText().equals(text)) label.setText(text);
    }

    private UIElement row(ProfilerModel.HintRow row, int frames) {
        CgTraceHints.Hint hint = row.hint();
        UIElement box = new UIElement();
        box.addClass(HINT_CLASS);

        UIElement head = new UIElement();
        head.addClass(HEAD_CLASS);
        UIText code = new UIText(hint.code());
        code.addClass(CODE_CLASS);
        head.append(code);
        if (frames > 1) {
            UIText meta = new UIText(meta(row, frames));
            meta.addClass(META_CLASS);
            head.append(meta);
            metas.add(meta);
        }
        box.append(head);

        UIText text = new UIText(hint.text());
        text.addClass(TEXT_CLASS);
        box.append(text);
        texts.add(text);

        UIElement links = new UIElement();
        links.addClass(LINKS_CLASS);
        String zone = hint.linkedZone();
        String counter = hint.linkedCounter();
        if (zone != null) {
            links.append(link("Show zone " + zone, () -> zoneListener.accept(zone)));
        } else if (counter != null) {
            links.append(link("Show counter " + counter, () -> counterListener.accept(counter)));
        } else if (hint.link() != null) {
            UIText ref = new UIText("See " + hint.link());
            ref.addClass(REF_CLASS);
            links.append(ref);
        }
        if (frames > 1) {
            int first = row.firstPosition();
            links.append(link("First frame it fired in", () -> frameListener.accept(first)));
        }
        box.append(links);
        return box;
    }

    private static Button link(String label, Runnable action) {
        Button button = new Button(label);
        button.addClass(LINK_CLASS);
        button.attachListener(action);
        return button;
    }
}
