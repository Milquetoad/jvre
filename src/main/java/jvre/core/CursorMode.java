package jvre.core;

import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_HIDDEN;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL;

/**
 * How the mouse cursor behaves over the window, for {@link Window#setCursorMode}.
 * Maps to GLFW's {@code GLFW_CURSOR} input mode.
 */
public enum CursorMode {
    /** Visible and moves freely -- the default for normal UI. */
    NORMAL(GLFW_CURSOR_NORMAL),
    /** Invisible while over the window, but still moves freely (and reappears when it
     *  leaves). For a clean fullscreen look that still lets the OS cursor out. */
    HIDDEN(GLFW_CURSOR_HIDDEN),
    /** Invisible AND locked to the window centre, reporting unlimited virtual motion
     *  -- the mode for first-person / orbit MOUSELOOK. Read the motion from the
     *  per-frame cursor position (which then grows unbounded rather than clamping at
     *  the screen edge). */
    DISABLED(GLFW_CURSOR_DISABLED);

    final int glfw;

    CursorMode(int glfw) {
        this.glfw = glfw;
    }
}
