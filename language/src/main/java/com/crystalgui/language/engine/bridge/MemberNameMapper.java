package com.crystalgui.language.engine.bridge;

/**
 * Translates between the member names a script <b>writes</b> and the ones a class actually <b>declares</b>.
 *
 * <h3>Why a script must not be written against runtime names</h3>
 *
 * <p>On an obfuscated Minecraft a method is called {@code func_147439_a}, and its readable name —
 * {@code getBlock} — exists only in a mapping file. A script written against the runtime name breaks on
 * every version bump and is unreadable in between; a script written against the readable name has to be
 * translated at lookup. {@code plan/lang-stack.md} §16.1 states the consequence plainly: without this the
 * JavaScript engine is a developer's toy rather than something a pack can ship.</p>
 *
 * <h3>Two directions, both needed, and they are not symmetric</h3>
 *
 * <ul>
 *   <li><b>Out</b> ({@link #runtimeName}) is what makes a call work: the script says {@code getBlock} and
 *       the engine must ask the class for {@code func_147439_a}.</li>
 *   <li><b>In</b> ({@link #readableName}) is what makes the editor usable: a member list read off the class
 *       is full of runtime names, and showing those would teach the author to write them.</li>
 * </ul>
 *
 * <p>Both are needed or the feature is worse than absent — a completion list offering
 * {@code func_147439_a} beside a runtime that only accepts {@code getBlock} is an editor actively working
 * against its user.</p>
 *
 * <h3>Strings, because the child cannot see the mapping model</h3>
 *
 * <p>{@code MappingSet} lives in {@code language.map}, which is not parent-first, so the engine side cannot
 * hold one — the same reason the console arrives as a {@code Consumer} and the sandbox as a
 * {@code Predicate}. The host adapts its {@code MappingSet} to this interface; the engine knows only that
 * some names differ from others.</p>
 *
 * <h3>Internal names, not binary names</h3>
 *
 * <p>{@code net/minecraft/world/World}, with slashes. That is the form a mapping file uses and the form
 * {@code MappingSet} is keyed by, so converting here rather than at each call site keeps one spelling of a
 * class name in the mapping path — and a mapper handed {@code net.minecraft.world.World} would silently
 * match nothing, which is the failure mode that looks like "mappings are not loaded".</p>
 */
public interface MemberNameMapper {

    /** Maps nothing — what an unmapped deployment installs, and what a test uses. */
    MemberNameMapper IDENTITY = new MemberNameMapper() {
        @Override
        public String runtimeName(String ownerInternalName, String readableName) {
            return readableName;
        }

        @Override
        public String readableName(String ownerInternalName, String runtimeName) {
            return runtimeName;
        }

        @Override
        public boolean mapsAnythingIn(String ownerInternalName) {
            return false;
        }
    };

    /**
     * The name the class declares, given the name the script wrote. Never null; returns its input when
     * nothing maps.
     *
     * @param ownerInternalName the type the lookup is happening on, e.g. {@code net/minecraft/world/World}
     */
    String runtimeName(String ownerInternalName, String readableName);

    /** The name to show, given the name the class declares. Never null; returns its input when nothing maps. */
    String readableName(String ownerInternalName, String runtimeName);

    /**
     * Whether any member of this type is mapped at all.
     *
     * <p>The fast path, and it is load-bearing rather than an optimisation: this is asked on <b>every
     * property lookup a script makes</b>, including every call into an unmapped JDK class. A mapper that
     * answered by searching would put a map lookup on the hot path of {@code list.add(x)}.</p>
     */
    boolean mapsAnythingIn(String ownerInternalName);
}
