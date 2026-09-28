package com.crystalgui.language.java;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.language.java.classpath.TypeIndex;
import com.crystalgui.language.platform.ScriptServices;
import com.crystalgui.mc.fabric.v1165.common.lang.NodeScriptService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

/**
 * A merged jar's host classes are offered from the running node only.
 *
 * <p>The jar carries each host class once per (loader, Minecraft version), relocated into that node's
 * package, so typing {@code Minecraft} offered 54 {@code MinecraftBytes}. The running node is the one the
 * registered {@code ScriptService} sits in — here Fabric 1.16.5.</p>
 */
public class NodeCopiesTypeIndexTest {

    private Path classpath;

    @Before
    public void jar() throws IOException {
        classpath = Files.createTempDirectory("node-copies");
        for (String type : List.of(
                "com/crystalgui/mc/fabric/v1165/common/lang/MinecraftBytes",
                "com/crystalgui/mc/fabric/v1201/common/lang/MinecraftBytes",
                "com/crystalgui/mc/forge/v1165/common/lang/MinecraftBytes",
                "com/crystalgui/mc/v1122/lang/MinecraftBytes",
                "com/crystalgraphics/mc/modern/fabric/v1165/common/platform/Windows",
                "com/crystalgraphics/mc/modern/fabric/v1201/common/platform/Windows",
                "com/crystalgui/mc/shared/CrystalGuiForgeMixins")) {
            Path file = classpath.resolve(type + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[0]);
        }
    }

    @After
    public void restore() {
        CgPlatform.provide(ScriptServices.SERVICE, null);
    }

    private List<String> found(String query) {
        return new TypeIndex(List.of(classpath.toString())).matching(query).entries().stream()
                .map(TypeIndex.Entry::qualifiedName).filter(name -> name.startsWith("com.crystal"))
                .sorted().collect(Collectors.toList());
    }

    /** Both mods' copies of other nodes go; a package that is no node's stays. */
    @Test
    public void onlyTheRunningNodesCopiesAreOffered() {
        CgPlatform.provide(ScriptServices.SERVICE, new NodeScriptService());

        assertEquals(List.of("com.crystalgui.mc.fabric.v1165.common.lang.MinecraftBytes"), found("MinecraftBytes"));
        assertEquals(List.of("com.crystalgraphics.mc.modern.fabric.v1165.common.platform.Windows"), found("Windows"));
        assertEquals(1, found("CrystalGuiForgeMixins").size());
    }

    /** Outside a merged jar the service is in no node, and every copy stays. */
    @Test
    public void withNoRunningNodeNothingIsHidden() {
        assertEquals(4, found("MinecraftBytes").size());
    }
}
