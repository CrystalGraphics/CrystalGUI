package com.crystalgui.core.config;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;

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
     * <p>A consumer wanting a colour wheel instead of a text box registers a factory for {@link #COLOR};
     * it does not invent a kind. A kind is added when a value has a genuinely different SHAPE — a point
     * on a box, a paragraph — and every kind needs a control in {@code ConfigControls}, which
     * {@code ConfigKitTest} holds it to.</p>
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
        HEADER,
        /**
         * Structure, not a value: a read-only <b>fact</b> — a label and some text, with no input chrome.
         *
         * <p>A node's id, its resolved port types, a compile's error count. The second non-value kind
         * after {@link #HEADER}, and it exists because the obvious alternative does not work: a disabled
         * {@link #TEXT} row still draws a sunken box that says "type here", and disabling the wrapper
         * never reached the text field inside it, so those rows stayed genuinely editable.</p>
         */
        INFO,
        /**
         * Structure, not a field: a paragraph across the whole row, with no label column — Blender's
         * {@code layout.label}, Unity's help box.
         *
         * <p>Read-only like {@link #INFO}, and bindable the same way, so a note can be a live readout as
         * well as a sentence of guidance.</p>
         */
        NOTE,
        /**
         * A point on a box as two fractions, {@code [x, y]} each 0..1 — Photoshop's 3x3 reference point,
         * Unity's anchor presets. Nine cells put it on the corners, edge midpoints and centre; any other
         * value lights none.
         */
        ANCHOR
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
    private String unit;
    private String shortLabel;
    private int decimals = -1;
    private boolean commitWhileTyping;
    private boolean toggle;
    private double scrubRate = Double.NaN;
    private DoubleSupplier scrubRateSource;
    private String description;
    private Predicate<String> validator;
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

    /** A read-only fact — label on the left, text on the right, nothing to type into. @see Kind#INFO */
    public static ConfigDescriptor info(String id, String label) {
        return of(id, label, Kind.INFO);
    }

    /** A paragraph across the row, its text being the value. @see Kind#NOTE */
    public static ConfigDescriptor note(String id) {
        return of(id, "", Kind.NOTE);
    }

    /** A point on a box, as two fractions. @see Kind#ANCHOR */
    public static ConfigDescriptor anchor(String id, String label) {
        return of(id, label, Kind.ANCHOR);
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
    public String description() {
        return description;
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

    /** What a number is measured in — {@code "%"}, {@code "°"}, {@code "px"} — or null for a bare number. */
    @Nullable
    public String unit() {
        return unit;
    }

    /** What a compact field shows in front of its control, or null to show {@link #label()}. */
    @Nullable
    public String shortLabel() {
        return shortLabel;
    }

    /** Decimal places a number is shown to, or -1 for up to four with trailing zeros dropped. */
    public int decimals() {
        return decimals;
    }

    /** Whether a typed value lands on every keystroke rather than on Enter, Tab or a click away. */
    public boolean commitsWhileTyping() {
        return commitWhileTyping;
    }

    /** Whether a {@link Kind#BOOLEAN} is a pressed-or-not button rather than a checkbox. */
    public boolean toggle() {
        return toggle;
    }

    /** Units per pixel of scrub, or {@code NaN} to let the range decide. @see #scrubRate(double) */
    public double scrubRate() {
        if (scrubRateSource != null) {
            double asked = scrubRateSource.getAsDouble();
            return asked > 0d && Double.isFinite(asked) ? asked : Double.NaN;
        }
        return scrubRate;
    }

    @Nullable
    public Predicate<String> validator() {
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

    /**
     * Shown after the number in its field, and optional when typed.
     *
     * <pre>{@code
     * ConfigDescriptor.number("angle", "Angle").unit("°");   // reads 45°, takes 50 or 50° alike
     * }</pre>
     */
    public ConfigDescriptor unit(@Nullable String value) {
        this.unit = value;
        return this;
    }

    /**
     * The letter a compact field shows in front of its control, with {@link #label()} on hover.
     *
     * <pre>{@code
     * ConfigDescriptor.number("rotation", "Rotation").shortLabel("R").unit("°");   // R [45°], "Rotation" on hover
     * }</pre>
     *
     * <p>A row in a panel always shows the full label.</p>
     */
    public ConfigDescriptor shortLabel(@Nullable String value) {
        this.shortLabel = value;
        return this;
    }

    /**
     * Shows a number to exactly this many decimal places.
     *
     * <pre>{@code
     * ConfigDescriptor.number("ior", "Index of refraction").range(1f, 2.5f).decimals(2);   // 1.50
     * }</pre>
     *
     * <p>Display only: a scrub or a typed value keeps its full precision.</p>
     */
    public ConfigDescriptor decimals(int places) {
        this.decimals = places;
        return this;
    }

    /**
     * Lands a typed value on every keystroke, as a node's port editor does, rather than on Enter, Tab or
     * a click away.
     *
     * <pre>{@code
     * ConfigDescriptor.number("value", "X").commitWhileTyping(true);   // the preview follows each digit
     * }</pre>
     */
    public ConfigDescriptor commitWhileTyping(boolean value) {
        this.commitWhileTyping = value;
        return this;
    }

    /**
     * Draws a {@link Kind#BOOLEAN} as a button that stays pressed while it is on — Blender's
     * {@code prop(toggle=True)}, Photoshop's chain between width and height.
     *
     * <pre>{@code
     * form.prop(ConfigDescriptor.bool("link", "Maintain aspect ratio").toggle(true), linked)
     *         .addClass(LINK_CLASS);   // the sheet gives it its icon
     * }</pre>
     */
    public ConfigDescriptor toggle(boolean value) {
        this.toggle = value;
        return this;
    }

    /**
     * What one pixel of a drag-scrub is worth on this number, for a field that knows its own scale.
     *
     * <pre>{@code
     * ConfigDescriptor.number("zoom", "Zoom").scrubRate(0.05d);   // a fine unbounded quantity
     * }</pre>
     *
     * <p>Left unset, a pixel is worth a hundredth of the field's {@link #range}, or one unit when it has
     * none — so most fields never need this. @see com.crystalgui.ui.input.DragScrub</p>
     */
    public ConfigDescriptor scrubRate(double unitsPerPixel) {
        this.scrubRate = unitsPerPixel;
        this.scrubRateSource = null;
        return this;
    }

    /**
     * A scrub rate asked for when each drag starts, for a number whose scale is another value.
     *
     * <pre>{@code
     * ConfigDescriptor.number("value", "Value").scrubRate(() -> (slider.getMax() - slider.getMin()) * 0.01);
     * }</pre>
     *
     * <p>A slider's value moves between its own min and max, which can change while the form is on screen.
     * NaN or a non-positive answer falls back as an unset rate does.</p>
     */
    public ConfigDescriptor scrubRate(DoubleSupplier unitsPerPixel) {
        this.scrubRateSource = unitsPerPixel;
        return this;
    }

    /**
     * A sentence saying what the field does, shown under its name in the row's hint.
     *
     * <pre>{@code
     * ConfigDescriptor.bool("hit-test", "Hit test").tooltip("hit-test").description("Whether a click can land here.");
     * }</pre>
     *
     * <p>The hint appears only when the descriptor also has a {@link #tooltip} — that is its heading.</p>
     */
    public ConfigDescriptor description(@Nullable String value) {
        this.description = value;
        return this;
    }

    public ConfigDescriptor validator(Predicate<String> value) {
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
