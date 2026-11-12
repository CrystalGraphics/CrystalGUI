package com.crystalgui.screen;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgui.core.settings.Preferences;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.desktop.app.AppDocument;
import com.crystalgui.desktop.app.AppDocuments;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.app.ClientRoot;
import com.crystalgui.desktop.app.PopupLayer;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.viewport.CgUiViewport;
import com.crystalgui.viewport.Readouts;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The documents applications run in, one viewport each: built when an application launches, framed on every host
 * frame before the shell's own (so the surfaces a window's frame shows have been drawn), and ended on the render
 * thread once their application has quit.
 *
 * <p>A document here shows nothing on the screen itself: its viewport executes its passes and composites nothing, and
 * the shell draws each client's surface inside the window's frame.</p>
 */
final class ApplicationDocuments implements AppDocuments {

    private static final class Entry implements AppDocument.Backend {
        final CgUiViewport viewport;
        final CgUiViewport.Content content;
        final Connection scale;
        final ApplicationDocuments owner;
        AppDocument document;
        boolean first;

        Entry(ApplicationDocuments owner, CgUiViewport viewport, CgUiViewport.Content content, Connection scale) {
            this.owner = owner;
            this.viewport = viewport;
            this.content = content;
            this.scale = scale;
        }

        /** Asked from any thread: ended by the render thread at the start of its next frame. */
        @Override
        public void close() {
            owner.closing.add(this);
        }

        @Override
        public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
            return viewport.consumeMouseEvent(event);
        }

        @Override
        public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
            return viewport.consumeKeyboardEvent(event);
        }
    }

    private final List<Entry> open = new CopyOnWriteArrayList<>();
    private final Queue<Entry> closing = new ConcurrentLinkedQueue<>();

    @Override
    public AppDocument open(ApplicationKind kind) {
        return open("app:" + kind.id(), kind.threading() == ApplicationKind.Threading.INLINE
                ? CgUiViewport.Threading.INLINE : CgUiViewport.Threading.fromFlags(), false);
    }

    @Override
    public AppDocument openServer(String name) {
        return open("server:" + name, CgUiViewport.Threading.fromFlags(), true);
    }

    /** {@code first}: framed before the applications', whose windows may show what it draws. */
    private AppDocument open(String name, CgUiViewport.Threading threading, boolean first) {
        UIDocument document = CgUiScreen.newDocument();
        document.styles().addStylesheet(StyleSheet.parse(ClientRoot.SHEET + PopupLayer.SHEET));
        document.useTopLayer(new PopupLayer());
        CgUiViewport viewport = CgUiViewport.open(document, threading, name);
        // Its frames execute and show nothing: the shell draws what they leave in the clients' surfaces.
        viewport.presenter().composites(false);
        AppDocument[] made = new AppDocument[1];
        CgUiViewport.Content whole = CgUiViewport.Content.whole(document);
        CgUiViewport.Content content = new CgUiViewport.Content() {
            @Override
            public boolean frame(float deltaSeconds, int width, int height) {
                return whole.frame(deltaSeconds, width, height);
            }

            @Override
            public void paint(CgUiPaintContext ctx) {
                whole.paint(ctx);
            }

            @Override
            public void readouts(Readouts out) {
                if (made[0] != null) made[0].committed();
            }
        };
        // THE USER'S SCALE, as the shell's: the clients draw at the size their frames give them.
        Connection scale = Preferences.PUBLISHED.changes().subscribe(
                () -> document.execute(() -> document.boxes().setUiScale(Preferences.current().uiScale())));
        Entry entry = new Entry(this, viewport, content, scale);
        entry.first = first;
        made[0] = new AppDocument(document, entry);
        entry.document = made[0];
        open.add(entry);
        return made[0];
    }

    /** Ends what has quit, then frames every document at {@code stage}. Render thread, before the shell's frame. */
    void frame(CgStageFrame stage) {
        for (Entry gone; (gone = closing.poll()) != null; ) {
            open.remove(gone);
            gone.scale.disconnect();
            gone.viewport.close();
        }
        frameAll(stage, true);
        frameAll(stage, false);
    }

    private void frameAll(CgStageFrame stage, boolean first) {
        for (Entry entry : open) {
            if (entry.first != first) continue;
            entry.viewport.frame(stage, entry.content);
            // What the application left, such as an Escape nothing in it took, goes where the desktop's own go.
            CgUiScreen.input().returnUnhandledKeys(entry.viewport);
        }
    }

    /** Ends every document, as the client shuts down. Render thread. */
    void dispose() {
        for (Entry entry : open) {
            entry.scale.disconnect();
            entry.viewport.close();
        }
        open.clear();
        closing.clear();
    }
}
