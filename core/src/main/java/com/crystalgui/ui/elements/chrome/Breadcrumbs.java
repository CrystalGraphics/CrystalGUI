package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UIText;

import javax.annotation.Nullable;
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
    /** The glyph before a segment's text. Hidden unless that crumb supplied one. */
    public static final String ICON_CLASS = "__crumb-icon__";
    /** What {@link Crumb#iconClass()} is swapped under. The icons carry their own palette, so nothing tints it. */
    public static final String FILETYPE_PREFIX = "filetype-";
    public static final String SEPARATOR_CLASS = "__crumb-sep__";

    /** On the last segment — where you are, rather than somewhere you could go. */
    public static final String CURRENT_CLASS = "__crumb-current__";

    /** How many levels deep a trail may go before it stops growing slots. */
    public static final int MAX_SEGMENTS = 8;

    /** Emits the index of the segment pressed. The last is never emitted; it is where you already are. */
    public final Signal.Value<Integer> onSegmentChosen = new Signal.Value<>();

    /**
     * One trail entry: its text, and optionally a glyph to put in front of it.
     *
     * <p>The <b>drawable</b> is passed in rather than a file name, so this widget stays a trail of places
     * and does not acquire an opinion about files — a settings path and a package path use the same
     * segments with no icon at all. {@code iconClass} is what colours it: a dozen languages share one
     * {@code code} glyph and still need their own colours, so colour is keyed to the class and never to
     * the icon. The same split {@code graph.css} makes for port types.</p>
     */
    public record Crumb(String text, @Nullable CgUiDrawable icon, @Nullable String iconClass) {
        public static Crumb of(String text) {
            return new Crumb(text, null, null);
        }
    }

    private final List<UIElement> icons = new ArrayList<>();
    private final List<UIText> segments = new ArrayList<>();
    private final List<UIElement> separators = new ArrayList<>();
    private List<String> trail = new ArrayList<>();

    public Breadcrumbs() {
        markAsInternal();
        for (int i = 0; i < MAX_SEGMENTS; i++) {
            if (i > 0) {
                // A SHAPE, not the character "›". That was U+203A hard-coded here, which is wrong twice
                // over: a glyph in Java is a look the cascade cannot reach, and it depends on the font
                // having the codepoint -- the bundled MinecraftRegular.otf has no U+2026 and UIText
                // carries a whole fallback path because of it. `shape("chevron-right")` is what the
                // configurator's own arrows already use, and it scales and colours from the sheet.
                UIElement separator = new UIElement();
                separator.addClass(SEPARATOR_CLASS);
                separator.setHitTest(false);
                separators.add(separator);
                addInternalChild(separator);
            }
            // BUILT HERE, never in setTrail. An element created during an update lands after that
            // frame's layout pass -- the trap the palette's key chips and the editor's gutter arrows each
            // shipped once. A slot that is sometimes empty is cheaper than one that is sometimes late.
            UIElement icon = new UIElement();
            icon.addClass(ICON_CLASS);
            icon.setHitTest(false);      // the press belongs to the segment beside it
            icons.add(icon);
            addInternalChild(icon);

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
        setCrumbs(List.of());
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    /** Replaces the trail with plain text segments. @see #setCrumbs */
    public Breadcrumbs setTrail(List<String> newTrail) {
        List<Crumb> crumbs = new ArrayList<>(newTrail.size());
        for (String text : newTrail) crumbs.add(Crumb.of(text));
        return setCrumbs(crumbs);
    }

    /** Replaces the trail. Longer than {@link #MAX_SEGMENTS} is truncated from the front, keeping the end. */
    public Breadcrumbs setCrumbs(List<Crumb> newTrail) {
        List<Crumb> shown = new ArrayList<>(newTrail);
        while (shown.size() > MAX_SEGMENTS) shown.remove(0);
        List<String> texts = new ArrayList<>(shown.size());
        for (Crumb crumb : shown) texts.add(crumb.text());
        this.trail = texts;

        for (int i = 0; i < MAX_SEGMENTS; i++) {
            UIText segment = segments.get(i);
            UIElement icon = icons.get(i);
            boolean visible = i < shown.size();
            segment.setDisplayed(visible);
            if (!visible) {
                icon.setDisplayed(false);
                segment.removeClass(CURRENT_CLASS);
                continue;
            }
            Crumb crumb = shown.get(i);
            segment.setText(crumb.text());
            // The last one is where you are: not a link, and styled to say so.
            if (i == shown.size() - 1) segment.addClass(CURRENT_CLASS);
            else segment.removeClass(CURRENT_CLASS);

            icon.setDisplayed(crumb.icon() != null);
            if (crumb.icon() != null) {
                // DEFAULT origin, so `.filetype-java { overlay: icon(...) }` in a theme can still beat it
                // -- written INLINE this would be the one part of a trail a stylesheet cannot touch. The
                // same reasoning ProjectFileTree records for a row's icon.
                StyleGroup.defaultPipeline(icon.getStyle().getGeneralGroup(),
                        g -> g.overlay(crumb.icon()));
            }
            // SWAPPED, never added: a slot is a different file every time the trail moves, so adding
            // `filetype-java` without removing `filetype-md` leaves both on the element and the cascade
            // resolves whichever happens to win -- which reads as a random colour.
            icon.swapPrefixedClass(FILETYPE_PREFIX,
                    crumb.iconClass() == null ? "" : crumb.iconClass());
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
