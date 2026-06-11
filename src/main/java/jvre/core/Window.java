package jvre.core;

import org.lwjgl.glfw.GLFWErrorCallback;

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

    // Native callback kept as a field so we can free it at shutdown (off-heap),
    // same pattern as the debug-messenger callback in Instance.
    private final GLFWErrorCallback errorCallback;

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
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);    // fixed until we add swapchain recreation

        handle = glfwCreateWindow(width, height, title, NULL, NULL);
        if (handle == NULL) {
            throw new RuntimeException("Failed to create the GLFW window");
        }
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

    /** Destroy the window and shut GLFW down. */
    public void close() {
        glfwDestroyWindow(handle);
        glfwTerminate();
        // Detach before freeing so GLFW can't call into freed memory.
        glfwSetErrorCallback(null);
        errorCallback.free();
    }
}
