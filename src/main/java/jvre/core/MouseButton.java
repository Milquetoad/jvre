package jvre.core;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

/**
 * The mouse buttons jvre's input surface speaks in -- an L2-clean name instead of
 * a raw GLFW integer (errors and signatures stay in the user's vocabulary, never
 * a GLFW/Vulkan constant). The three buttons every mouse has; GLFW supports more,
 * which {@link Input} can expose later if a need appears.
 */
public enum MouseButton {
    LEFT(GLFW_MOUSE_BUTTON_LEFT),
    RIGHT(GLFW_MOUSE_BUTTON_RIGHT),
    MIDDLE(GLFW_MOUSE_BUTTON_MIDDLE);

    /** The underlying GLFW button index -- package-private so it never leaks to users. */
    final int glfw;

    MouseButton(int glfw) {
        this.glfw = glfw;
    }
}
