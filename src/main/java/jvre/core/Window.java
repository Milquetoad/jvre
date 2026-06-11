package jvre.core;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
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

    private final long handle;

    // Native callbacks kept as fields so we can free them at shutdown (off-heap),
    // same pattern as the debug-messenger callback in Instance.
    private final GLFWErrorCallback errorCallback;
    private final GLFWFramebufferSizeCallback resizeCallback;

    // Set by the resize callback, consumed by the renderer. A FLAG rather than
    // reacting inside the callback: GLFW may fire it many times during one drag,
    // and mid-callback is no place to rebuild a swapchain -- the render loop
    // picks it up at a safe point in its own frame.
    private boolean framebufferResized = false;

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
    }

    /** Raw GLFW handle -- needed for Vulkan surface creation and size queries. */
    public long handle() {
        return handle;
    }

    public boolean shouldClose() {
        return glfwWindowShouldClose(handle);
    }

    public void pollEvents() {
        glfwPollEvents();
    }

    /** Current framebuffer size in PIXELS (not screen coords), written into w/h. */
    public void framebufferSize(IntBuffer w, IntBuffer h) {
        glfwGetFramebufferSize(handle, w, h);
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
        glfwDestroyWindow(handle);  // also detaches per-window callbacks
        glfwTerminate();
        // Detach before freeing so GLFW can't call into freed memory.
        glfwSetErrorCallback(null);
        errorCallback.free();
        resizeCallback.free();
    }
}
