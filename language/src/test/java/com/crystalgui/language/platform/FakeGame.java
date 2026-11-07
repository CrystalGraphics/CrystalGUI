package com.crystalgui.language.platform;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgGameClasses;
import com.crystalgraphics.platform.service.CgGameDirectory;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * A running game for tests: a version, a class's bytes, and a game directory, provided through CrystalGraphics' slots
 * as a host would.
 *
 * <pre>{@code
 * FakeGame.install("1.7.10", name -> WORLD.equals(name) ? worldBytes : null, folder.getRoot().toPath());
 * // ... ScriptService.live() is true, mappings() is 1.7.10's ...
 * FakeGame.clear();   // in @After, and in @Before when an earlier class may have left one
 * }</pre>
 */
public final class FakeGame {

    private FakeGame() {
    }

    public static void install(String version, Function<String, byte[]> bytes, Path gameDirectory) {
        clear();
        CgPlatform.provide(CgGameClasses.HOST, new CgGameClasses.Host() {
            @Override
            public String minecraftVersion() {
                return version;
            }

            @Override
            public byte[] bytes(String internalName) {
                return bytes.apply(internalName);
            }
        });
        CgPlatform.provide(CgGameDirectory.HOST, new CgGameDirectory.Host() {
            @Override
            public Path game() {
                return gameDirectory;
            }

            @Override
            public Path world() {
                return null;
            }
        });
    }

    /** Back to no game. */
    public static void clear() {
        CgPlatform.provide(CgGameClasses.HOST, null);
        CgPlatform.provide(CgGameDirectory.HOST, null);
        CgGameClasses.forgetForTesting();
        CgGameDirectory.forgetForTesting();
    }
}
