package com.crystalgui.language.map;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A {@code $}-named class is presented as the MEMBER it is.
 *
 * <h3>Obfuscation flattens nesting and renaming does not put it back</h3>
 *
 * <p>In an obfuscated 1.7.10 jar an inner class has a top-level Notch name — {@code avf.class}, no dollar
 * anywhere — and no {@code InnerClasses} attribute tying it to anything. The deobfuscating transformer
 * restores the NAME and not the RELATIONSHIP, so every consumer reasonably concludes it is a top-level
 * class whose simple name contains a dollar.</p>
 *
 * <p>Each consumer then failed in its own way, and none of the failures pointed here. The decompiler
 * emitted {@code import net.minecraft.world.WorldSettings$GameType;} and a field typed
 * {@code WorldSettings$GameType} — text that is not valid Java. The compiler refused
 * {@code import …WorldSettings.GameType} because no member type of that name existed. Go To File had
 * nothing to list. And the class loaded and ran the whole time, because a JVM asks for a binary name and
 * never reads this attribute — so the editor appeared unable to see a class it was executing.</p>
 */
public class NestingRestoredTest {

    /** A class named as a member, carrying no {@code InnerClasses} attribute — the obfuscated shape. */
    private static byte[] flattenedInner() {
        ClassWriter writer = new ClassWriter(0);
        // AN ENUM, because the flags are half of what the entry carries and a plain class cannot tell a
        // preserved flag from a hardcoded one.
        writer.visit(Opcodes.V1_8,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_ENUM,
                "demo/WorldSettings$GameType", null, "java/lang/Enum", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** An anonymous class, which has no simple name and must NOT acquire an invented one. */
    private static byte[] anonymous() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "demo/WorldSettings$1", null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A class that already says what it is — an ordinary jar, which must pass through untouched. */
    private static byte[] alreadyDeclared() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "demo/Outer$Inner", null, "java/lang/Object", null);
        writer.visitInnerClass("demo/Outer$Inner", "demo/Outer", "Inner",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** The OUTER class, holding a field of the flattened inner type — no attribute, as obfuscation left it. */
    private static byte[] outerReferencing() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                "demo/WorldSettings", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "theGameType",
                "Ldemo/WorldSettings$GameType;", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ReadableView viewOver(byte[] bytes, String internalName) {
        return new ReadableView(MappingSet.IDENTITY,
                name -> internalName.equals(name) ? bytes : null);
    }

    /** Every {@code InnerClasses} entry a class file declares, as {@code inner|outer|simple}. */
    private static List<String> nestingOf(byte[] bytes) {
        List<String> found = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visitInnerClass(String inner, String outer, String simple, int access) {
                found.add(inner + "|" + outer + "|" + simple);
            }
        }, 0);
        return found;
    }

    /**
     * <b>The relationship is restored.</b>
     *
     * <p>Asserted on the attribute rather than on decompiled text, because the attribute is what every
     * consumer reads — the decompiler, the compiler and the index each fail differently from its absence,
     * and all three are downstream of this one fact.</p>
     */
    @Test
    public void aFlattenedInnerClassIsDeclaredAMember() throws IOException {
        byte[] view = viewOver(flattenedInner(), "demo/WorldSettings$GameType")
                .readableBytesOf("demo/WorldSettings$GameType");

        assertEquals(List.of("demo/WorldSettings$GameType|demo/WorldSettings|GameType"),
                nestingOf(view));
    }

    /** The compile view gets it too — that is the one the name environment reads. */
    @Test
    public void theCompileViewCarriesItAsWell() throws IOException {
        byte[] view = viewOver(flattenedInner(), "demo/WorldSettings$GameType")
                .compilableBytesOf("demo/WorldSettings$GameType");

        assertEquals(List.of("demo/WorldSettings$GameType|demo/WorldSettings|GameType"),
                nestingOf(view));
    }

    /**
     * <b>An anonymous class is left alone.</b>
     *
     * <p>{@code Outer$1} has no simple name — the attribute takes null there, which is a different
     * statement from the one being repaired. Inventing {@code "1"} would declare a member nobody can
     * write, which is the shape that once filled a completion list with {@code Minecraft$1} through
     * {@code Minecraft$16}.</p>
     */
    @Test
    public void anAnonymousClassIsNotGivenAName() throws IOException {
        byte[] view = viewOver(anonymous(), "demo/WorldSettings$1")
                .readableBytesOf("demo/WorldSettings$1");

        assertTrue("an anonymous class was declared a named member: " + nestingOf(view),
                nestingOf(view).isEmpty());
    }

    /**
     * <b>The OUTER class declares the member it references.</b>
     *
     * <p>The first version of this repair declared the nesting on the MEMBER's own file alone, which is
     * half of what javac writes: every class that mentions a nested type carries the entry. So
     * {@code WorldSettings} went on describing a field of type {@code WorldSettings$GameType} while the
     * member itself claimed to be {@code WorldSettings.GameType} — the two disagreed about one type, and
     * a decompiler had no consistent name left to print.</p>
     *
     * <p>The reference set comes from the remapper, which is called for every internal name a class file
     * mentions — its own, its supertypes, every descriptor and every signature — so this needs no second
     * pass and cannot miss a mention the rewrite itself saw.</p>
     */
    @Test
    public void anOuterClassDeclaresTheMemberItReferences() throws IOException {
        byte[] view = viewOver(outerReferencing(), "demo/WorldSettings")
                .readableBytesOf("demo/WorldSettings");

        assertEquals(List.of("demo/WorldSettings$GameType|demo/WorldSettings|GameType"),
                nestingOf(view));
    }

    /**
     * <b>An enum is declared an ENUM.</b>
     *
     * <p>A compiler reads a member type's modifiers from the {@code InnerClasses} entry rather than from
     * the class's own header, so an entry written as a flat {@code public static} makes an enum resolve
     * as a class extending {@code Enum}. It surfaced as one type describing itself two ways: hovering
     * {@code GameType} in a source file said <i>class ... extends Enum</i>, while the decompiled view —
     * which reads generated text that literally says {@code enum} — had it right.</p>
     *
     * <p>{@code ACC_STATIC} is asserted beside it because it is the one flag ADDED rather than copied: it
     * never appears in a class's own header, only in the entry that declares the nesting. And
     * {@code ACC_SUPER} is asserted ABSENT, because it is set on nearly every class file and means
     * nothing here — copying the header wholesale would put a bit in a field that does not define one.</p>
     */
    @Test
    public void anEnumMemberKeepsItsEnumFlag() throws IOException {
        int flags = accessOfEntry(viewOver(flattenedInner(), "demo/WorldSettings$GameType")
                .readableBytesOf("demo/WorldSettings$GameType"));

        assertTrue("the entry lost ACC_ENUM, so an enum resolves as a plain class",
                (flags & Opcodes.ACC_ENUM) != 0);
        assertTrue("a member entry must say static", (flags & Opcodes.ACC_STATIC) != 0);
        assertTrue("the enum's own final did not survive", (flags & Opcodes.ACC_FINAL) != 0);
        assertEquals("ACC_SUPER is meaningless on a member entry and must not be copied",
                0, flags & Opcodes.ACC_SUPER);
    }

    /** The flags on the first {@code InnerClasses} entry. */
    private static int accessOfEntry(byte[] bytes) {
        int[] flags = {0};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visitInnerClass(String inner, String outer, String simple, int access) {
                if (flags[0] == 0) flags[0] = access;
            }
        }, 0);
        return flags[0];
    }

    /** A class that already declares itself keeps exactly what it had — no second entry. */
    @Test
    public void anOrdinaryJarIsUntouched() throws IOException {
        byte[] view = viewOver(alreadyDeclared(), "demo/Outer$Inner")
                .readableBytesOf("demo/Outer$Inner");

        assertEquals(List.of("demo/Outer$Inner|demo/Outer|Inner"), nestingOf(view));
    }
}
