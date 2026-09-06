package com.crystalgui.widget.surface;

import java.util.ArrayList;
import java.util.List;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.inspector.InspectorForm;
import com.crystalgui.widget.config.inspector.InspectorSection;
import com.crystalgui.widget.surface.extension.SurfaceExtension;
import com.crystalgui.widget.surface.insert.Insertable;
import com.crystalgui.widget.surface.mode.Tool;
import com.crystalgui.widget.surface.overlay.ViewMode;

/** The stand-in consumer the engine's own tests run against: a policy, and an extension of everything. */
final class TestSurface {

    private TestSurface() {
    }

    /** Selects whatever it is handed, gives every press to the surface, records no move. */
    static SurfacePolicy policy() {
        return new SurfacePolicy() {
            @Override
            public UIElement itemFor(UIElement hit) {
                return hit;
            }

            @Override
            public PressOwner ownerOf(UIElement hit) {
                return PressOwner.SURFACE;
            }

            @Override
            public void markSelected(UIElement item, boolean selected) {
            }

            @Override
            public Edit moveEdit(List<Move> moves) {
                return null;
            }
        };
    }

    /** One extension registering one of everything, so a round trip has something to leave behind. */
    static final class Everything implements SurfaceExtension {

        static final String ID = "test:everything";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public Disposable activate(SurfaceContext surface) {
            List<Disposable> handles = new ArrayList<>();
            handles.add(surface.registerTool(ToolKind.of("test:tool", "Tool")
                    .tool(ctx -> new Tool() { })));
            handles.add(surface.registerOverlay(OverlayKind.of("test:overlay", "Overlay")
                    .element(ctx -> new UIElement())));
            handles.add(surface.registerViewMode(ViewModeKind.of("test:mode", "Mode")
                    .mode(ctx -> new ViewMode() {
                        @Override
                        public void enter() {
                        }

                        @Override
                        public void exit() {
                        }
                    })));
            handles.add(surface.registerInsertSource(() -> List.<Insertable>of()));
            handles.add(surface.registerDropHandler(new DropHandler() {
                @Override
                public boolean accepts(Object payload) {
                    return false;
                }

                @Override
                public boolean drop(Object payload, float worldX, float worldY) {
                    return false;
                }
            }));
            handles.add(surface.registerSection(new InspectorSection() {
                @Override
                public String tab() {
                    return "Test";
                }

                @Override
                public boolean accepts(DataContext context) {
                    return false;
                }

                @Override
                public void build(InspectorForm form, DataContext context) {
                }
            }));
            handles.add(surface.registerCommand(Command.of("test.surface.thing", "Thing")
                    .run(() -> { })));
            return () -> {
                for (Disposable handle : handles) handle.dispose();
            };
        }
    }

    /** Fails at activation, to prove a broken feature costs only itself. */
    static final class Broken implements SurfaceExtension {

        static final String ID = "test:broken";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public Disposable activate(SurfaceContext surface) {
            throw new IllegalStateException("this feature is broken on purpose");
        }
    }
}
