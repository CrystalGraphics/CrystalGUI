package com.crystalgui.ui.elements.config.control;

import com.crystalgui.ui.elements.config.ConfigControlContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.config.ConfigControl;
import com.crystalgui.ui.elements.config.ConfigDescriptor;
import com.crystalgui.ui.elements.config.ValueControl;

import javax.annotation.Nullable;

/**
 * A resource path, typed or browsed to.
 *
 * <p>Unity reference: the material/texture reference rows in
 * {@code docs/research/unity-inspector/04-inspector-custom-function.png}.</p>
 *
 * <h3>The browser is deliberately not here</h3>
 * <p>P6.1.10's asset browser does not exist yet, and this control does not wait on it: {@link #onBrowse}
 * fires when the {@code ...} button is pressed and a host that has a browser connects to it, exactly the
 * way {@link ConfigControl#changed changed} reports an edit without this class knowing what a document
 * or an undo stack is. Until then the field is still fully usable — a path is text, and typing one in is
 * a legitimate way to set an asset reference whether or not a picker exists yet.</p>
 */
public class AssetControl extends ValueControl<String> {

    /** An asset id -- picked, not typed. */
    public static final WidgetContract<AssetControl> CONTRACT = ConfigControlContracts.register(
            AssetControl.class, "assetcontrol", StateTypes.STRING, "", RatePolicy.IMMEDIATE);


    public static final String FIELD_CLASS = "__field__";
    public static final String BROWSE_CLASS = "__browse__";

    /** Fires when the {@code ...} button is pressed. Never fires from typing — that goes through
     * {@link ConfigControl#changed changed} like every other edit. */
    public final Signal.Action onBrowse = new Signal.Action();

    private final TextField field = new TextField();
    private final Button browse = new Button("...");

    public AssetControl(ConfigDescriptor descriptor, @Nullable String defaultValue) {
        super(descriptor, defaultValue == null ? "" : defaultValue);
        addClass("__asset__");
        markAsInternal();

        field.addClass(FIELD_CLASS);
        field.setText(defaultValue == null ? "" : defaultValue);
        field.attachListener(this::commit);

        browse.addClass(BROWSE_CLASS);
        browse.attachListener(onBrowse::emit);

        addInternalChild(field);
        addInternalChild(browse);
    }

    @Override
    protected void writeToWidgets(@Nullable String value) {
        field.setText(value == null ? "" : value);
    }

    /** The path field, for a host that needs to reach the widget — focus, selection, a max length. */
    public TextField field() {
        return field;
    }

    /** The {@code ...} button, for a host wiring a real picker on top of {@link #onBrowse}. */
    public Button browseButton() {
        return browse;
    }
}
