package jvre.demo;

import jvre.core.Diagnostics;
import jvre.core.Instance;
import jvre.core.Renderer;
import jvre.core.RendererOptions;
import jvre.core.Surface;
import jvre.core.Window;

/**
 * Regression probe for the "Out of stack space" crash a user hit: an extension-rich
 * driver reports enough device extensions that enumerating them onto LWJGL's default
 * 64 KB {@code MemoryStack} overflowed it, crashing in device selection.
 *
 * <p>This probe DELIBERATELY does NOT bump {@code Configuration.STACK_SIZE}: it boots
 * the full WINDOWED path (the only path that enumerates device extensions -- headless
 * requires none) on the default 64 KB stack. If jvre's enumeration is heap-allocated
 * (the fix), this completes; with the old stack allocation it would throw
 * {@code OutOfMemoryError: Out of stack space} on a driver with many extensions.
 *
 * <p>Frame-capped to 0 -- it only needs to construct the Renderer (which runs device
 * selection) and tear down. Run: {@code gradlew runStackProbe}.
 */
public final class StackProbe {

    public static void main(String[] args) {
        // NB: no Configuration.STACK_SIZE.set(...) -- that's the whole point.
        Diagnostics.init("jvre stack probe");

        Window window = new Window(320, 240, "jvre - stack probe");
        Instance instance = new Instance("jvre stackprobe", true);
        Surface surface = new Surface(instance, window);
        // The Renderer constructor runs device selection, which enumerates device
        // extensions -- the allocation that used to overflow the default stack.
        Renderer renderer = new Renderer(instance, surface, window, RendererOptions.builder().build());

        System.out.println("StackProbe: booted the windowed renderer on the DEFAULT 64 KB stack "
                + "(device-extension enumeration did not overflow). PASS.");

        renderer.waitIdle();
        renderer.close();
        surface.close();
        instance.close();
        window.close();
    }
}
