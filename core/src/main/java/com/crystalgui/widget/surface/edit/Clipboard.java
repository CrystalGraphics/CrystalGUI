package com.crystalgui.widget.surface.edit;

import javax.annotation.Nullable;

/**
 * What cut, copy and paste mean on this surface — the consumer's answer, in one interface.
 *
 * <pre>{@code
 * final class GraphClipboard implements Clipboard<GraphDocument> {
 *     public Class<GraphDocument> type()  { return GraphDocument.class; }
 *     public GraphDocument copy()          { return view.copySelection(); }
 *     public void paste(GraphDocument clip, float worldX, float worldY) { view.pasteAt(clip, worldX, worldY); }
 *     public void pasteBy(GraphDocument clip, float dx, float dy)       { view.paste(clip, dx, dy); }
 * }
 * }</pre>
 *
 * <p>The engine holds what was copied ({@link Clipboards}) and the commands; what a fragment <em>is</em>
 * is yours — a detached graph document, a subtree of a {@code .cgui}. {@link #type} is how a paste into
 * a surface of another kind is declined rather than attempted.</p>
 *
 * <p>{@link #copy} returning null must leave the clipboard alone: copying nothing should not throw away
 * what was copied a minute ago.</p>
 */
public interface Clipboard<T> {

    /** What a fragment is, so a clip from another kind of surface is not offered here. */
    Class<T> type();

    /** The selection as a detached fragment, or null when there is nothing to copy. */
    @Nullable
    T copy();

    /** Adds a copy at a world point — what "paste at the cursor" means. */
    void paste(T clip, float worldX, float worldY);

    /** Adds a copy at an offset from where it was — what paste means with the pointer elsewhere. */
    void pasteBy(T clip, float offsetX, float offsetY);

    /** Whether a clip has anything in it. Overridden where empty is representable. */
    default boolean isEmpty(T clip) {
        return clip == null;
    }
}
