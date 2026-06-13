package jvre;

import jvre.core.Color;
import jvre.core.Diagnostics;
import jvre.core.Instance;
import jvre.core.Renderer;
import jvre.core.Renderer2D;
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
    private static final CharSequence TITLE = "jvre - 2D shapes";

    // Pick a demo. DEMO_2D = the L2 "just draw" surface (Renderer2D). Otherwise
    // EFFECT chooses the Shadertoy fullscreen shader, or null falls back to the
    // 3D cube.
    private static final boolean DEMO_2D = true;
    private static final String EFFECT = DEMO_2D ? null : "/demo/ripple.frag";

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
    private Renderer2D g;   // the L2 surface, when DEMO_2D

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
        if (DEMO_2D) {
            // The L2 altitude: ask the renderer for its 2D surface. From here the
            // loop is begin() / draw shapes / end() -- no Vulkan in sight.
            g = renderer.renderer2D();
        }
    }

    private void mainLoop() {
        System.out.println("Entering render loop. Close the window to exit.");
        while (!window.shouldClose()) {
            window.pollEvents();
            if (g != null) {
                drawShapes();
            }
            renderer.drawFrame();
        }
        // Let the GPU finish in-flight work before cleanup frees anything.
        renderer.waitIdle();
    }

    /**
     * The L2 demo: a few rectangles in pixel coordinates (top-left origin),
     * including a translucent red one that overlaps the blue rectangle AND the
     * orange clear -- so one glance confirms solid fills, alpha blending, and the
     * pixel coordinate system all work.
     */
    private void drawShapes() {
        g.begin();
        g.fillRect(100, 100, 220, 160, Color.rgb(40, 120, 220));       // solid blue, anchored top-left
        g.fillRect(250, 200, 220, 160, Color.rgba(220, 60, 60, 128));  // translucent red, overlapping
        // A filled circle (centre + radius), tessellated. Translucent so its
        // round edge shows against the rectangles it overlaps.
        g.fillCircle(360, 300, 110, Color.rgba(80, 220, 130, 160));     // translucent green circle
        g.fillEllipse(180, 470, 130, 55, Color.rgb(245, 200, 40));      // a wide yellow ellipse
        g.fillTriangle(520, 60, 760, 60, 640, 230, Color.rgb(200, 80, 220));  // purple triangle
        g.fillQuad(560, 250, 700, 270, 720, 360, 540, 340, Color.rgb(40, 200, 220));  // cyan quad
        // Anchored to the BOTTOM-RIGHT via the live framebuffer size -- relative
        // layout as plain arithmetic (no coordinate mode). Resize the window and
        // this square tracks the corner while the others stay pinned top-left.
        g.fillRect(g.width() - 170, g.height() - 170, 130, 130, Color.WHITE);
        g.end();
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
