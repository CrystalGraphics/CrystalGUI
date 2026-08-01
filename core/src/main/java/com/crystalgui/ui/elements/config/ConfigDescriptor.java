package com.crystalgui.ui.elements.config;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <b>What to build, not how.</b> A description of one editable value, from which
 * {@link ConfigControls} produces a control.
 *
 * <h3>Why a descriptor rather than a Java type</h3>
 * <p>LDLib2 dispatches on the reflected field's {@code Class}, which is the right answer when a
 * reflection driver is doing the asking. Nothing here reflects over anything yet — the first consumer
 * is a shader node library whose fields are <em>data</em>, read out of a document that a dedicated
 * server may have authored with no Java type behind it at all. Dispatching on a declared {@link Kind}
 * serves both: a future reflection driver maps {@code float.class -> NUMBER} once, in one place, and
 * every control stays unaware that reflection exists.</p>
 *
 * <h3>The metadata is per INSTANCE, and that is the point</h3>
 * <p>Unity's Slider node authors its own {@code Min}/{@code Max} on the node, not on the node type;
 * LDLib2 spells the same thing as {@code @ConfigNumber(min, max)}. Two systems built independently
 * arriving at per-instance range is the evidence that it belongs here rather than in a registry of
 * kinds. The same applies to {@link #options()} — two dropdowns of the same kind rarely offer the same
 * choices.</p>
 */
public final class ConfigDescriptor {

    /**
     * The shape of a value — never the control that edits it.
     *
     * <p>Deliberately a small closed set. A consumer wanting a colour wheel instead of a text box
     * registers a factory for {@link #COLOR}; it does not invent a kind. A new kind makes every
     * document written before it unreadable, which is a much worse trade than a control that is
     * temporarily plainer than it could be.</p>
     */
    public enum Kind {
        /** Free text. Pair with {@link #validator()} to constrain it. */
        TEXT,
        /** One number. {@link #range()} turns it into a slider; {@link #integral()} into a step of 1. */
        NUMBER,
        /** 2, 3 or 4 numbers with X/Y/Z/W sub-labels — {@link #arity()} says how many. */
        VECTOR,
        BOOLEAN,
        /** One of {@link #options()}. */
        SELECT,
        /** Any number of {@link #options()}. */
        MASK,
        /** ARGB. {@link #hdr()} allows an intensity above 1. */
        COLOR,
        /** {@link #arity()} squared numbers, unlabelled, in a grid. */
        MATRIX,
        /** A resource path plus a picker. */
        ASSET,
        /** Colour stops. */
        GRADIENT,
        /** A repeated value — {@link #element()} describes one entry. */
        ARRAY,
        /** Structure, not a value: a foldout owning children. */
        GROUP,
        /**
         * Structure, not a value: a full-width banded caption with no control and no arrow.
         *
         * <p>Unity's {@code Target Settings} in {@code docs/research/unity-inspector/07-full-window.png}
         * — a plain section break, unlike {@link #GROUP} which collapses. There is no {@code font-weight}
         * in this engine's CSS (see {@code StylePropertyRegistry}), so the band itself — the same surface
         * change a group's head uses — carries the separation, not a bold glyph. Modelled as a
         * self-labelling {@link ConfigControl} rather than special-cased in {@link ConfiguratorPanel}, so
         * it goes through the same registry, row and kit-height machinery every other kind does.</p>
         */
        HEADER
    }

    /** An inclusive numeric range. {@code null} anywhere means "no bound stated". */
    public record Range(float min, float max) {
    }

    private final String id;
    private final String label;
    private final Kind kind;
    private String tooltip;
    private List<String> options = Collections.emptyList();
    private Range range;
    private int arity = 3;
    private boolean integral;
    private boolean hdr;
    private java.util.function.Predicate<String> validator;
    private ConfigDescriptor element;
    private final List<ConfigDescriptor> children = new ArrayList<>();

    private ConfigDescriptor(String id, String label, Kind kind) {
        this.id = id;
        this.label = label;
        this.kind = kind;
    }

    public static ConfigDescriptor of(String id, String label, Kind kind) {
        return new ConfigDescriptor(id, label, kind);
    }

    public static ConfigDescriptor text(String id, String label) {
        return of(id, label, Kind.TEXT);
    }

    public static ConfigDescriptor number(String id, String label) {
        return of(id, label, Kind.NUMBER);
    }

    public static ConfigDescriptor bool(String id, String label) {
        return of(id, label, Kind.BOOLEAN);
    }

    public static ConfigDescriptor select(String id, String label, List<String> options) {
        return of(id, label, Kind.SELECT).options(options);
    }

    public static ConfigDescriptor vector(String id, String label, int arity) {
        return of(id, label, Kind.VECTOR).arity(arity);
    }

    public static ConfigDescriptor color(String id, String label) {
        return of(id, label, Kind.COLOR);
    }

    public static ConfigDescriptor mask(String id, String label, List<String> options) {
        return of(id, label, Kind.MASK).options(options);
    }

    public static ConfigDescriptor matrix(String id, String label, int arity) {
        return of(id, label, Kind.MATRIX).arity(arity);
    }

    public static ConfigDescriptor asset(String id, String label) {
        return of(id, label, Kind.ASSET);
    }

    public static ConfigDescriptor group(String label) {
        return of(label, label, Kind.GROUP);
    }

    public static ConfigDescriptor header(String label) {
        return of(label, label, Kind.HEADER);
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public Kind kind() {
        return kind;
    }

    @Nullable
    public String tooltip() {
        return tooltip;
    }

    public List<String> options() {
        return options;
    }

    @Nullable
    public Range range() {
        return range;
    }

    /** Components for {@link Kind#VECTOR}; the side length for {@link Kind#MATRIX}. */
    public int arity() {
        return arity;
    }

    public boolean integral() {
        return integral;
    }

    public boolean hdr() {
        return hdr;
    }

    @Nullable
    public java.util.function.Predicate<String> validator() {
        return validator;
    }

    /** For {@link Kind#ARRAY}: what one entry looks like. */
    @Nullable
    public ConfigDescriptor element() {
        return element;
    }

    /** For {@link Kind#GROUP}: what it contains. */
    public List<ConfigDescriptor> children() {
        return Collections.unmodifiableList(children);
    }

    // ── Writes, fluent ──────────────────────────────────────────────────────

    public ConfigDescriptor tooltip(String value) {
        this.tooltip = value;
        return this;
    }

    public ConfigDescriptor options(List<String> value) {
        this.options = List.copyOf(value);
        return this;
    }

    public ConfigDescriptor range(float min, float max) {
        this.range = new Range(min, max);
        return this;
    }

    public ConfigDescriptor arity(int value) {
        this.arity = value;
        return this;
    }

    public ConfigDescriptor integral(boolean value) {
        this.integral = value;
        return this;
    }

    public ConfigDescriptor hdr(boolean value) {
        this.hdr = value;
        return this;
    }

    public ConfigDescriptor validator(java.util.function.Predicate<String> value) {
        this.validator = value;
        return this;
    }

    public ConfigDescriptor element(ConfigDescriptor value) {
        this.element = value;
        return this;
    }

    public ConfigDescriptor child(ConfigDescriptor value) {
        this.children.add(value);
        return this;
    }
}
