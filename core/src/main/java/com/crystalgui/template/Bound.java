package com.crystalgui.template;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Fills this field from the inflated document, by id — JavaFX's {@code @FXML}, Unreal's
 * {@code BindWidget}.
 *
 * <pre>{@code
 * @UiTemplate.Source("mymod:ui/status")
 * public final class StatusPanel extends UIElement implements Networked<StatusModel> {
 *     @Bound UIText title;                       // the element with id="title"
 *     @Bound("stats") RadarChart chart;          // a different id from the field name
 *     @Bound(optional = true) Button debug;      // absent in some documents
 *
 *     public void build(StatusModel model) { }   // nothing: the template is the layout
 * }
 * }</pre>
 *
 * <p>A required id the document has not got, or an element of the wrong type, is an error <b>at
 * build</b> naming the field and the document — there is no compile step over a resource, so this is
 * the earliest moment it can be caught. A field that is also assigned by an initializer is refused for
 * the same reason: two owners of one part.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Bound {

    /** The element id. Empty means the field's own name, which is the usual case. */
    String value() default "";

    /** Whether the document is allowed not to have it — the field then stays null. */
    boolean optional() default false;
}
