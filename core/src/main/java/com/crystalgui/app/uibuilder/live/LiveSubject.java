package com.crystalgui.app.uibuilder.live;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.BuilderSelection;
import com.crystalgui.app.uibuilder.canvas.UIBuilderView;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/**
 * What a live pick selected, answered to the whole window.
 *
 * <pre>{@code
 * LiveSubject subject = LiveSubject.on(document);
 * subject.pick(element);       // the inspector describes it on the next frame
 * }</pre>
 *
 * <p><b>Document-level, not element-level, and that is the point.</b> A {@code DataContext} walks OUTWARD
 * from whatever has focus — so a selection held by a builder's canvas can only be found from inside that
 * canvas. Live inspect has no canvas: the thing being picked is a taskbar entry, a dialog, a widget in
 * somebody else's scene, and the inspector asking about it is somewhere else entirely. A provider on the
 * document is reachable from every one of them, because the walk ends at the window.</p>
 *
 * <p>One per document, so two pickers over one window do not fight; {@link #on} returns the one that is
 * already installed.</p>
 */
public final class LiveSubject implements DataProvider {

    /** One per document, gone with it. */
    private static final UIDocument.Local<LiveSubject> LOCAL = UIDocument.Local.of("live-subject", document -> {
        LiveSubject installed = new LiveSubject(document);
        document.addDataProvider(installed);
        return installed;
    });

    private final BuilderSelection selection;

    private LiveSubject(@Nullable UIDocument document) {
        selection = new BuilderSelection(() -> document);
    }

    /** The subject for {@code document}, installing one the first time it is asked for. On its thread. */
    public static LiveSubject on(@Nullable UIDocument document) {
        return document == null ? new LiveSubject(null) : document.local(LOCAL);
    }

    /** What is picked. The same selection type the builder's own canvas uses, so the inspector sections
     * do not know or care which of the two is asking. */
    public BuilderSelection selection() {
        return selection;
    }

    /** Selects {@code element}, or clears when null. */
    public void pick(@Nullable UIElement element) {
        selection.selectOnly(element);
    }

    @Override
    public Object getData(DataKey<?> key) {
        // DELIBERATELY NOT UI_BUILDER: there is no builder here. A section that needs the editor -- to
        // write an edit through it -- must find nothing rather than something that cannot be written to,
        // which is what keeps the read-only sections working over a live screen and the editing ones
        // silent.
        return key == UIBuilderView.BUILDER_SELECTION ? selection : null;
    }
}
