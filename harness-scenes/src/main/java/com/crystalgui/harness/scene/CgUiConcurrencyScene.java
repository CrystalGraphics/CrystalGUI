package com.crystalgui.harness.scene;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.ui.HarnessThemes;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgui.style.StyleEngine;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.theme.ThemeRegistry;
import com.crystalgui.style.theme.UiTheme;
import com.crystalgui.style.theme.UiThemeManager;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.viewport.CgUiViewport;
import com.crystalgui.widget.text.UIText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Ten documents, each recording on its own sequence, relaying out and repainting through the same caches for
 * {@value #SECONDS} seconds, with text, icons and theme switches while they do. It proves the engine's shared
 * state under load: no frame throws, and the picture it ends on is the one the same run draws with every document
 * on one sequence.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-concurrency"                       # a sequence each
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-concurrency" -Dcrystalgui.ui.singleSequence=true
 * }</pre>
 *
 * <p>The two runs capture {@code final}: after the churn each document is set to one fixed state and the theme to its
 * first, so the picture does not depend on how many frames the churn took. The run prints
 * {@code [concurrency] ... problems=N}, where a problem is a frame that threw or a document that never presented.
 * {@code -Dcrystalgui.harness.concurrency.seconds=N} shortens the churn.</p>
 */
public final class CgUiConcurrencyScene implements InteractiveSceneLifecycle {

    private static final Logger LOGGER = Logger.getLogger(CgUiConcurrencyScene.class.getName());

    private static final int DOCUMENTS = 10, COLUMNS = 5;
    private static final int SECONDS = Integer.getInteger("crystalgui.harness.concurrency.seconds", 30);
    private static final int CELL_W = 340, CELL_H = 200, GAP = 12;
    /** Frames after the final state before it is photographed: glyphs and icon rasters land a frame or more late. */
    private static final int SETTLE_FRAMES = 150;
    private static final String[] ICONS = {"crystalgui:code", "crystalgui:activity", "crystalgui:copy",
            "crystalgui:crosshair", "crystalgui:file-text", "crystalgui:folder"};
    private static final String[] WORDS = {"relayout", "paint", "glyphs", "icons", "theme", "sequence", "cache", "atlas"};

    private static final String SHEET = """
            .cell { position: absolute; padding: 8px; width: %dpx; height: %dpx; background: #20242c; border-radius: 6px; }
            .cell-title { color: #FFFFFF; font-size: 14; }
            .cell-icons { height: 22px; flex-direction: row; }
            .cell-body { color: #C8CCD4; font-size: 11; width: 100%%; }
            .cell-bar { height: 6px; background: #4C8DFF; }
            .ic { width: 20px; height: 20px; margin-right: 6px; color: #FFFFFF; }
            .w0 { width: 35px; } .w1 { width: 70px; } .w2 { width: 105px; } .w3 { width: 140px; }
            .w4 { width: 175px; } .w5 { width: 210px; } .w6 { width: 245px; } .w7 { width: 280px; }
            """;

    /** One document: what the scene keeps to drive it from the render thread. */
    private static final class Cell {
        final int index;
        final UIDocument document = new UIDocument();
        CgUiViewport viewport;
        CgUiViewport.Content content;
        UIText body;
        UIElement bar;

        Cell(int index) {
            this.index = index;
        }
    }

    private final List<Cell> cells = new ArrayList<>();
    private final AtomicInteger problems = new AtomicInteger();
    private CgRenderStage.Registration screen;
    private HarnessContext context;
    private volatile boolean running = true;
    private long started;
    private int frames, finalAt = -1, switches;
    private String firstTheme;

    @Override
    public void init(HarnessContext ctx) {
        context = ctx;
        List<UiTheme> themes = ThemeRegistry.themes();
        firstTheme = themes.isEmpty() ? null : themes.get(0).id();
        for (int i = 0; i < DOCUMENTS; i++) {
            Cell cell = new Cell(i);
            cell.viewport = CgUiViewport.open(cell.document, CgUiViewport.Threading.fromFlags(), "concurrency-" + i);
            cell.content = new Counting(CgUiViewport.Content.whole(cell.document));
            cells.add(cell);
            cell.viewport.run(() -> build(cell));
        }
        screen = CgRenderStage.SCREEN.register(CgRenderStage.VIEWPORT_WIDGETS, stage -> {
            for (Cell cell : cells) cell.viewport.frame(stage, cell.content);
        });
        started = System.nanoTime();
    }

    private void build(Cell cell) {
        StyleEngine styles = cell.document.styles();
        HarnessThemes.install(styles, null);
        int col = cell.index % COLUMNS, row = cell.index / COLUMNS;
        StringBuilder glyphs = new StringBuilder();
        for (int i = 0; i < ICONS.length; i++) glyphs.append(".ic%d { overlay: icon(\"%s\"); }%n".formatted(i, ICONS[i]));
        String sheet = SHEET.formatted(CELL_W, CELL_H) + glyphs
                + ".cell { left: %dpx; top: %dpx; }".formatted(GAP + col * (CELL_W + GAP), GAP + row * (CELL_H + GAP));
        styles.addStylesheet(StyleSheet.parse(sheet));

        UIElement root = new UIElement();
        root.addClass("cell");
        UIText title = new UIText("Document " + cell.index);
        title.addClass("cell-title");
        root.append(title);
        UIElement icons = new UIElement();
        icons.addClass("cell-icons");
        for (int i = 0; i < ICONS.length; i++) {
            UIElement icon = new UIElement();
            icon.addClass("ic");
            icon.addClass("ic" + (i + cell.index) % ICONS.length);
            icons.append(icon);
        }
        root.append(icons);
        cell.body = new UIText(text(cell.index, 0));
        cell.body.addClass("cell-body");
        root.append(cell.body);
        cell.bar = new UIElement();
        cell.bar.addClass("cell-bar");
        cell.bar.addClass("w0");
        root.append(cell.bar);
        cell.document.append(root);
    }

    /** Text whose length moves with {@code n}, so layout is redone, from the vocabulary of what is being tested. */
    private static String text(int index, int n) {
        StringBuilder out = new StringBuilder();
        int words = 3 + (n + index) % 14;
        for (int w = 0; w < words; w++) out.append(WORDS[(w + n + index) % WORDS.length]).append(' ');
        return out.toString().trim();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        context = ctx;
        int n = ++frames;
        double elapsed = (System.nanoTime() - started) / 1e9;
        if (finalAt < 0 && elapsed < SECONDS) {
            for (Cell cell : cells) {
                int step = n;
                cell.viewport.execute(() -> {
                    cell.body.setText(text(cell.index, step));
                    for (int w = 0; w < 8; w++) cell.bar.removeClass("w" + w);
                    cell.bar.addClass("w" + (step * 3 + cell.index) % 8);
                });
            }
            // Twice mid-run, as a player does: a published theme every document takes at its next frame.
            if (elapsed > SECONDS * 0.35 && switches == 0 || elapsed > SECONDS * 0.7 && switches == 1) {
                switches++;
                cells.get(0).viewport.execute(CgUiConcurrencyScene::nextTheme);
            }
            return;
        }
        if (finalAt < 0) {
            finalAt = n;
            for (Cell cell : cells) {
                cell.viewport.execute(() -> {
                    cell.body.setText("final " + cell.index);
                    for (int w = 0; w < 8; w++) cell.bar.removeClass("w" + w);
                    cell.bar.addClass("w3");
                });
            }
            if (firstTheme != null) cells.get(0).viewport.execute(() -> UiThemeManager.getInstance().setTheme(firstTheme));
        } else if (n == finalAt + SETTLE_FRAMES) {
            ctx.getArtifactService().requestCapture("final");
        } else if (n == finalAt + SETTLE_FRAMES + 30) {
            report();
            running = false;
        }
    }

    private static void nextTheme() {
        UiThemeManager themes = UiThemeManager.getInstance();
        List<UiTheme> all = ThemeRegistry.themes();
        if (all.size() < 2) return;
        int at = 0;
        for (int i = 0; i < all.size(); i++) if (all.get(i).id().equals(themes.activeThemeId())) at = i;
        themes.setTheme(all.get((at + 1) % all.size()).id());
    }

    private void report() {
        long fewest = Long.MAX_VALUE, most = 0;
        for (Cell cell : cells) {
            long shown = cell.viewport.presentedFrames();
            fewest = Math.min(fewest, shown);
            most = Math.max(most, shown);
            if (shown == 0) problems.incrementAndGet();
        }
        String line = String.format("[concurrency] documents=%d seconds=%d themeSwitches=%d presented=%d..%d problems=%d "
                + "singleSequence=%s", DOCUMENTS, SECONDS, switches, fewest, most, problems.get(),
                Boolean.getBoolean("crystalgui.ui.singleSequence"));
        LOGGER.info(line);
        System.out.println(line);
    }

    @Override
    public void dispose() {
        if (screen != null) screen.close();
        screen = null;
        for (Cell cell : cells) cell.viewport.close();
        cells.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return true;
    }

    /** A document's content, counting the frames that threw. */
    private final class Counting implements CgUiViewport.Content {
        private final CgUiViewport.Content inner;

        Counting(CgUiViewport.Content inner) {
            this.inner = inner;
        }

        @Override
        public boolean frame(float deltaSeconds, int width, int height) {
            return inner.frame(deltaSeconds, width, height);
        }

        @Override
        public void paint(com.crystalgui.render.CgUiPaintContext ctx) {
            inner.paint(ctx);
        }

        @Override
        public void failed(Throwable cause) {
            problems.incrementAndGet();
            LOGGER.severe("[concurrency] a frame threw: " + cause);
        }
    }
}
