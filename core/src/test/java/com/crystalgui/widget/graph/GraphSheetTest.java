package com.crystalgui.widget.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.control.Button;

/**
 * <b>Opening a graph must not restyle the window it opened in.</b>
 *
 * <p>Reported from the gallery: visiting the graph page left every other page unstyled. A widget that
 * changes the cascade for its whole document is the worst kind of coupling there is, because nothing
 * about the symptom points at what caused it.</p>
 */
public class GraphSheetTest {

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
        UIElementRegistry.bootstrap();
        UIDocument window = new UIDocument();
        window.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.styleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:graph"));
        window.styleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));

        Button button = new Button("click me");
        window.append(button);
        window.update(800f, 600f);
        Object before = button.getStyle().getComputed(StylePropertyRegistry.COLOR);
        int sheetsBefore = window.styleEngine().getSheets().size();

        window.append(new GraphView());
        window.update(800f, 600f);

        assertEquals("a graph added no sheet of its own", sheetsBefore,
                window.styleEngine().getSheets().size());
        assertEquals("and changed nothing about what was already styled", before,
                button.getStyle().getComputed(StylePropertyRegistry.COLOR));
        assertNotNull("the ore theme still reaches a button", 
                button.getStyle().getComputed(StylePropertyRegistry.BACKGROUND));
    }
}
