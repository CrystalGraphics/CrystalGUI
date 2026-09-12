package com.crystalgui.render.text;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontFamilyGroup;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.text.CgBakedGlyphs;
import com.crystalgraphics.api.text.CgStyleSpan;
import com.crystalgraphics.api.text.CgStyledText;
import com.crystalgraphics.api.text.CgTextDecoration;
import com.crystalgraphics.api.text.CgTextLayout;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * A span that changes nothing the shaper produces must not move a single glyph.
 *
 * <p>A decoration is applied to already-shaped glyphs, so underlining one character may not cause it
 * to be shaped apart from its neighbours — a kern is a GPOS adjustment between two glyphs in one
 * buffer, and there is no second glyph in the other buffer. It used to, and the label grew: the menu
 * bar's mnemonic underlines widened {@code Edit} by 0.0098em the moment Alt was pressed, which pushed
 * every title after it off the pixel grid.
 *
 * <p>IBM Plex Sans rather than the layout engine's own test font, because this needs a face with a
 * real kern pair in a real label — {@code Ed} is the only one of the six main-menu mnemonics that has
 * one, which is exactly why the symptom looked like it belonged to {@code View} and {@code Window}.
 */
public class StyleSpanKerningTest {

    private static final String FONT = "/assets/crystalgui/ui/fonts/IBMPlexSans-Regular.ttf";

    @Test
    public void decorationOnlySpanDoesNotReshape() throws Exception {
        for (int px : new int[] {12, 16, 24}) {
            CgFont font = CgFont.load(fontBytes(), "plex-" + px, CgFontStyle.REGULAR, px);
            try {
                CgFontFamilyGroup group = CgFontFamilyGroup.ofRegular(CgFontFamily.of(font));
                for (String label : new String[] {"File", "Edit", "View", "Graph", "Window", "Help"}) {
                    CgBakedGlyphs plain = bake(CgStyledText.plain(label), group);
                    CgBakedGlyphs underlined = bake(new CgStyledText(label, List.of(
                            CgStyleSpan.builder().start(0).end(1)
                                    .decorations(Set.of(CgTextDecoration.UNDERLINE)).build())), group);

                    assertEquals(label + " @" + px + "px: glyph count must not change",
                            plain.glyphCount(), underlined.glyphCount());
                    for (int i = 0; i < plain.glyphCount(); i++) {
                        assertEquals(label + " @" + px + "px: glyph " + i + " must not move",
                                plain.penX()[i], underlined.penX()[i], 0f);
                    }
                }
            } finally {
                font.dispose();
            }
        }
    }

    private static CgBakedGlyphs bake(CgStyledText text, CgFontFamilyGroup group) {
        return CgTextLayout.of(text, group).build().baked();
    }

    private static byte[] fontBytes() throws Exception {
        try (InputStream in = StyleSpanKerningTest.class.getResourceAsStream(FONT)) {
            assertNotNull("the UI face must be on the test classpath", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }
}
