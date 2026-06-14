package jvre;

import jvre.core.Color;
import jvre.core.Diagnostics;
import jvre.core.Input;
import jvre.core.Instance;
import jvre.core.Key;
import jvre.core.MouseButton;
import jvre.core.Renderer;
import jvre.core.Renderer2D;
import jvre.core.ShaderEffect;
import jvre.core.Surface;
import jvre.core.Texture;
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
    private float cursorSize = 48;   // the interactive cursor box; scroll resizes it
    private float spin = 0f;          // accumulated rotation, integrated from dt()
    private final StringBuilder typed = new StringBuilder();   // the demo text field's contents
    private Texture demoImage;    // a generated texture drawn via g.image(), when DEMO_2D
    private Texture demoImage2;   // a SECOND texture -- proves multi-texture batching (flush-on-switch)

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
            // Two small generated images. Real programs will load a PNG; for now
            // we synthesize recognizable pixels. TWO distinct textures in one frame
            // is what exercises the flush-on-texture-switch batching.
            demoImage = renderer.createImage(makeDemoImage(64, false), 64, 64);
            demoImage2 = renderer.createImage(makeDemoImage(64, true), 64, 64);
            // Bake the default font now (at startup) rather than lazily on the
            // first text() call mid-loop -- avoids a one-time hitch in frame 1.
            renderer.font();
        }
    }

    /**
     * Build a {@code size x size} RGBA texture: a 4x4 checker of distinct colors
     * over green/blue ramps. The ramps make UV orientation obvious (a flipped V
     * would show). {@code swap} recolors it so the two demo textures are visibly
     * different (and clearly two separate images, not one).
     */
    private static byte[] makeDemoImage(int size, boolean swap) {
        byte[] px = new byte[size * size * 4];
        int cell = size / 4;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean odd = ((x / cell) + (y / cell)) % 2 == 1;
                int i = (y * size + x) * 4;
                int ramp = x * 255 / size;
                int ramp2 = y * 255 / size;
                px[i]     = (byte) (odd ? 255 : 40);          // R
                px[i + 1] = (byte) (swap ? ramp2 : ramp);     // G
                px[i + 2] = (byte) (swap ? ramp : ramp2);     // B
                px[i + 3] = (byte) 255;                       // opaque
            }
        }
        return px;
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
        g.fillCircle(360, 300, 110, Color.rgba(80, 220, 130, 160));     // translucent green circle (SDF)
        g.fillCircle(720, 90, 14, Color.rgb(255, 255, 255));            // a small SDF circle -- crisp at any size
        g.fillRoundedRect(540, 500, 200, 80, 24, Color.rgb(120, 90, 230));  // a rounded-rect "button" (SDF)
        g.fillEllipse(180, 470, 130, 55, Color.rgb(245, 200, 40));      // a wide yellow ellipse
        g.fillTriangle(520, 60, 760, 60, 640, 230, Color.rgb(200, 80, 220));  // purple triangle
        g.fillQuad(560, 250, 700, 270, 720, 360, 540, 340, Color.rgb(40, 200, 220));  // cyan quad
        // Strokes (CPU-triangulated, no GPU line width): a thin dark line across
        // the top, and a thick diagonal -- different thicknesses, any angle.
        g.line(40, 40, 760, 40, 3, Color.rgb(20, 20, 20));             // thin near-black rule
        g.line(80, 540, 320, 410, 14, Color.rgb(255, 140, 0));         // thick orange diagonal
        // A TRANSLUCENT stroked frame -- if the corners were double-covered they'd
        // blend brighter; the non-overlapping 8-triangle frame keeps them uniform.
        g.strokeRect(430, 420, 150, 110, 12, Color.rgba(255, 255, 255, 140));
        // Stroked curves: a ring (circle outline) and a wide elliptical outline.
        g.strokeCircle(670, 180, 70, 9, Color.rgb(255, 255, 255));      // white ring
        g.strokeEllipse(360, 300, 150, 40, 6, Color.rgb(20, 20, 20));   // dark outline round the green circle
        // Polygon outlines -- the corners are MITER joins. Thick, to make the
        // mitered corners obvious (sharp, gap-free, no double-blend).
        g.strokeTriangle(360, 70, 470, 70, 415, 165, 10, Color.rgb(20, 20, 20));   // dark triangle outline
        g.strokeQuad(120, 380, 250, 360, 280, 470, 90, 450, 8, Color.rgb(255, 255, 255));  // white quad outline
        // Anchored to the BOTTOM-RIGHT via the live framebuffer size -- relative
        // layout as plain arithmetic (no coordinate mode). Resize the window and
        // this square tracks the corner while the others stay pinned top-left.
        g.fillRect(g.width() - 170, g.height() - 170, 130, 130, Color.WHITE);
        // Textured IMAGES -- the 64x64 generated textures scaled up to 120x120,
        // so NEAREST sampling shows crisp texels. Drawn after the shapes, so they
        // sit on top (one paint order across flat, SDF, and textured alike). TWO
        // distinct textures in one frame -> two draw runs (flush-on-switch).
        g.image(demoImage, 40, 120, 120, 120);
        g.image(demoImage2, 40, 250, 120, 120);
        // TEXT (SDF glyphs, the built-in DejaVu Sans). Different sizes from one
        // baked atlas -- crisp at each -- proving the SDF scales for free. The
        // multi-line string exercises '\n'.
        g.text("jvre", 470, 110, 72, Color.rgb(20, 20, 20));
        g.text("the L2 'just draw' altitude:\nshapes, images, and text.", 300, 540, 22,
                Color.rgb(30, 30, 30));
        // Transform stack: a rotated + scaled rounded-rect with a text label drawn
        // in the SAME nested transform (one group). The SDF rounded-rect stays
        // crisp under rotation/scale (screen-space AA); push/pop scope it so the
        // rest of the frame is unaffected.
        g.push();
        g.translate(650, 440);
        g.rotate((float) Math.toRadians(-18));
        g.scale(1.3f);
        g.fillRoundedRect(-55, -22, 110, 44, 12, Color.rgba(70, 160, 210, 220));
        g.text("rotated", -46, -13, 24, Color.WHITE);
        g.pop();
        // ANIMATION via the time/delta source. A dot slides with the absolute
        // clock time(); a small square spins by INTEGRATING dt() (so its speed is
        // frame-rate independent). One glance confirms both accessors are live.
        float t = renderer.time();
        g.fillCircle(400f + (float) Math.sin(t * 2.0) * 150f, 22, 11, Color.rgb(255, 220, 40));
        spin += renderer.dt() * 1.5f;
        g.push();
        g.translate(755, 565);
        g.rotate(spin);
        g.fillRect(-12, -12, 24, 24, Color.rgb(120, 90, 230));
        g.pop();
        // INTERACTIVE: a rounded box that follows the cursor (framebuffer pixels,
        // so it sits exactly under the pointer), turns red while the left button
        // is held, and resizes with the scroll wheel. One glance confirms
        // position + button + scroll all flow through the input seam.
        Input in = window.input();
        cursorSize = Math.max(12f, Math.min(240f, cursorSize + in.scrollY() * 6f));
        Color box = in.mouseDown(MouseButton.LEFT)
                ? Color.rgb(235, 70, 70) : Color.rgba(60, 200, 120, 200);
        float half = cursorSize * 0.5f;
        g.fillRoundedRect(in.mouseX() - half, in.mouseY() - half, cursorSize, cursorSize, 10, box);
        // INTERACTIVE TEXT FIELD: typed characters flow in via typedChars()
        // (layout/shift handled, character keys auto-repeat); BACKSPACE deletes,
        // ESCAPE clears. One glance confirms keyboard text + key edges.
        typed.append(in.typedChars());
        if (in.keyPressed(Key.BACKSPACE) && typed.length() > 0) {
            typed.deleteCharAt(typed.length() - 1);
        }
        if (in.keyPressed(Key.ESCAPE)) {
            typed.setLength(0);
        }
        g.text("type something: " + typed + "_", 40, 86, 22, Color.rgb(20, 20, 20));
        g.end();
    }

    private void cleanup() {
        // The demo image is caller-owned: close it BEFORE the renderer (it frees
        // VMA memory the device owns, and the device dies with the renderer).
        if (demoImage != null) {
            demoImage.close();
        }
        if (demoImage2 != null) {
            demoImage2.close();
        }
        // Reverse order of creation: the renderer (the whole device context)
        // goes first, then the instance-level objects, then the window.
        renderer.close();
        surface.close();   // owned by the instance -> destroy before it
        instance.close();
        window.close();
        System.out.println("Cleaned up. Bye.");
    }
}
