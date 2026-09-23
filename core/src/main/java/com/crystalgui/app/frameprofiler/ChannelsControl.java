package com.crystalgui.app.frameprofiler;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.control.MaskControl;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Which channels are recording — a {@link MaskControl} over the engine's live mask.
 *
 * <pre>{@code
 * ChannelsControl channels = new ChannelsControl();
 * channels.bindTo(model);
 * toolbar.append(channels);
 * }</pre>
 *
 * <h3>Bound, never copied</h3>
 *
 * <p>The control edits a {@link Property} that reads and writes {@code CgTrace} directly. There is no
 * local copy of the enabled set and no {@code refresh()} — a second copy of the mask is a second thing
 * that can be wrong, and the one that is wrong is always the one being shown.</p>
 *
 * <h3>It edits a SET, and that is what keeps the strip readable</h3>
 *
 * <p>The whole selection is written in one call rather than a channel at a time. Each write of the mask
 * makes the engine record a boundary marker; applying six ticks one by one would stripe the strip with
 * six boundaries for one gesture, and the strip greys everything before the last of them.</p>
 *
 * <h3>The option list grows</h3>
 *
 * <p>A channel registers when its declaring class first loads, so the list is not complete at startup —
 * and {@code MaskControl} builds its checkboxes once, from the descriptor. {@link #readBack} therefore
 * rebuilds the control when the channel count has moved, which is a handful of times in a run and never
 * while the panel is open.</p>
 */
public class ChannelsControl extends UIElement {

    public static final Name NAME = Name.of("channelscontrol");

    @Nullable
    private ProfilerModel model;
    @Nullable
    private MaskControl control;

    private int builtFor = -1;

    public ChannelsControl() {
        super(NAME);
        refusePublicChildren();
    }

    public void bindTo(ProfilerModel value) {
        model = value;
        rebuild();
    }

    /** Rebuilds when a channel has registered since the control was built. */
    public void readBack() {
        if (model == null) return;
        if (model.channelNames().size() != builtFor) rebuild();
    }

    /** The control itself, for a test that needs to reach past the summary text. */
    @Nullable
    public MaskControl control() {
        return control;
    }

    private void rebuild() {
        ProfilerModel bound = model;
        if (bound == null) return;
        // NOT THE ENGINE'S OWN CHANNEL. `trace` carries the mask-change markers and is switched on by
        // the engine whenever anything else is; offering it as a box made every summary start with a
        // name that means nothing to the reader and could not usefully be unticked.
        List<String> names = new ArrayList<>(bound.channelNames());
        names.removeIf(CgTrace::isEngineOwn);
        // THE ENGINE'S COUNT, before `trace` is taken out: readBack compares against channelNames(),
        // and measuring the filtered list made the two differ by one forever -- so the control was
        // destroyed and rebuilt on every render, closing an open menu four times a second.
        builtFor = bound.channelNames().size();

        removeAll();
        if (names.isEmpty()) {
            // NOT AN EMPTY DROPDOWN. Nothing has registered a channel yet, which is a real state on a
            // process that has not painted — and an empty list of boxes reads as "nothing to record".
            control = null;
            return;
        }

        ConfigDescriptor descriptor = ConfigDescriptor.mask("profiler.channels", "Channels", names)
                .emptyText("Not recording");
        MaskControl made = new MaskControl(descriptor, bound.enabledChannels());
        // POLLED, deliberately: the mask moves from Capture, from Freeze and from anything else in the
        // process that enables a channel, and none of those announce. A poll of a volatile long once a
        // frame is cheaper than a notification seam on the engine for one reader.
        made.bind(Property.derived(bound::enabledChannels, this::apply));
        control = made;
        appendStructural(made);
    }

    private void apply(@Nullable Set<String> selected) {
        if (model != null) model.setEnabledChannels(selected == null ? Set.of() : selected);
    }
}
