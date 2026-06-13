package jvre;

import jvre.core.Diagnostics;
import jvre.core.Instance;
import jvre.core.Renderer;
import jvre.core.ShaderEffect;
import jvre.core.Surface;
import jvre.core.Window;

import org.lwjgl.system.Configuration;

/**
 * jvre demo entry point. After the elementaries refactor this is just WIRING:
 * the stable layer (Window -> Instance -> Surface) plus a Renderer (which owns
 * the whole device context -- Device, Swapchain, command recording, sync), and
 * the loop that pumps it. All Vulkan machinery lives in jvre.core now.
 *
 * This file is a preview of what the L1 API wants to feel like: "give me a
 * window and a renderer, then draw frames until the window closes."
 */
public class Main {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final CharSequence TITLE = "jvre - shader effect";

    // The Shadertoy altitude: a runtime-compiled user fragment shader on a
    // fullscreen triangle. Set to null to see the cube demo instead.
    private static final String EFFECT = "/demo/ripple.frag";

    // Bright orange (RGB in [0,1]) -- the renderer clears to this every frame.
    private static final float CLEAR_R = 1.0f;
    private static final float CLEAR_G = 0.4f;
    private static final float CLEAR_B = 0.0f;

    // Flip to false to build a "release" run with no validation overhead.
    private static final boolean ENABLE_VALIDATION = true;

    private Window window;
    private Instance instance;
    private Surface surface;
    private Renderer renderer;

    public static void main(String[] args) {
        // See the MemoryStack gotcha note: this machine's GPUs expose enough
        // extensions to overflow the default 64 KB per-thread stack.
        Configuration.STACK_SIZE.set(512);

        // Open the diagnostics log BEFORE anything Vulkan touches the GPU, so the
        // environment fingerprint is on disk before anything can crash. From here
        // on every console line is also captured to the file.
        Diagnostics.init("jvre demo");

        try {
            new Main().run();
        } catch (Throwable t) {
            // A Ring 2 fault (or any fatal): the log already holds the fingerprint
            // and the failure; tell the user where it is and how to send it.
            Diagnostics.reportFault(t);
            System.exit(1);
        }
    }

    public void run() {
        init();
        mainLoop();
        cleanup();
    }

    private void init() {
        window = new Window(WIDTH, HEIGHT, TITLE);
        instance = new Instance("jvre demo", ENABLE_VALIDATION);
        surface = new Surface(instance, window);
        renderer = new Renderer(instance, surface, window, CLEAR_R, CLEAR_G, CLEAR_B);

        if (EFFECT != null) {
            // This is the API Vision sketch, nearly verbatim: the user's whole
            // contribution is one fragment shader. It compiles RIGHT HERE, at
            // runtime (shaderc) -- bad GLSL fails on this line with the
            // shader's own line numbers, before any drawing starts.
            renderer.setEffect(ShaderEffect.fromFragment(EFFECT));
        }
    }

    private void mainLoop() {
        System.out.println("Entering render loop. Close the window to exit.");
        while (!window.shouldClose()) {
            window.pollEvents();
            renderer.drawFrame();
        }
        // Let the GPU finish in-flight work before cleanup frees anything.
        renderer.waitIdle();
    }

    private void cleanup() {
        // Reverse order of creation: the renderer (the whole device context)
        // goes first, then the instance-level objects, then the window.
        renderer.close();
        surface.close();   // owned by the instance -> destroy before it
        instance.close();
        window.close();
        System.out.println("Cleaned up. Bye.");
    }
}
