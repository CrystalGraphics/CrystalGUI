package com.crystalgui.screen;

import com.crystalgraphics.easing.CgEasings;
import com.crystalgui.core.settings.Preferences;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * An application's zoom, shown for a moment at its top right after Ctrl + or Ctrl −: the percentage and a reset, as a
 * browser's zoom bubble. The hold is here; the fade is {@link #SHEET}'s.
 */
final class ZoomIndicator {

    /** Added to an application's document beside its root's sheet. */
    static final String SHEET = """
            .__zoom-indicator__ {
                position: absolute; top: 16px; right: 16px; z-index: 1000;
                flex-direction: row; align-items: center; gap: 16px;
                padding: 8px 16px; border-radius: 8px;
                background-color: var(--editor-zoom-bg, #2B303B);
                opacity: 0; transition: opacity 0.3s;
            }
            .__zoom-indicator__.__shown__ { opacity: 1; }
            .__zoom-indicator__ uitext { font-size: 24px; color: var(--editor-zoom-fg, #C8CDD4); white-space: nowrap; }
            .__zoom-indicator__ button {
                font-size: 24px; color: var(--editor-zoom-link, #4A9EFF); background: #00000000;
                padding: 0px; min-height: 0px; border-radius: 0px;
            }
            """;

    private static final String CLASS = "__zoom-indicator__";
    private static final String SHOWN = "__shown__";
    private static final float HOLD_SECONDS = 1.5f;

    private static final Map<UIDocument, ZoomIndicator> SHOWN_IN = Collections.synchronizedMap(new WeakHashMap<>());

    private final UIElement panel = new UIElement();
    private final UIText label = new UIText("");
    private int shows;

    private ZoomIndicator(UIDocument document, String app) {
        panel.addClass(CLASS);
        label.setHitTest(false);
        Button reset = new Button("Reset");
        // Never takes focus: the key after a reset belongs to what had it.
        reset.setFocusPolicy(FocusPolicy.NONE);
        reset.attachListener(() -> UiScale.resetZoom(app));
        panel.append(label);
        panel.append(reset);
        panel.setHitTest(false);
        document.append(panel);
    }

    /** Shows {@code app}'s zoom now in {@code document}, and again for each change; on the document's thread. */
    static void show(UIDocument document, String app) {
        document.execute(() -> SHOWN_IN.computeIfAbsent(document, d -> new ZoomIndicator(d, app)).hold(document, app));
    }

    private void hold(UIDocument document, String app) {
        label.setText(Preferences.percent(Preferences.current().zoom(app)));
        panel.addClass(SHOWN);
        panel.setHitTest(true);
        int mine = ++shows;
        document.animation().start(HOLD_SECONDS, CgEasings.LINEAR, progress -> { }, () -> {
            if (mine != shows) return;   // shown again since: that hold decides
            panel.removeClass(SHOWN);
            panel.setHitTest(false);
        });
    }
}
