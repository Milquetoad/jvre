package jvre.core;

import static org.lwjgl.glfw.GLFW.*;

/**
 * A standard OS mouse-cursor shape, for {@link Window#setCursor}. These map to
 * GLFW's built-in cursors (the system's native shapes), so an L2 app can show an
 * I-beam over a text field or a hand over a clickable thing without shipping cursor
 * images. For a CUSTOM image cursor, see {@link Window#setCursor(String, int, int)};
 * for hiding/locking the cursor (mouselook), see {@link Window#setCursorMode}.
 */
public enum CursorShape {
    /** The normal arrow. */
    ARROW(GLFW_ARROW_CURSOR),
    /** A text I-beam -- over editable text. */
    IBEAM(GLFW_IBEAM_CURSOR),
    /** A crosshair -- over a precise pick target. */
    CROSSHAIR(GLFW_CROSSHAIR_CURSOR),
    /** A pointing hand -- over a clickable link / button. */
    HAND(GLFW_HAND_CURSOR),
    /** A horizontal resize (left-right) arrow. */
    RESIZE_H(GLFW_HRESIZE_CURSOR),
    /** A vertical resize (up-down) arrow. */
    RESIZE_V(GLFW_VRESIZE_CURSOR);

    final int glfw;

    CursorShape(int glfw) {
        this.glfw = glfw;
    }
}
