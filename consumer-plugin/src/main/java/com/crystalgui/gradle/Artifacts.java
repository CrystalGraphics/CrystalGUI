package com.crystalgui.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** Every coordinate the plugins name, at the versions this plugin was built with. */
final class Artifacts {

    static final String GUI_GROUP = "com.crystalgui";
    static final String CG_GROUP = "com.crystalgraphics";

    static final String CORE = GUI_GROUP + ":core";
    static final String TAFFY = GUI_GROUP + ":taffy";
    static final String GUI_MOD = GUI_GROUP + ":crystalgui";
    static final String LANGUAGE_MOD = GUI_GROUP + ":crystalgui-language";
    static final String CG_MOD = CG_GROUP + ":crystalgraphics";
    static final String CG_JOML_MOD = CG_GROUP + ":crystalgraphics-joml";

    /** Minecraft ships JOML from here on; below it the companion mod supplies it. */
    static final String FIRST_WITH_JOML = "1.19.3";

    /** What a headless test runs on that the game would otherwise supply. */
    static final String LOG4J_CORE = "org.apache.logging.log4j:log4j-core:2.26.1";
    static final String COMMONS_IO = "commons-io:commons-io:2.4";

    static final String GUI_VERSION;
    static final String CG_VERSION;

    static {
        Properties versions = new Properties();
        try (InputStream in = Artifacts.class.getResourceAsStream("versions.properties")) {
            versions.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        GUI_VERSION = versions.getProperty("crystalgui");
        CG_VERSION = versions.getProperty("crystalgraphics");
    }

    private Artifacts() {
    }

    /** {@code coordinate} at the version its group ships at. */
    static String at(String coordinate) {
        return coordinate + ":" + (coordinate.startsWith(CG_GROUP) ? CG_VERSION : GUI_VERSION);
    }
}
