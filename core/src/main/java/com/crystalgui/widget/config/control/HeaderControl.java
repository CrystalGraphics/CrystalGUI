package com.crystalgui.widget.config.control;

import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.config.ConfigControl;
import com.crystalgui.core.config.ConfigDescriptor;

import javax.annotation.Nullable;
import com.crystalgui.ui.dom.Name;

/**
 * A full-width, banded section caption — no field, no arrow, and nothing to toggle.
 *
 * <p>Unity reference: {@code Target Settings} in
 * {@code docs/research/unity-inspector/07-full-window.png}. Unlike {@link ConfiguratorGroup}, a header
 * does not own content and does not collapse; it exists purely to break a long panel into named
 * stretches the way a heading breaks a page.</p>
 *
 * <h3>Not a value</h3>
 * <p>{@link #getValueObject()} and {@link #applyValue} are no-ops. A header is structure wearing the
 * {@link ConfigControl} shape so it can travel through the same registry, {@link
 * com.crystalgui.ui.elements.config.Configurator} row and kit-height rules as every other kind — the
 * alternative is special-casing it in {@code ConfiguratorPanel} the way {@code GROUP} is, which buys
 * nothing here since a header has no children to recurse into.</p>
 */
public class HeaderControl extends ConfigControl {

    public static final Name NAME = Name.of("headercontrol");

    /** The no-argument constructor the registry's factory needs, over a NEUTRAL
     * descriptor -- an unlabelled control of this kind, which is a real thing rather than a
     * placeholder. Nothing decodes one: the kit is {@code localOnly}, and the registration
     * exists so a theme can address {@code headercontrol } by tag. */
    public HeaderControl() {
        this(ConfigDescriptor.text("", ""));
    }

    public HeaderControl(ConfigDescriptor descriptor) {
        super(NAME, descriptor);
        addClass("__header__");
        UIText title = new UIText(descriptor.label());
        title.addClass("__title__");
        title.setHitTest(false);
        append(title);
    }

    @Override
    public boolean selfLabelling() {
        return true;
    }

    @Override
    public Object getValueObject() {
        return null;
    }

    @Override
    protected void applyValue(Object value) {
        // A header carries no state to write back.
    }
}
