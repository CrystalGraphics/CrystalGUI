package com.crystalgui.style.property.general.floats;

import com.crystalgui.style.property.StyleValue;

import java.util.Locale;

/**
 * A scalar declaration — {@code opacity}, {@code font-size}, {@code outline-width}, {@code caret-width}.
 *
 * <h3>{@code px} is accepted, and it silently was not</h3>
 *
 * <p>This was {@code Float.parseFloat(raw.trim())}, which throws on {@code "7px"} — and a thrown parse is
 * caught upstream, logged as a warning and turned into {@code null}, so the declaration <b>degrades to
 * nothing rather than failing</b>. That is the right behaviour for a malformed value and the wrong one
 * here, because {@code font-size: 7px} is not malformed: it is how CSS is written, and how the shipped
 * sheets were written.</p>
 *
 * <p><b>Thirty-one declarations across {@code default.css} and {@code decorations.css} were doing nothing
 * at all</b> — twenty-one {@code font-size}s including the status bar's, the breadcrumbs' and the Problems
 * rows', plus the {@code 1px} outline and caret widths. Every one of them fell back to the inherited
 * {@code 10}, which is why the status bar's text was rendering half again the size it was authored at.
 * Nothing reported it: the warning goes to a log nobody reads during layout, and a value that never
 * arrives looks exactly like a value nobody set.</p>
 *
 * <p>Found by probe rather than by eye — the bar computed {@code font-size = 10.0} while the rule that had
 * just matched it (proved by its {@code white-space}) said {@code 7px}.</p>
 *
 * <p>Only {@code px} is taken. It is the only absolute unit this engine has ever meant — there is no
 * viewport-relative or font-relative sizing to resolve {@code em}, {@code rem} or {@code %} against at
 * parse time, and accepting them here would compute a number in the wrong unit rather than degrade.</p>
 */
public class FloatValue extends StyleValue<Float> {

    public FloatValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected Float doCompute(String rawValue) {
        String text = rawValue.trim();
        if (text.length() > 2 && text.toLowerCase(Locale.ROOT).endsWith("px")) {
            text = text.substring(0, text.length() - 2).trim();
        }
        return Float.parseFloat(text);
    }
}
