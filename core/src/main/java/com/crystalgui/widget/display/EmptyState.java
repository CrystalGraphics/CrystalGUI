package com.crystalgui.widget.display;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;

/**
 * What a panel says while it has nothing to show: a heading and a few short lines over the middle of the panel —
 * IntelliJ's {@code StatusText}.
 *
 * <p>A workbench panel declares one and says when it is vacant; the container it is docked in does the rest —
 * places the note, and shows it in the panel's place:</p>
 *
 * <pre>{@code
 * private final EmptyState empty = EmptyState.of(this, "To see a document's structure:",
 *         "— Open one that has a structure, such as a .cgui file");
 *
 * private void show(@Nullable Thing thing) {
 *     empty.setVacant(thing == null);
 * }
 * }</pre>
 *
 * <p>A line that changes, such as one naming a key binding, is rewritten on the same note:</p>
 *
 * <pre>{@code
 * empty.setLine(0, "— Open a java file and press Run (" + accelerator + ")");
 * }</pre>
 *
 * <ul>
 *   <li>Vacant until told otherwise.</li>
 *   <li>A panel is only hidden once its note has been placed, so one used where nothing places the note stays
 *       visible. {@link #placeIn} is the host's half.</li>
 *   <li>It never wraps. A panel narrower than the note clips it — evenly at both ends of each line when
 *       {@linkplain #setCentred centred} — so keep lines short: one idea to a line.</li>
 *   <li>The copy is a direction, not a status: "To &lt;do the thing&gt;:" and the steps there. Name the kind of
 *       document a panel needs with one example, since another extension may serve the same panel.</li>
 *   <li>Themed through {@code emptystate::part(lines)}, {@code ::part(heading)} and {@code ::part(line)}, and the
 *       {@code --empty-state-fg} token.</li>
 * </ul>
 */
public class EmptyState extends UIElement {

    public static final Name NAME = Name.of("emptystate");

    /** The note a panel declared with {@link #of}, which whatever hosts the panel places. */
    public static final Attribute<EmptyState> NOTE = Attribute.of("empty-state", EmptyState.class, null);

    public static final String LINES_PART = "lines";
    public static final String HEADING_PART = "heading";
    public static final String LINE_PART = "line";

    /** On the host while its lines share a left edge. @see #setCentred */
    public static final String START_ALIGNED_CLASS = "__start-aligned__";

    // Two containers: the host centres the block, and the block aligns its lines against its widest one -- which a
    // start-aligned list needs, since one container can only centre each line on its own.
    private final UIElement lines = new UIElement();
    private final UIText heading = new UIText("");
    private final List<UIText> rows = new ArrayList<>();

    /** The panel this note stands in for, or null for one placed by hand. */
    @Nullable
    private UIElement panel;
    private boolean vacant = true;

    /** Declares {@code panel}'s note, in list form: its lines share a left edge. */
    public static EmptyState of(UIElement panel, String heading, String... lines) {
        EmptyState note = new EmptyState(heading, lines).setCentred(false);
        note.panel = panel;
        panel.set(NOTE, note);
        return note;
    }

    public EmptyState(String heading, String... lines) {
        super(NAME);
        ShadowRoot shadow = attachShadow();
        setHitTest(false);
        this.lines.set(Attribute.PART, LINES_PART);
        this.lines.setHitTest(false);
        this.heading.set(Attribute.PART, HEADING_PART);
        this.heading.setHitTest(false);
        this.lines.append(this.heading);
        shadow.append(this.lines);
        setHeading(heading);
        setLines(lines);
    }

    /** Whether the panel has nothing to show. While it has not, the note is hidden and the panel shown. */
    public EmptyState setVacant(boolean vacant) {
        this.vacant = vacant;
        apply();
        return this;
    }

    public boolean isVacant() {
        return vacant;
    }

    /** Puts the note in {@code slot}, beside its panel — what a host does when it mounts a panel that declared one. */
    public void placeIn(UIElement slot) {
        slot.append(this);
        apply();
    }

    private void apply() {
        setDisplayed(vacant);
        if (panel != null) panel.setDisplayed(!vacant || parent() == null);
    }

    /** Whether each line is centred (the default) or all share the block's left edge, for a list of dashes. */
    public EmptyState setCentred(boolean centred) {
        toggleClass(START_ALIGNED_CLASS, !centred);
        return this;
    }

    public String heading() {
        return heading.getText();
    }

    public EmptyState setHeading(String text) {
        if (!text.equals(heading.getText())) heading.setText(text);
        return this;
    }

    /** Replaces the lines under the heading. */
    public EmptyState setLines(String... texts) {
        while (rows.size() > texts.length) lines.remove(rows.remove(rows.size() - 1));
        while (rows.size() < texts.length) {
            UIText row = new UIText("");
            row.set(Attribute.PART, LINE_PART);
            row.setHitTest(false);
            rows.add(row);
            lines.append(row);
        }
        for (int i = 0; i < texts.length; i++) setLine(i, texts[i]);
        return this;
    }

    /** Rewrites one line; an unchanged text costs a comparison, so this may run every frame. */
    public EmptyState setLine(int index, String text) {
        UIText row = rows.get(index);
        if (!text.equals(row.getText())) row.setText(text);
        return this;
    }

    public List<String> lines() {
        List<String> out = new ArrayList<>(rows.size());
        for (UIText row : rows) out.add(row.getText());
        return out;
    }
}
