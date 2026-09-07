package com.crystalgui.core.attribute;

/**
 * One transferable property: what it is called, and where it belongs in the Paste Attributes window.
 *
 * <pre>{@code
 * new AttributeSlot("border-radius", "Appearance", "Corner radius")
 * }</pre>
 *
 * <p>{@code group} is a plain string rather than an enum, because the groups are the CONSUMER'S: a UI
 * builder files things under Layout and Appearance, a colour grader under Colour and Retime, and neither
 * has any business naming the other's.</p>
 *
 * @param id    stable and unique within its domain — what a remembered selection is keyed on
 * @param group the heading it appears under; consumers should keep these few
 * @param label what the checkbox says
 */
public record AttributeSlot(String id, String group, String label) {

    /** Label defaulting to the id, for a domain whose ids are already readable. */
    public static AttributeSlot of(String id, String group) {
        return new AttributeSlot(id, group, id);
    }
}
