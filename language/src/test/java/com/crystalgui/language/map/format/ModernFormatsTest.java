package com.crystalgui.language.map.format;

import com.crystalgui.language.map.MappingSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The two formats a Forge 1.20.1 runtime needs, and the join they exist for.
 *
 * <p>Fixtures are real excerpts rather than invented rows: the details that break a parser are the
 * licence header, the line-number prefix, the two-tab parameter line and the descriptor-less field.</p>
 */
public class ModernFormatsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** Mojang's file opens with a licence block and uses dotted names and line-number prefixes. */
    private static final String CLIENT_TXT = String.join("\n",
            "# (C) 2023 Mojang AB and Microsoft Corporation",
            "# The contents of this file are subject to a licence.",
            "net.minecraft.world.level.Level -> dhg:",
            "    net.minecraft.util.RandomSource random -> c",
            "    boolean isClientSide -> d",
            "    12:34:net.minecraft.world.level.block.state.BlockState getBlockState("
                    + "net.minecraft.core.BlockPos) -> a",
            "    56:78:boolean isClientSide() -> b",
            "net.minecraft.core.BlockPos -> fx:",
            "    int getX() -> u");

    /** MCPConfig's file: tabs nest, and its srg CLASS names are {@code net/minecraft/src/C_NNNN_}. */
    private static final String JOINED_TSRG = String.join("\n",
            "tsrg2 obf srg",
            "dhg net/minecraft/world/level/Level",
            "\ta (Lfx;)Ldkr; m_8055_",
            "\tb ()Z m_5776_",
            "\tc Ldsx; f_46441_",
            "\td f_46443_",
            "\tm_ignored_ (I)V m_9999_",
            "\t\t0 p_49832_ pos",
            "\t\tstatic",
            "fx net/minecraft/core/BlockPos",
            "\tu ()I m_123341_");

    private Path write(String name, String content) throws IOException {
        Path file = folder.newFile(name).toPath();
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * Fabric's intermediary, with the two nested records that break a naive reader: a {@code c} comment
     * under a method, and a member the second namespace does not rename.
     */
    private static final String MAPPINGS_TINY = String.join("\n",
            "tiny\t2\t0\tofficial\tintermediary",
            "c\tdhg\tnet/minecraft/class_1937",
            "\tm\t(Lfx;)Ldkr;\ta\tmethod_8320",
            "\t\tc\tGets the block state at a position.",
            "\tf\tLdsx;\tc\tfield_9229",
            "\tf\tLdsy;\te\t",
            "c\tfx\tnet/minecraft/class_2338");

    @Test
    public void tinyV2ReadsObfToIntermediaryAndSkipsNestedRecords() throws IOException {
        MappingSet.Builder builder = MappingSet.builder();
        new TinyV2Format().parse(write("mappings.tiny", MAPPINGS_TINY), builder);
        MappingSet obfToIntermediary = builder.build();

        assertEquals("net/minecraft/class_1937", obfToIntermediary.readableClass("dhg"));
        assertEquals("method_8320", obfToIntermediary.readableMethod("dhg", "a"));
        assertEquals("field_9229", obfToIntermediary.readableField("dhg", "c"));
        assertEquals("a comment nested under a method is not a class",
                "Gets the block state at a position.",
                obfToIntermediary.readableClass("Gets the block state at a position."));
        assertEquals("a blank second name is not a rename",
                "e", obfToIntermediary.readableField("dhg", "e"));
        assertEquals("net/minecraft/class_2338", obfToIntermediary.readableClass("fx"));
    }

    /** Fabric's own join, end to end: two published files in, what a Fabric runtime needs out. */
    @Test
    public void tinyAndProGuardJoinIntoWhatAFabricRuntimeNeeds() throws IOException {
        MappingSet.Builder official = MappingSet.builder();
        new ProGuardFormat().parse(write("client.txt", CLIENT_TXT), official);
        MappingSet.Builder intermediary = MappingSet.builder();
        new TinyV2Format().parse(write("mappings.tiny", MAPPINGS_TINY), intermediary);

        MappingSet toOfficial = intermediary.build().invert().then(official.build());

        assertEquals("net/minecraft/world/level/Level",
                toOfficial.readableClass("net/minecraft/class_1937"));
        assertEquals("getBlockState",
                toOfficial.readableMethod("net/minecraft/class_1937", "method_8320"));
        assertEquals("random", toOfficial.readableField("net/minecraft/class_1937", "field_9229"));
    }

    @Test
    public void eachFormatRecognisesOnlyItsOwnFile() throws IOException {
        Path proguard = write("client.txt", CLIENT_TXT);
        Path tsrg = write("joined.tsrg", JOINED_TSRG);

        assertTrue(new ProGuardFormat().matches(proguard));
        assertFalse(new ProGuardFormat().matches(tsrg));
        assertTrue(new Tsrg2Format().matches(tsrg));
        assertFalse(new Tsrg2Format().matches(proguard));

        Path tiny = write("mappings.tiny", MAPPINGS_TINY);
        assertTrue(new TinyV2Format().matches(tiny));
        assertFalse(new TinyV2Format().matches(tsrg));
        assertFalse("tsrg2 and tiny both start with a magic word; neither may claim the other",
                new Tsrg2Format().matches(tiny));
    }

    /** Content, never file name — the whole point of the {@code matches} contract. */
    @Test
    public void formatsAreChosenByContentNotByName() throws IOException {
        Path misnamed = write("mappings.txt", JOINED_TSRG);
        assertEquals("tsrg2", MappingFiles.formatOf(misnamed).id());
    }

    @Test
    public void proGuardReadsObfToOfficialThroughItsHeaderAndLineNumbers() throws IOException {
        MappingSet.Builder builder = MappingSet.builder();
        new ProGuardFormat().parse(write("client.txt", CLIENT_TXT), builder);
        MappingSet obfToOfficial = builder.build();

        assertEquals("net/minecraft/world/level/Level", obfToOfficial.readableClass("dhg"));
        assertEquals("the line-number prefix is not part of the signature",
                "getBlockState", obfToOfficial.readableMethod("dhg", "a"));
        assertEquals("random", obfToOfficial.readableField("dhg", "c"));
        assertEquals("a field and a method may share a readable name",
                "isClientSide", obfToOfficial.readableField("dhg", "d"));
        assertEquals("isClientSide", obfToOfficial.readableMethod("dhg", "b"));
    }

    @Test
    public void tsrg2ReadsObfToSrgAndSkipsParameterLines() throws IOException {
        MappingSet.Builder builder = MappingSet.builder();
        new Tsrg2Format().parse(write("joined.tsrg", JOINED_TSRG), builder);
        MappingSet obfToSrg = builder.build();

        assertEquals("net/minecraft/world/level/Level", obfToSrg.readableClass("dhg"));
        assertEquals("m_8055_", obfToSrg.readableMethod("dhg", "a"));
        assertEquals("a two-column line is a field with no descriptor",
                "f_46443_", obfToSrg.readableField("dhg", "d"));
        assertEquals("f_46441_", obfToSrg.readableField("dhg", "c"));
        assertEquals("a two-tab parameter line is not a member",
                "pos", obfToSrg.readableField("dhg", "pos"));
    }

    /**
     * The whole point, end to end: two published files in, the mapping a Forge runtime needs out.
     *
     * <p>Classes come out as the identity because both halves already agree on official class names,
     * and members come out SRG→official — which together is exactly what that runtime is.</p>
     */
    @Test
    public void thetwoFilesJoinIntoWhatAForgeRuntimeNeeds() throws IOException {
        MappingSet.Builder official = MappingSet.builder();
        new ProGuardFormat().parse(write("client.txt", CLIENT_TXT), official);
        MappingSet.Builder srg = MappingSet.builder();
        new Tsrg2Format().parse(write("joined.tsrg", JOINED_TSRG), srg);

        MappingSet srgToOfficial = srg.build().invert().then(official.build());

        String level = "net/minecraft/world/level/Level";
        assertEquals(level, srgToOfficial.readableClass(level));
        assertEquals("getBlockState", srgToOfficial.readableMethod(level, "m_8055_"));
        assertEquals("isClientSide", srgToOfficial.readableMethod(level, "m_5776_"));
        assertEquals("random", srgToOfficial.readableField(level, "f_46441_"));
        assertEquals("getX", srgToOfficial.readableMethod("net/minecraft/core/BlockPos", "m_123341_"));

        assertEquals("an SRG name with no official counterpart stays as it is, never as its obf name",
                "m_9999_", srgToOfficial.readableMethod(level, "m_9999_"));

        assertEquals("and back again, which is what makes a compiled script link",
                "m_8055_", srgToOfficial.runtimeMethod(level, "getBlockState"));
    }

    /** An unparseable file is skipped by every format rather than refused by one. */
    @Test
    public void somethingElseEntirelyIsNobodys() throws IOException {
        Path readme = write("README.md", "# Mappings\n\nDownloaded by the build.\n");
        assertSame(null, MappingFiles.formatOf(readme));
    }
}
