package com.crystalgui.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

import org.junit.Test;

import com.crystalgraphics.platform.input.CgCursorBitmaps;

/**
 * <b>DIAGNOSTIC — prints the cursor artwork so a wrong shape can be seen instead of guessed at.</b>
 *
 * <p>Asserts nothing. Cursor art is twenty pixels of shape at 32&times;32, and the difference between a
 * rotate arrow and a smear is invisible in any assertion anyone would think to write — so the useful tool
 * is a picture. Writes an ASCII map per shape to {@code build/cursor-probe/} and a 8&times;-scaled PNG
 * beside it, the ASCII being the half that can be read straight out of a terminal.</p>
 *
 * <pre>{@code
 * ./gradlew :core:test --tests "com.crystalgui.core.CursorArtProbe"
 * cat core/build/cursor-probe/rotate.txt
 * }</pre>
 *
 * <p>Legend: {@code #} white body, {@code +} partial body, {@code O} black outline, {@code ,} partial
 * outline, {@code .} transparent. A curve should show {@code +} and {@code ,} along its edges — if it is
 * all {@code #} and {@code O} it has been flattened back to a mask.</p>
 */
public class CursorArtProbe {

    private static final Path OUT = Paths.get("build", "cursor-probe");

    @Test
    public void dumpTheCursorArtwork() throws IOException {
        Files.createDirectories(OUT);
        dump("rotate", CgCursorBitmaps.rotate());
        dump("skew", CgCursorBitmaps.skew());
        dump("pivot", CgCursorBitmaps.pivot());
        dump("four-way", CgCursorBitmaps.fourWayArrow());
    }

    private static void dump(String name, int[] art) throws IOException {
        int size = CgCursorBitmaps.SIZE;
        StringBuilder text = new StringBuilder(name + "  " + size + "x" + size
                + "   # body  + partial body  O outline  , partial outline  . clear\n\n");
        text.append("    ");
        for (int x = 0; x < size; x++) text.append(x % 10);
        text.append('\n');

        for (int y = 0; y < size; y++) {
            text.append(String.format("%3d ", y));
            for (int x = 0; x < size; x++) {
                int pixel = art[y * size + x];
                int alpha = (pixel >>> 24) & 0xFF;
                int level = pixel & 0xFF;
                if (alpha == 0) {
                    text.append('.');
                } else if (level > 128) {
                    text.append(alpha == 255 ? '#' : '+');
                } else {
                    text.append(alpha == 255 ? 'O' : ',');
                }
            }
            text.append('\n');
        }
        Files.write(OUT.resolve(name + ".txt"), text.toString().getBytes(StandardCharsets.UTF_8));

        // AND A PNG, scaled up so a human can see it at all. Nearest-neighbour on purpose: smoothing the
        // preview would hide exactly the aliasing the probe exists to show.
        final int scale = 8;
        BufferedImage image = new BufferedImage(size * scale, size * scale, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size * scale; y++) {
            for (int x = 0; x < size * scale; x++) {
                image.setRGB(x, y, art[(y / scale) * size + (x / scale)]);
            }
        }
        ImageIO.write(image, "PNG", OUT.resolve(name + ".png").toFile());
    }
}
