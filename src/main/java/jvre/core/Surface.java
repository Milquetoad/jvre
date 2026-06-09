package jvre.core;

import org.lwjgl.system.MemoryStack;

import java.nio.LongBuffer;

import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/**
 * The window <-> Vulkan bridge (VkSurfaceKHR): an instance-owned opaque handle
 * GLFW creates from the window. Infrastructure tier. GLFW picks the right per-OS
 * surface call internally (e.g. vkCreateWin32SurfaceKHR), so this stays
 * cross-platform without any branching here.
 */
public class Surface {

    private final Instance instance;  // kept so we can destroy ourselves before it
    private final long handle;

    public Surface(Instance instance, Window window) {
        this.instance = instance;
        try (MemoryStack stack = stackPush()) {
            // Output param: glfwCreateWindowSurface writes the new handle here, and
            // returns a Vulkan result code (an int), not the handle itself.
            LongBuffer pSurface = stack.longs(VK_NULL_HANDLE);
            int result = glfwCreateWindowSurface(instance.handle(), window.handle(), null, pSurface);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create the window surface: " + result);
            }
            handle = pSurface.get(0);
        }
        System.out.println("Window surface created.");
    }

    /** The wrapped VkSurfaceKHR handle (a long). */
    public long handle() {
        return handle;
    }

    /** Surface is owned by the instance, so destroy it before the instance. */
    public void close() {
        vkDestroySurfaceKHR(instance.handle(), handle, null);
    }
}
