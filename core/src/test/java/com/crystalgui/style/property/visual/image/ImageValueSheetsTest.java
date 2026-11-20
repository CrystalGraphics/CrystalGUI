package com.crystalgui.style.property.visual.image;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

import com.crystalgui.render.image.CgUiImage;

/**
 * Every image value the shipped sheets write: each {@code background}, {@code overlay}, {@code mask} and {@code outline}
 * reads (a token, when it reads as an image), and writes itself back to CSS that parses to an equal value.
 */
public class ImageValueSheetsTest {

    private static final Path SHEETS = Path.of("src/main/resources/assets");
    /** An image property's value, or a custom property's (a token may hold an image). */
    private static final Pattern DECLARATION =
            Pattern.compile("(?:^|[;{\\s])(background|overlay|mask|outline|--[\\w-]+)\\s*:\\s*([^;{}]+)");

    @Test
    public void everyShippedImageParsesAndWritesItselfBack() throws IOException {
        // value -> whether an image property wrote it (a token may hold anything)
        Map<String, Boolean> values = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(SHEETS)) {
            for (Path css : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".css"))::iterator) {
                String text = Files.readString(css).replaceAll("(?s)/\\*.*?\\*/", " ");
                Matcher m = DECLARATION.matcher(text);
                while (m.find()) {
                    String value = m.group(2).replace("!important", "").trim();
                    if (value.contains("var(")) continue;
                    // `outline: 1px #C1C2C2` is the width-and-colour shorthand, expanded elsewhere
                    boolean shorthand = m.group(1).equals("outline") && value.matches("-?[\\d.].*");
                    values.merge(value, !m.group(1).startsWith("--") && !shorthand, Boolean::logicalOr);
                }
            }
        }
        List<String> failures = new ArrayList<>();
        int images = 0;
        for (Map.Entry<String, Boolean> entry : values.entrySet()) {
            String value = entry.getKey();
            CgUiImage parsed = ImageValue.parse(value);
            if (parsed == null) {
                if (entry.getValue()) failures.add("did not parse: " + value);
                continue;
            }
            images++;
            String written = parsed.css();
            CgUiImage again = ImageValue.parse(written);
            if (!parsed.equals(again)) failures.add(value + " wrote " + written + ", which reads as " + again);
        }
        assertTrue("the sheets name no image at all: the walk found nothing", images > 20);
        assertTrue(images + " images, " + failures.size() + " wrong:\n" + String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void everyFormWritesItselfBack() {
        String[] forms = {
                "none", "#203040", "#20304080",
                "linear-gradient(#000000, #FFFFFF)", "linear-gradient(90deg, #00000000, #3574F033 50%, #00000000)",
                "linear-gradient(to bottom right, #FF0000, #0000FF)",
                "radial-gradient(circle at 25% 15%, #B8FFE8, #00000000 80%)",
                "radial-gradient(ellipse 60% 30% at 50% 100%, #FF0000, #00000000)",
                "grid(16, #6EDCD024)", "grid(16 24, #6EDCD024, 2)",
                "shape(\"chevron-down\")", "asset(\"crystalgui:ore\", \"button\")",
                "image(\"crystalgui:textures/x.png\", \"0 0 16 16\", #80FFFFFF)",
                "sprite(\"crystalgui:textures/x.png\", \"0 0 16 16\", \"4 4 4 4\", \"repeat round\")",
                "paint(mymod:stripes)", "cross-fade(#FF0000 40%, #0000FF)",
                "#FF0000, linear-gradient(#000000, #FFFFFF)"
        };
        for (String form : forms) {
            CgUiImage parsed = ImageValue.parse(form);
            assertNotNull(form, parsed);
            assertEquals(form, parsed, ImageValue.parse(parsed.css()));
        }
    }

    @Test
    public void coloursMixAndUnlikeValuesCrossFade() {
        CgUiImage red = ImageValue.parse("#FF0000"), blue = ImageValue.parse("#0000FF");
        assertTrue(CgUiImage.interpolate(red, blue, 0.5f) instanceof CgUiImage.Color);
        CgUiImage mark = ImageValue.parse("shape(\"cross\")");
        assertTrue(CgUiImage.interpolate(red, mark, 0.5f) instanceof CgUiImage.CrossFade);
        assertEquals(blue, CgUiImage.interpolate(red, blue, 1f));
    }
}
