package jvre.core;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.system.MemoryStack;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * A GLFW window with no graphics API attached (we render to it through a Vulkan
 * surface, not OpenGL). Named for its role, not a Vulkan object -- a window is a
 * GLFW/OS concept, so this is one of jvre's own abstractions rather than an L1
 * Vulkan elementary.
 *
 * For now this also owns GLFW's GLOBAL lifecycle (init/terminate), which is fine
 * for a single window; multi-window support would split that out later.
 */
public class Window {

    // Window is usually the first jvre object built, and it touches a MemoryStack in
    // its constructor -- which creates the thread's stack at whatever size is set by
    // then. So raise the stack size HERE too (at class-load, before that), not only in
    // Instance: otherwise a 64 KB stack is already locked in before Instance loads.
    // See NativeBootstrap.
    static {
        NativeBootstrap.ensureStackCapacity();
    }

    private final long handle;

    // Native callbacks kept as fields so we can free them at shutdown (off-heap),
    // same pattern as the debug-messenger callback in Instance.
    private final GLFWErrorCallback errorCallback;
    private final GLFWFramebufferSizeCallback resizeCallback;

    // The per-frame input snapshot (mouse for now). Owned here because input is a
    // window/GLFW concern; kept fresh automatically in pollEvents().
    private final Input input;

    // Set by the resize callback, consumed by the renderer. A FLAG rather than
    // reacting inside the callback: GLFW may fire it many times during one drag,
    // and mid-callback is no place to rebuild a swapchain -- the render loop
    // picks it up at a safe point in its own frame.
    private boolean framebufferResized = false;

    // Standard cursors are created lazily and CACHED (creating a fresh one per
    // setCursor() call would leak). Indexed by CursorShape.ordinal(); 0 = not yet
    // created. Destroyed at close (before glfwTerminate, which would free them too).
    private final long[] cursors = new long[CursorShape.values().length];

    public Window(int width, int height, CharSequence title) {
        // GLFW reports errors through a callback, not return codes -- without one
        // installed, failures (bad hints, missing display, ...) are SILENT and all
        // you see is the next call misbehaving. Installed before glfwInit so even
        // init errors are heard.
        errorCallback = GLFWErrorCallback.createPrint(System.err);
        glfwSetErrorCallback(errorCallback);

        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);  // no OpenGL context -- Vulkan owns drawing
        // Resizable: the renderer recreates the swapchain when the size changes.

        handle = glfwCreateWindow(width, height, title, NULL, NULL);
        if (handle == NULL) {
            throw new RuntimeException("Failed to create the GLFW window");
        }

        resizeCallback = GLFWFramebufferSizeCallback.create((win, w, h) -> framebufferResized = true);
        glfwSetFramebufferSizeCallback(handle, resizeCallback);

        // Install input callbacks (mouse buttons + scroll) on this window.
        input = new Input(handle);
    }

    /** The per-frame input snapshot (mouse position/buttons/scroll). */
    public Input input() {
        return input;
    }

    /** Raw GLFW handle -- needed for Vulkan surface creation and size queries. */
    public long handle() {
        return handle;
    }

    public boolean shouldClose() {
        return glfwWindowShouldClose(handle);
    }

    /** Change the window's title bar text at runtime (e.g. a document name or a
     *  live FPS readout). */
    public void setTitle(CharSequence title) {
        glfwSetWindowTitle(handle, title);
    }

    /** Set the mouse cursor to a standard {@link CursorShape} (e.g. an I-beam over a
     *  text field). The standard cursor is created once and cached. */
    public void setCursor(CursorShape shape) {
        int i = shape.ordinal();
        if (cursors[i] == NULL) {
            cursors[i] = glfwCreateStandardCursor(shape.glfw);
        }
        glfwSetCursor(handle, cursors[i]);
    }

    /** The system clipboard's text, or {@code null} if it holds no convertible text.
     *  (For a paste action.) */
    public String clipboard() {
        return glfwGetClipboardString(handle);
    }

    /** Set the system clipboard's text (for a copy action). */
    public void setClipboard(CharSequence text) {
        glfwSetClipboardString(handle, text);
    }

    /**
     * The DPI content scale (framebuffer pixels per window coordinate) -- e.g. 2.0
     * on a typical "retina" / 200%-scaled display, 1.0 on a standard one. The X
     * axis; see {@link #contentScaleY()}. jvre's L2 already works in framebuffer
     * PIXELS (so drawing is DPI-correct automatically); this is for apps that also
     * need the factor (e.g. to size text in points, or convert window coords).
     */
    public float contentScaleX() {
        return contentScale(true);
    }

    /** The DPI content scale on the Y axis (see {@link #contentScaleX()}). */
    public float contentScaleY() {
        return contentScale(false);
    }

    private float contentScale(boolean x) {
        try (MemoryStack stack = stackPush()) {
            FloatBuffer sx = stack.mallocFloat(1);
            FloatBuffer sy = stack.mallocFloat(1);
            glfwGetWindowContentScale(handle, sx, sy);
            return (x ? sx : sy).get(0);
        }
    }

    /** Current window size in SCREEN COORDINATES (not pixels), written into w/h --
     *  the complement of {@link #framebufferSize} (pixels). They differ by the
     *  {@linkplain #contentScaleX() content scale} on high-DPI displays. */
    public void windowSize(IntBuffer w, IntBuffer h) {
        glfwGetWindowSize(handle, w, h);
    }

    public void pollEvents() {
        // Bracket the GLFW dispatch: clear this frame's edges/scroll, let GLFW
        // fire the callbacks, then snapshot the cursor. Input is fresh afterward
        // with no extra call for the user to remember.
        input.newFrame();
        glfwPollEvents();
        input.snapshot();
    }

    /** Current framebuffer size in PIXELS (not screen coords), written into w/h. */
    public void framebufferSize(IntBuffer w, IntBuffer h) {
        glfwGetFramebufferSize(handle, w, h);
    }

    /**
     * Current cursor position relative to the window's top-left corner, written
     * into x/y. NOTE: GLFW reports SCREEN coordinates, not framebuffer pixels --
     * they differ on high-DPI displays (the same scale split as framebufferSize).
     * Identical on this machine; the proper content-scale conversion belongs to
     * the input milestone.
     */
    public void cursorPos(DoubleBuffer x, DoubleBuffer y) {
        glfwGetCursorPos(handle, x, y);
    }

    /** Did the framebuffer change size since the last call? (Reading clears the flag.) */
    public boolean consumeFramebufferResized() {
        boolean was = framebufferResized;
        framebufferResized = false;
        return was;
    }

    /** Sleep until SOME window event arrives (used to idle while minimized). */
    public void waitEvents() {
        glfwWaitEvents();
    }

    /** Destroy the window and shut GLFW down. */
    public void close() {
        // Destroy the cached standard cursors before terminating (glfwTerminate
        // would free them anyway, but tidy ownership is clearer).
        for (long cursor : cursors) {
            if (cursor != NULL) {
                glfwDestroyCursor(cursor);
            }
        }
        glfwDestroyWindow(handle);  // also detaches per-window callbacks
        glfwTerminate();
        // Detach before freeing so GLFW can't call into freed memory.
        glfwSetErrorCallback(null);
        errorCallback.free();
        resizeCallback.free();
        input.free();   // free the mouse/scroll callbacks (already detached by destroy)
    }
}
