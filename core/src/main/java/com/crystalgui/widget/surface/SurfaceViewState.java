package com.crystalgui.widget.surface;

import java.util.LinkedHashMap;
import java.util.Map;

import com.crystalgui.serialization.StateMap;
import com.crystalgui.widget.surface.overlay.FloatingPanel;

/**
 * Where <b>this person</b> was looking: zoom, pan, and where each floating panel was left.
 *
 * <pre>{@code
 * private final SurfaceViewState view = new SurfaceViewState(surface.surface())
 *         .track("preview", previewPanel)
 *         .track("blackboard", board);
 *
 * public <T> void writeViewState(StateMap<T> out) { view.write(out); }
 * public <T> void readViewState(StateMap<T> in)   { view.read(in); }
 * }</pre>
 *
 * <p><b>Per session, never in the file.</b> A shared workspace has one document and several people
 * reading it: with the camera in the document, whoever saves last imposes their view on everyone else.
 * It is also view state by the engine's own boundary — looking around is not an edit, which is why it
 * never went on the undo stack either.</p>
 */
public final class SurfaceViewState {

    private static final String ZOOM = "view.zoom";
    private static final String PAN_X = "view.panX";
    private static final String PAN_Y = "view.panY";
    private static final String PANEL = "view.panel.";

    private final Surface surface;

    private final Map<String, FloatingPanel> panels = new LinkedHashMap<>();

    public SurfaceViewState(Surface surface) {
        this.surface = surface;
    }

    /** Remembers {@code panel} under {@code key} — a stable name, since it is written into a session. */
    public SurfaceViewState track(String key, FloatingPanel panel) {
        panels.put(key, panel);
        return this;
    }

    /** Records the camera and every tracked panel. */
    public <T> void write(StateMap<T> out) {
        out.putFloat(ZOOM, surface.zoom());
        out.putFloat(PAN_X, surface.panX());
        out.putFloat(PAN_Y, surface.panY());
        for (Map.Entry<String, FloatingPanel> entry : panels.entrySet()) {
            // ONLY WHEN THERE IS A BOX TO RECORD. An unmeasured panel yields "", and writing that would
            // erase a good rect rather than leave the one already stored.
            String rect = entry.getValue().rect();
            if (!rect.isEmpty()) out.putString(PANEL + entry.getKey(), rect);
        }
    }

    /** Puts it all back. Anything the record does not carry is left as it is. */
    public <T> void read(StateMap<T> in) {
        float zoom = in.getFloat(ZOOM, 0f);
        if (zoom > 0f) surface.setZoom(zoom);
        // BOTH OR NEITHER: a pan is a point, and applying one axis from a record that carries only the
        // other moves the camera somewhere nobody left it.
        if (in.has(PAN_X) && in.has(PAN_Y)) {
            surface.setPan(in.getFloat(PAN_X, 0f), in.getFloat(PAN_Y, 0f));
        }
        for (Map.Entry<String, FloatingPanel> entry : panels.entrySet()) {
            entry.getValue().applyRect(in.getString(PANEL + entry.getKey(), ""));
        }
    }
}
