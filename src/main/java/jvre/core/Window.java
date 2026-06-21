package jvre.core;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.stb.STBImage.stbi_failure_reason;
import static org.lwjgl.stb.STBImage.stbi_image_free;
import static org.lwjgl.stb.STBImage.stbi_load_from_memory;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;

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

    // The current CUSTOM (image) cursor, or NULL. One at a time: setting a new one
    // destroys the previous. Destroyed at close.
    private long customCursor = NULL;

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

    /**
     * Set a CUSTOM mouse cursor from a classpath image ({@code resourcePath}, e.g.
     * {@code "/cursors/wand.png"}), decoded via stb_image. {@code hotspotX} /
     * {@code hotspotY} are the pixel in the image that actually points -- {@code
     * (0,0)} = top-left (an arrow tip), the centre for a crosshair. The previously
     * set custom cursor (if any) is destroyed. Works on all desktop platforms.
     */
    public void setCursor(String resourcePath, int hotspotX, int hotspotY) {
        int[] wh = new int[2];
        ByteBuffer pixels = decodeResourceRgba(resourcePath, wh);
        try (MemoryStack stack = stackPush()) {
            applyCustomCursor(stack, pixels, wh[0], wh[1], hotspotX, hotspotY);
        } finally {
            stbi_image_free(pixels);
        }
    }

    /**
     * Set a custom mouse cursor from raw RGBA8 pixels ({@code width*height*4} bytes,
     * row-major, top-to-bottom). See {@link #setCursor(String, int, int)} for the
     * hotspot meaning.
     */
    public void setCursor(byte[] rgba, int width, int height, int hotspotX, int hotspotY) {
        if (rgba.length != width * height * 4) {
            throw new IllegalArgumentException("Cursor is " + width + "x" + height
                    + " (expected " + (width * height * 4) + " RGBA bytes), got " + rgba.length);
        }
        ByteBuffer pixels = memAlloc(rgba.length);
        pixels.put(rgba).flip();
        try (MemoryStack stack = stackPush()) {
            applyCustomCursor(stack, pixels, width, height, hotspotX, hotspotY);
        } finally {
            memFree(pixels);
        }
    }

    /** Create a GLFW cursor from one RGBA image + hotspot, set it, then destroy the
     *  PREVIOUS custom cursor (in that order, so the cursor never momentarily reverts
     *  to the default while swapping). */
    private void applyCustomCursor(MemoryStack stack, ByteBuffer rgba, int width, int height,
                                   int hotspotX, int hotspotY) {
        GLFWImage image = GLFWImage.malloc(stack);
        image.width(width).height(height).pixels(rgba);
        long created = glfwCreateCursor(image, hotspotX, hotspotY);
        if (created == NULL) {
            throw new RuntimeException("GLFW failed to create a " + width + "x" + height
                    + " custom cursor");
        }
        long previous = customCursor;
        customCursor = created;
        glfwSetCursor(handle, customCursor);
        if (previous != NULL) {
            glfwDestroyCursor(previous);
        }
    }

    /**
     * Set the cursor MODE: {@link CursorMode#NORMAL} (visible, moves freely),
     * {@link CursorMode#HIDDEN} (invisible while over the window, still free), or
     * {@link CursorMode#DISABLED} (invisible AND locked to the window centre with
     * unlimited virtual motion -- for first-person / orbit mouselook; read the
     * motion from the per-frame cursor position, which then grows unbounded).
     */
    public void setCursorMode(CursorMode mode) {
        glfwSetInputMode(handle, GLFW_CURSOR, mode.glfw);
    }

    /**
     * Set the window's ICON (taskbar / title-bar / Alt-Tab image) from a classpath
     * image resource ({@code resourcePath}, e.g. {@code "/icons/app.png"}), decoded
     * via stb_image (PNG/JPEG/...). A square power-of-two source (e.g. 32x32 or
     * 64x64) is conventional; the OS scales as needed.
     *
     * <p>Honored on <b>Windows and Linux (X11)</b>. On macOS the icon comes from the
     * app bundle, and on Wayland from the desktop file, so GLFW IGNORES this there --
     * a documented no-op, harmless to call (your cross-platform code needs no guard).
     */
    public void setIcon(String resourcePath) {
        int[] wh = new int[2];
        ByteBuffer pixels = decodeResourceRgba(resourcePath, wh);
        try (MemoryStack stack = stackPush()) {
            // pixels stays at position 0 (we never get() it), so it points at the
            // RGBA data for GLFW and frees cleanly below.
            applyIcon(stack, pixels, wh[0], wh[1]);
        } finally {
            stbi_image_free(pixels);
        }
    }

    /**
     * Set the window's icon from raw RGBA8 pixels ({@code width*height*4} bytes,
     * row-major, top-to-bottom) -- for an icon you generate or decode yourself. See
     * {@link #setIcon(String)} for the platform notes.
     */
    public void setIcon(byte[] rgba, int width, int height) {
        if (rgba.length != width * height * 4) {
            throw new IllegalArgumentException("Icon is " + width + "x" + height
                    + " (expected " + (width * height * 4) + " RGBA bytes), got " + rgba.length);
        }
        ByteBuffer pixels = memAlloc(rgba.length);
        pixels.put(rgba).flip();
        try (MemoryStack stack = stackPush()) {
            applyIcon(stack, pixels, width, height);
        } finally {
            memFree(pixels);   // GLFW copied the data during the set call
        }
    }

    /** Hand one RGBA image to GLFW as the window icon. The pixels buffer must stay
     *  valid and at position 0 for the duration of the call (GLFW copies it). */
    private void applyIcon(MemoryStack stack, ByteBuffer rgba, int width, int height) {
        GLFWImage.Buffer icon = GLFWImage.malloc(1, stack);
        icon.position(0).width(width).height(height).pixels(rgba);
        icon.position(0);
        glfwSetWindowIcon(handle, icon);
    }

    /**
     * Decode a classpath image resource to RGBA8 via stb_image -- shared by the
     * window-icon and custom-cursor paths (both hand RGBA pixels + dimensions to
     * GLFW, which copies them during the call). Returns the native pixel buffer at
     * position 0 (free it with {@code stbi_image_free}) and writes the dimensions
     * into {@code wh[0]} (width) and {@code wh[1]} (height). Forces 4 channels so a
     * source with no alpha still comes back RGBA.
     */
    private static ByteBuffer decodeResourceRgba(String resourcePath, int[] wh) {
        ByteBuffer fileBytes = readResource(resourcePath);   // native buffer -- memFree below
        try (MemoryStack stack = stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channelsInFile = stack.mallocInt(1);
            ByteBuffer pixels = stbi_load_from_memory(fileBytes, w, h, channelsInFile, 4);
            if (pixels == null) {
                throw new RuntimeException("stb_image failed to decode " + resourcePath
                        + ": " + stbi_failure_reason());
            }
            wh[0] = w.get(0);
            wh[1] = h.get(0);
            return pixels;   // survives the stack pop (native heap, not stack); caller frees
        } finally {
            memFree(fileBytes);
        }
    }

    /** Read a classpath resource into a native {@link ByteBuffer} (freed by the
     *  caller with {@code memFree}) -- what stb_image decodes an icon/cursor from. */
    private static ByteBuffer readResource(String path) {
        try (InputStream in = Window.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new RuntimeException("Image resource not found on the classpath: " + path);
            }
            byte[] bytes = in.readAllBytes();
            ByteBuffer buf = memAlloc(bytes.length);
            buf.put(bytes).flip();
            return buf;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read icon resource: " + path, e);
        }
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
        if (customCursor != NULL) {
            glfwDestroyCursor(customCursor);
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
