package com.crystalgui.language.map.format;

import com.crystalgui.language.map.MappingSet;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MCPConfig's {@code joined.tsrg} for Minecraft 1.13–1.16 — Forge's obf → SRG data, in TSRG v1.
 *
 * <pre>{@code
 * cyf net/minecraft/client/Minecraft
 * 	aa field_71432_P
 * 	a (Z)V func_71400_g
 * }</pre>
 *
 * <p>Read <b>obf → srg</b>, the runtime half of a Forge join, as {@link Tsrg2Format} is from 1.17.</p>
 *
 * <p><b>Unlike TSRG2, its class names are the runtime's</b>: Forge before 1.17 ran MCP class names with
 * SRG members, and these are those class names. So a target on this format keeps them — it must not ask
 * {@code MappingCoordinates.runtimeKeepsReadableClassNames()}.</p>
 */
public final class TsrgFormat implements MappingFormat {

    @Override
    public String id() {
        return "tsrg";
    }

    /** No header: the first line is already a class -- two columns, the second an internal name. */
    @Override
    public boolean matches(Path file) {
        try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String first = lines.readLine();
            if (first == null || first.startsWith("tsrg2 ") || first.startsWith("\t")) return false;
            String[] columns = first.trim().split(" ");
            return columns.length == 2 && columns[1].contains("/");
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    @Override
    public void parse(Path file, MappingSet.Builder into) throws IOException {
        try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String obfOwner = null;
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                if (line.trim().isEmpty()) continue;
                String[] columns = line.trim().split(" ");
                if (!line.startsWith("\t")) {
                    if (columns.length < 2) continue;
                    into.type(columns[0], columns[1]);
                    obfOwner = columns[0];
                } else if (obfOwner != null) {
                    // Two columns a field, three a method: v1 gives a field no descriptor.
                    if (columns.length == 2) into.field(obfOwner, columns[0], columns[1]);
                    else if (columns.length >= 3) into.method(obfOwner, columns[0], columns[2]);
                }
            }
        }
    }
}
