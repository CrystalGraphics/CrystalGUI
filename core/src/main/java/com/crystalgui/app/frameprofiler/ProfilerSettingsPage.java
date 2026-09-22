package com.crystalgui.app.frameprofiler;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.ConfiguratorPanel;
import com.crystalgui.widget.config.PanelForm;
import com.crystalgui.widget.config.SettingsConfigurator;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

import javax.annotation.Nullable;

/**
 * The frame profiler's settings, as a page inside its own window.
 *
 * <pre>{@code
 * ProfilerSettingsPage page = new ProfilerSettingsPage();
 * page.onDone(() -> panel.setSettingsOpen(false));
 * }</pre>
 *
 * <p>A page and not a dialog: the profiler is usually open beside the thing it measures, and a second
 * window over it would cover the strip the settings are about. Changes apply as they are made — VS Code's
 * model — and the line under the title says what the ring holds <em>now</em>, so a resize is seen to have
 * happened rather than taken on trust.</p>
 */
public class ProfilerSettingsPage extends UIElement {

    public static final Name NAME = Name.of("profilersettings");

    public static final String HEADER_CLASS = "__settings-header__";
    public static final String TITLES_CLASS = "__settings-titles__";
    public static final String TITLE_CLASS = "__settings-title__";
    public static final String SUMMARY_CLASS = "__settings-summary__";

    private final UIText summary = new UIText("");
    private final ConfiguratorPanel panel = new ConfiguratorPanel();
    private final Button done = new Button("Done");
    private final Button defaults = new Button("Restore defaults");

    @Nullable
    private Runnable onDone;

    public ProfilerSettingsPage() {
        super(NAME);
        Settings store = ProfilerSettings.store();

        UIElement header = new UIElement();
        header.addClass(HEADER_CLASS);
        UIElement titles = new UIElement();
        titles.addClass(TITLES_CLASS);
        UIText title = new UIText("Settings");
        title.addClass(TITLE_CLASS);
        titles.append(title);
        summary.addClass(SUMMARY_CLASS);
        titles.append(summary);
        header.append(titles);
        defaults.attachListener(this::restoreDefaults);
        header.append(defaults);
        done.attachListener(() -> {
            if (onDone != null) onDone.run();
        });
        header.append(done);
        append(header);

        PanelForm form = panel.form();
        for (ProfilerSettings.Section section : ProfilerSettings.SECTIONS) {
            PanelForm group = form.group(section.title(), false);
            for (Setting<?> setting : section.settings()) {
                SettingsConfigurator.addRow(group, store, SettingsLayer.USER, setting, null);
                // SAID UNDER THE ROW, not in a tooltip: "clears the recording" is the one thing to know
                // before changing a value, and a tooltip is read after.
                if (setting.getDescription() != null) group.note(setting.getDescription());
            }
        }
        append(panel);

        whileConnected(() -> store.onChanged.connect(change -> refreshSummary()));
        onConnected(this::refreshSummary);
    }

    public ProfilerSettingsPage onDone(Runnable action) {
        this.onDone = action;
        return this;
    }

    public Button doneButton() {
        return done;
    }

    public ConfiguratorPanel panel() {
        return panel;
    }

    public String summaryText() {
        return summary.getText();
    }

    private void restoreDefaults() {
        Settings store = ProfilerSettings.store();
        for (Setting<?> setting : ProfilerSettings.all()) store.reset(SettingsLayer.USER, setting);
    }

    /** What the ring holds now, and at most what it will cost. */
    public void refreshSummary() {
        long zones = (long) CgTrace.zoneCapacity() + (CgTrace.firstFrames() > 0 ? CgTrace.headZoneCapacity() : 0);
        String keeps = CgTrace.newestFrames() == 0
                ? String.format("the first %,d frames, then stops", CgTrace.firstFrames())
                : CgTrace.firstFrames() == 0 ? String.format("the newest %,d frames", CgTrace.newestFrames())
                : String.format("the first %,d and the newest %,d frames", CgTrace.firstFrames(), CgTrace.newestFrames());
        summary.setText(String.format("Keeps %s  ·  up to %,d zones a thread (%s)  ·  %,d recorded",
                keeps, zones, megabytes(zones * ZONE_BYTES), CgTrace.frameCount()));
    }

    /** start + end + name + packed. @see com.crystalgraphics.trace.CgTrace */
    private static final long ZONE_BYTES = 24L;

    private static String megabytes(long bytes) {
        double mb = bytes / (1024d * 1024d);
        return mb < 10d ? String.format("%.1f MB", mb) : String.format("%.0f MB", mb);
    }
}
