package com.crystalgui.document;

import java.util.List;

import com.crystalgui.fs.Resource;

/**
 * Documents a drag carries — what an editor area opens where they are dropped, as VS Code's editor drop target reads
 * the resources an explorer drag puts in its {@code DataTransfer}.
 *
 * <pre>{@code
 * public Object transfer(List<CgPath> paths) { return new DraggedResources(paths.stream().map(Resource::of).toList()); }
 * DraggedResources dropped = DragData.find(event.getPayload(), DraggedResources.class);
 * }</pre>
 *
 * @param resources never empty — a source with nothing to offer offers no {@code DraggedResources} at all
 */
public record DraggedResources(List<Resource> resources) {

    public DraggedResources {
        resources = List.copyOf(resources);
        if (resources.isEmpty()) throw new IllegalArgumentException("nothing dragged");
    }
}
