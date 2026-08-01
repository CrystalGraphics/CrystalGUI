package com.crystalgui.ui.elements.config.control;

import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.config.ConfigControl;
import com.crystalgui.ui.elements.config.ConfigDescriptor;

import javax.annotation.Nullable;

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

    public HeaderControl(ConfigDescriptor descriptor) {
        super(descriptor);
        addClass("__header__");
        markAsInternal();
        UIText title = new UIText(descriptor.label());
        title.addClass("__title__");
        title.setHitTest(false);
        addInternalChild(title);
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
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
