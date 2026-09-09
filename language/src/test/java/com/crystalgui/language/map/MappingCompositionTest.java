package com.crystalgui.language.map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Joining two published artifacts through the obfuscated namespace they share.
 *
 * <p>Every artifact maps <em>away</em> from obf, and no runtime speaks obf — so the mapping a 1.20.x
 * host needs is always a composition, never a download. The two shapes below are the two that ship.</p>
 */
public class MappingCompositionTest {

    /** Mojang's {@code client.txt}: obf → official. The same second stage for every loader. */
    private static MappingSet obfToOfficial() {
        return MappingSet.builder()
                .type("dhg", "net/minecraft/world/level/Level")
                .type("fx", "net/minecraft/core/BlockPos")
                .method("dhg", "a", "getBlockState")
                .method("dhg", "b", "isClientSide")
                .field("dhg", "c", "random")
                .build();
    }

    /**
     * MCPConfig's srg namespace, spelled as it really is: {@code net/minecraft/src/C_NNNN_}.
     *
     * <p>An earlier version of this fixture put OFFICIAL class names in this column, because that is
     * what the code assumed. It is not what the file says — {@code enn net/minecraft/src/C_3391_ 3391}
     * — and writing the assumption into the fixture is what let twenty-four green tests agree with a
     * mapping that could not link a single script.</p>
     */
    private static MappingSet obfToSrg() {
        return MappingSet.builder()
                .type("dhg", "net/minecraft/src/C_3391_")
                .type("fx", "net/minecraft/src/C_4675_")
                .method("dhg", "a", "m_8055_")
                .method("dhg", "b", "m_5776_")
                .field("dhg", "c", "f_46441_")
                .build();
    }

    /**
     * Forge 1.20.1: official class names, SRG members — and the composed mapping must come out in that
     * mix, because that is what the runtime is.
     *
     * <p>The join alone does not produce it. MCPConfig has a class vocabulary of its own that Forge
     * does not run, so the raw composition keys everything under {@code C_3391_};
     * {@link MappingSet#withoutClassRenames()} is what moves the owners into the readable namespace
     * and drops the class table.</p>
     */
    @Test
    public void forgeComposesToOfficialClassesWithSrgMembers() {
        MappingSet forge = obfToSrg().invert().then(obfToOfficial()).withoutClassRenames();

        String level = "net/minecraft/world/level/Level";
        assertEquals("getBlockState", forge.readableMethod(level, "m_8055_"));
        assertEquals("isClientSide", forge.readableMethod(level, "m_5776_"));
        assertEquals("random", forge.readableField(level, "f_46441_"));
        assertEquals("a class answers as itself", level, forge.readableClass(level));
    }

    /**
     * The failure that shipped: a script naming {@code Minecraft} linked against {@code C_3391_}.
     *
     * <p>{@code runtimeClass} is what rewrites a compiled script's class references, so a class table
     * carried over from MCPConfig sends it to a name no runtime has —
     * {@code NoClassDefFoundError: net/minecraft/src/C_3391_}, on the line that named the class.</p>
     */
    @Test
    public void aForgeRuntimeClassNameIsNeverMcpConfigsOwn() {
        MappingSet forge = obfToSrg().invert().then(obfToOfficial()).withoutClassRenames();

        String level = "net/minecraft/world/level/Level";
        assertEquals("the runtime has this class under this very name", level, forge.runtimeClass(level));
        assertEquals("and the member is still SRG", "m_8055_", forge.runtimeMethod(level, "getBlockState"));
    }

    /** Fabric renames classes for real, so the same treatment there would throw away half the mapping. */
    @Test
    public void dropingClassRenamesIsWrongForARuntimeThatRenamesThem() {
        MappingSet obfToIntermediary = MappingSet.builder()
                .type("dhg", "net/minecraft/class_1937")
                .method("dhg", "a", "method_8320")
                .build();

        MappingSet fabric = obfToIntermediary.invert().then(obfToOfficial());

        assertEquals("net/minecraft/world/level/Level",
                fabric.readableClass("net/minecraft/class_1937"));
        assertEquals("getBlockState",
                fabric.readableMethod("net/minecraft/class_1937", "method_8320"));
    }

    /** Fabric: intermediary classes AND members, so both halves genuinely rename. */
    @Test
    public void fabricComposesFromIntermediaryInBothHalves() {
        MappingSet obfToIntermediary = MappingSet.builder()
                .type("dhg", "net/minecraft/class_1937")
                .type("fx", "net/minecraft/class_2338")
                .method("dhg", "a", "method_8320")
                .field("dhg", "c", "field_9229")
                .build();

        MappingSet intermediaryToOfficial = obfToIntermediary.invert().then(obfToOfficial());

        assertEquals("net/minecraft/world/level/Level",
                intermediaryToOfficial.readableClass("net/minecraft/class_1937"));
        assertEquals("getBlockState",
                intermediaryToOfficial.readableMethod("net/minecraft/class_1937", "method_8320"));
        assertEquals("random",
                intermediaryToOfficial.readableField("net/minecraft/class_1937", "field_9229"));
    }

    /**
     * The unqualified tier is what every TEXT scan reads — the Remap command included.
     *
     * <p>Without it a 1.20.x mapping is complete and invisible: {@code ReadableSource} has a name and a
     * dot and no owner, so it asks {@code readableMethodAnywhere} and gets the SRG name straight back.</p>
     */
    @Test
    public void aJoinedMappingAnswersWithoutAnOwner() {
        MappingSet obfToSrg = MappingSet.builder()
                .type("dhg", "net/minecraft/world/level/Level")
                .method("dhg", "a", "m_8055_")
                .field("dhg", "c", "f_46441_")
                .build();

        MappingSet joined = obfToSrg.invert().then(obfToOfficial()).withUnqualifiedMembers();

        assertEquals("getBlockState", joined.readableMethodAnywhere("m_8055_"));
        assertEquals("random", joined.readableFieldAnywhere("f_46441_"));
        assertEquals("the owner-keyed tier still answers",
                "getBlockState", joined.readableMethod("net/minecraft/world/level/Level", "m_8055_"));
    }

    /**
     * A runtime name two owners disagree about is LEFT OUT rather than guessed.
     *
     * <p>SRG and intermediary names are unique by construction, so this should never fire on real data —
     * which is exactly why it is measured rather than trusted. Renaming the wrong member is silent.</p>
     */
    @Test
    public void anAmbiguousRuntimeNameIsNotOfferedWithoutAnOwner() {
        MappingSet obfToRuntime = MappingSet.builder()
                .type("aa", "net/minecraft/One")
                .type("bb", "net/minecraft/Two")
                .method("aa", "x", "shared_")
                .method("bb", "y", "shared_")
                .method("aa", "z", "agreed_")
                .method("bb", "w", "agreed_")
                .build();
        MappingSet toReadable = MappingSet.builder()
                .type("aa", "net/minecraft/One")
                .type("bb", "net/minecraft/Two")
                .method("aa", "x", "first")
                .method("bb", "y", "second")
                .method("aa", "z", "same")
                .method("bb", "w", "same")
                .build();

        MappingSet joined = obfToRuntime.invert().then(toReadable).withUnqualifiedMembers();

        assertEquals("two owners disagree, so there is no answer without one",
                "shared_", joined.readableMethodAnywhere("shared_"));
        assertEquals("first", joined.readableMethod("net/minecraft/One", "shared_"));
        assertEquals("two owners agreeing is not a conflict",
                "same", joined.readableMethodAnywhere("agreed_"));
    }

    /** The out direction is what makes a compiled script LINK, so it is asserted rather than assumed. */
    @Test
    public void composedMappingAnswersBackToTheRuntime() {
        MappingSet obfToSrg = MappingSet.builder()
                .type("dhg", "net/minecraft/world/level/Level")
                .method("dhg", "a", "m_8055_")
                .build();

        MappingSet srgToOfficial = obfToSrg.invert().then(obfToOfficial());

        assertEquals("m_8055_",
                srgToOfficial.runtimeMethod("net/minecraft/world/level/Level", "getBlockState"));
    }

    /** A second stage that does not carry a name leaves the first stage's answer rather than emptying it. */
    @Test
    public void namesTheSecondStageDoesNotCarryPassThrough() {
        MappingSet obfToSrg = MappingSet.builder()
                .type("dhg", "net/minecraft/world/level/Level")
                .method("dhg", "zz", "m_9999_")
                .build();

        MappingSet composed = obfToSrg.invert().then(obfToOfficial());

        assertEquals("m_9999_ has no official name here, so it stays as it is",
                "m_9999_", composed.readableMethod("net/minecraft/world/level/Level", "m_9999_"));
    }

    /**
     * A second stage that knows nothing yields NOTHING -- never the obfuscated namespace.
     *
     * <p>The tempting law is {@code then(IDENTITY) == this}, and taking it would make every name resolve
     * to its obf spelling: {@code m_8055_} would be shown to a script author as {@code a}. Runtime names
     * shown as they are is the supported degraded state; obf names dressed as readable ones is not.</p>
     */
    @Test
    public void aSecondStageThatKnowsNothingMapsNothing() {
        MappingSet obfToSrg = MappingSet.builder()
                .type("dhg", "net/minecraft/world/level/Level")
                .method("dhg", "a", "m_8055_")
                .build();

        MappingSet composed = obfToSrg.invert().then(MappingSet.IDENTITY);

        assertTrue("a join onto nothing is nothing", composed.isIdentity());
        assertEquals("m_8055_",
                composed.readableMethod("net/minecraft/world/level/Level", "m_8055_"));
    }
}
