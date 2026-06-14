package jvre.core;

import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.system.MemoryStack;

import java.nio.DoubleBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * The per-frame INPUT snapshot -- what turns L2 from draw-only into interactive.
 * Owned by the {@link Window} (input is a window/GLFW concern) and surfaced via
 * {@code window.input()}. The user reads it during their frame; jvre keeps it
 * fresh automatically (the Window brackets {@code glfwPollEvents} with this
 * snapshot, so there is no extra call to remember).
 *
 * Two flavors of state, the immediate-mode GUI staples:
 *   - LEVEL: is a button held right now ({@link #mouseDown}).
 *   - EDGE: did it go down / up THIS frame ({@link #mousePressed} / {@link
 *     #mouseReleased}) -- what a "click" actually is. Edges come from GLFW
 *     callbacks (not polling), so a press+release inside one frame (a fast tap)
 *     is never missed; polling {@code glfwGetMouseButton} would drop it.
 *
 * Mouse position is reported in FRAMEBUFFER PIXELS (top-left origin) -- the same
 * space L2 draws in, so {@code input.mouseX()} lines up with {@code g.fillRect}.
 * GLFW reports cursor position in window/screen coordinates, which differ from
 * framebuffer pixels on high-DPI displays; we convert by the framebuffer/window
 * ratio (closing the DPI caveat the old {@code Window.cursorPos} noted).
 *
 * This class is GLFW-coupled (callbacks + cursor queries), so like {@link Window}
 * it is verified on hardware rather than unit-tested.
 */
public final class Input {

    private static final int BUTTON_COUNT = 8;  // GLFW's mouse-button range

    private final long window;

    // LEVEL state (held) + per-frame EDGE flags (set by the callback, cleared each
    // newFrame). Indexed by GLFW button number.
    private final boolean[] held = new boolean[BUTTON_COUNT];
    private final boolean[] pressed = new boolean[BUTTON_COUNT];
    private final boolean[] released = new boolean[BUTTON_COUNT];

    // Scroll accumulated over the frame (a delta, zeroed each newFrame).
    private double scrollX;
    private double scrollY;

    // Mouse position in framebuffer pixels, snapshotted once per frame.
    private float mouseX;
    private float mouseY;

    // Kept as fields so they can be freed at shutdown (off-heap native callbacks),
    // the same pattern as Window's resize callback.
    private final GLFWMouseButtonCallback mouseButtonCallback;
    private final GLFWScrollCallback scrollCallback;

    Input(long window) {
        this.window = window;

        mouseButtonCallback = GLFWMouseButtonCallback.create((win, button, action, mods) -> {
            if (button < 0 || button >= BUTTON_COUNT) {
                return;
            }
            if (action == GLFW_PRESS) {
                held[button] = true;
                pressed[button] = true;     // edge: latched for this frame
            } else if (action == GLFW_RELEASE) {
                held[button] = false;
                released[button] = true;
            }
        });
        glfwSetMouseButtonCallback(window, mouseButtonCallback);

        scrollCallback = GLFWScrollCallback.create((win, dx, dy) -> {
            scrollX += dx;
            scrollY += dy;
        });
        glfwSetScrollCallback(window, scrollCallback);
    }

    // ---- frame lifecycle (driven by Window.pollEvents) ----------------------

    /** Clear this frame's edges + scroll BEFORE GLFW dispatches new events. */
    void newFrame() {
        for (int i = 0; i < BUTTON_COUNT; i++) {
            pressed[i] = false;
            released[i] = false;
        }
        scrollX = 0;
        scrollY = 0;
    }

    /** Snapshot the cursor (window coords -> framebuffer pixels) AFTER events are
     *  processed, so position getters are plain field reads (no per-call polling). */
    void snapshot() {
        try (MemoryStack stack = stackPush()) {
            DoubleBuffer cx = stack.mallocDouble(1);
            DoubleBuffer cy = stack.mallocDouble(1);
            glfwGetCursorPos(window, cx, cy);

            IntBuffer winW = stack.mallocInt(1), winH = stack.mallocInt(1);
            IntBuffer fbW = stack.mallocInt(1), fbH = stack.mallocInt(1);
            glfwGetWindowSize(window, winW, winH);
            glfwGetFramebufferSize(window, fbW, fbH);

            // window coords -> framebuffer pixels (identical when no DPI scaling).
            float sx = winW.get(0) > 0 ? (float) fbW.get(0) / winW.get(0) : 1f;
            float sy = winH.get(0) > 0 ? (float) fbH.get(0) / winH.get(0) : 1f;
            mouseX = (float) (cx.get(0) * sx);
            mouseY = (float) (cy.get(0) * sy);
        }
    }

    // ---- the public surface -------------------------------------------------

    /** Cursor X in framebuffer pixels (same space as L2 drawing). */
    public float mouseX() {
        return mouseX;
    }

    /** Cursor Y in framebuffer pixels (top-left origin). */
    public float mouseY() {
        return mouseY;
    }

    /** Is {@code button} held down right now? */
    public boolean mouseDown(MouseButton button) {
        return held[button.glfw];
    }

    /** Did {@code button} go down THIS frame? (The down-edge of a click.) */
    public boolean mousePressed(MouseButton button) {
        return pressed[button.glfw];
    }

    /** Did {@code button} go up THIS frame? (The up-edge of a click.) */
    public boolean mouseReleased(MouseButton button) {
        return released[button.glfw];
    }

    /** Horizontal scroll accumulated this frame (rare; trackpads/tilt wheels). */
    public float scrollX() {
        return (float) scrollX;
    }

    /** Vertical scroll accumulated this frame (the usual wheel; up is positive). */
    public float scrollY() {
        return (float) scrollY;
    }

    /** Free the native callbacks (the window's destroy already detached them). */
    void free() {
        mouseButtonCallback.free();
        scrollCallback.free();
    }
}
