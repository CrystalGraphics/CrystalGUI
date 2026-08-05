package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UIText;

import java.util.ArrayList;
import java.util.List;

/**
 * A trail of places — {@code Appearance & Behavior › Appearance}.
 *
 * <p>Every segment but the last is clickable and emits {@link #onSegmentChosen} with its index, so a
 * caller can navigate to an ancestor. The last is where you are, and is drawn plainly: making it a link
 * to itself is a control that does nothing, which reads as broken rather than as consistent.</p>
 *
 * <h3>Slots, not rebuilt children</h3>
 *
 * <p>The segments are pooled and hidden rather than created per update — the same idiom the palette's key
 * boxes and the editor's gutter arrows use, and for the reason the palette learned the hard way: elements
 * created inside an update land in the tree <em>after</em> the layout pass that would have measured them,
 * so the first frame draws them collapsed. Reusing slots removes the ordering question rather than trying
 * to out-run it.</p>
 */
public class Breadcrumbs extends UIElement {

    public static final String SEGMENT_CLASS = "__crumb__";
    public static final String SEPARATOR_CLASS = "__crumb-sep__";

    /** On the last segment — where you are, rather than somewhere you could go. */
    public static final String CURRENT_CLASS = "__crumb-current__";

    /** How many levels deep a trail may go before it stops growing slots. */
    public static final int MAX_SEGMENTS = 8;

    /** Emits the index of the segment pressed. The last is never emitted; it is where you already are. */
    public final Signal.Value<Integer> onSegmentChosen = new Signal.Value<>();

    private final List<UIText> segments = new ArrayList<>();
    private final List<UIText> separators = new ArrayList<>();
    private List<String> trail = new ArrayList<>();

    public Breadcrumbs() {
        markAsInternal();
        for (int i = 0; i < MAX_SEGMENTS; i++) {
            if (i > 0) {
                UIText separator = new UIText("›");
                separator.addClass(SEPARATOR_CLASS);
                separator.setHitTest(false);
                separators.add(separator);
                addInternalChild(separator);
            }
            UIText segment = new UIText("");
            segment.addClass(SEGMENT_CLASS);
            final int index = i;
            // READ PER EVENT is unnecessary here -- a slot's index never changes, unlike a pooled list
            // row whose index moves as the view scrolls. Capturing it is correct precisely because these
            // slots are positional rather than recycled across different contents.
            segment.onMouseDown.attachListener((element, event) -> {
                if (index >= trail.size() - 1) return;   // the last segment is not a link
                event.stopPropagation();
                onSegmentChosen.emit(index);
            }, false, true);
            segments.add(segment);
            addInternalChild(segment);
        }
        setTrail(List.of());
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    /** Replaces the trail. Longer than {@link #MAX_SEGMENTS} is truncated from the front, keeping the end. */
    public Breadcrumbs setTrail(List<String> newTrail) {
        List<String> shown = new ArrayList<>(newTrail);
        while (shown.size() > MAX_SEGMENTS) shown.remove(0);
        this.trail = shown;

        for (int i = 0; i < MAX_SEGMENTS; i++) {
            UIText segment = segments.get(i);
            boolean visible = i < shown.size();
            if (visible) segment.setText(shown.get(i));
            segment.setDisplayed(visible);
            // The last one is where you are: not a link, and styled to say so.
            if (i == shown.size() - 1) segment.addClass(CURRENT_CLASS);
            else segment.removeClass(CURRENT_CLASS);
        }
        for (int i = 0; i < separators.size(); i++) {
            separators.get(i).setDisplayed(i + 1 < shown.size());
        }
        return this;
    }

    public List<String> trail() {
        return new ArrayList<>(trail);
    }
}
