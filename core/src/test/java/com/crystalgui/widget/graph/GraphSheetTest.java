package com.crystalgui.widget.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.layout.TabView;

/**
 * <b>Opening a graph must not restyle the window it opened in.</b>
 *
 * <p>Reported from the gallery: visiting the graph page left every other page unstyled. A widget that
 * changes the cascade for its whole document is the worst kind of coupling there is, because nothing
 * about the symptom points at what caused it.</p>
 */
public class GraphSheetTest {

    /** The gallery's own stack, in the gallery's own order. */
    private static UIDocument galleryWindow() {
        UIElementRegistry.bootstrap();
        UIDocument window = new UIDocument().markFrameThread();
        window.styles().addStylesheet(StyleSheet.DEFAULT);
        window.styles().addStylesheet(StyleSheetRegistry.of("crystalgui:graph"));
        window.styles().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        return window;
    }

    /** The tag is the cascade identity: a graph that reports anything else matches no graph rule. */
    @Test
    public void aGraphStillReportsItsOwnTag() {
        UIElementRegistry.bootstrap();
        GraphView graph = new GraphView();

        assertEquals("crystalgui:graphview", graph.name().toString());
        assertEquals("crystalgui:graphview", graph.tagName());
    }

    @Test
    public void addingAGraphDoesNotChangeWhatElseIsStyled() {
        UIDocument window = galleryWindow();

        Button button = new Button("click me");
        window.append(button);
        window.update(800f, 600f);
        Object before = button.getStyle().getComputed(StylePropertyRegistry.COLOR);
        int sheetsBefore = window.styles().getSheets().size();

        window.append(new GraphView());
        window.update(800f, 600f);

        assertEquals("a graph added no sheet of its own", sheetsBefore,
                window.styles().getSheets().size());
        assertEquals("and changed nothing about what was already styled", before,
                button.getStyle().getComputed(StylePropertyRegistry.COLOR));
        assertNotNull("the ore theme still reaches a button",
                button.getStyle().getComputed(StylePropertyRegistry.BACKGROUND));
    }

    /**
     * The reported trigger, exactly: the graph is built with every other page at startup and only
     * becomes visible when its tab is selected, so the moment under suspicion is the SELECTION, not
     * the construction.
     */
    @Test
    public void selectingTheGraphTabDoesNotUnstyleTheOtherPages() {
        UIDocument window = galleryWindow();

        TabView pages = new TabView();
        Tab buttons = pages.addTab("Buttons");
        Button button = new Button("click me");
        buttons.content().append(button);
        Tab graph = pages.addTab("Graph");
        graph.content().append(new GraphView());
        window.append(pages);
        window.update(800f, 600f);

        Object colour = button.getStyle().getComputed(StylePropertyRegistry.COLOR);
        Object background = button.getStyle().getComputed(StylePropertyRegistry.BACKGROUND);
        assertNotNull("the ore theme reaches a button before the graph is opened", background);

        pages.selectTab(graph);
        window.update(800f, 600f);
        window.update(800f, 600f);

        assertEquals("opening the graph changed a button's colour", colour,
                button.getStyle().getComputed(StylePropertyRegistry.COLOR));
        assertSame("opening the graph changed a button's background", background,
                button.getStyle().getComputed(StylePropertyRegistry.BACKGROUND));
    }
}
