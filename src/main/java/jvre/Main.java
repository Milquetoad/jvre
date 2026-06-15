package jvre;

import jvre.core.AttribFormat;
import jvre.core.Buffer;
import jvre.core.Color;
import jvre.core.Diagnostics;
import jvre.core.Input;
import jvre.core.Instance;
import jvre.core.Key;
import jvre.core.MouseButton;
import jvre.core.Pipeline;
import jvre.core.PipelineSpec;
import jvre.core.Renderer;
import jvre.core.Renderer2D;
import jvre.core.ShaderCompiler;
import jvre.core.ShaderEffect;
import jvre.core.Surface;
import jvre.core.Texture;
import jvre.core.VertexLayout;
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
    private Pipeline triPipeline; // a USER-DEFINED pipeline (the L1 escape hatch)
    private Buffer triBuffer;     // its vertex geometry

    // A user's own shaders for the custom pipeline -- compiled at runtime via
    // ShaderCompiler (no build-time step), exactly as a jvre consumer would.
    private static final String TRI_VERT = """
            #version 450
            layout(location = 0) in vec2 inPos;
            layout(location = 1) in vec3 inColor;
            layout(location = 0) out vec3 vColor;
            void main() { gl_Position = vec4(inPos, 0.0, 1.0); vColor = inColor; }
            """;
    private static final String TRI_FRAG = """
            #version 450
            layout(location = 0) in vec3 vColor;
            layout(location = 0) out vec4 outColor;
            void main() { outColor = vec4(vColor, 1.0); }
            """;

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

        // The L1 escape hatch: a USER-DEFINED pipeline drawing a custom triangle
        // with the user's own shaders + vertex layout, recorded through the scene
        // seam. It renders UNDER the L2 shapes -- custom geometry and L2 mixed in
        // one frame, the whole point of the escape hatch.
        byte[] triVs = ShaderCompiler.compileVertex(TRI_VERT, "tri.vert");
        byte[] triFs = ShaderCompiler.compileFragment(TRI_FRAG, "tri.frag");
        VertexLayout triLayout = VertexLayout.builder(5 * Float.BYTES)
                .attribute(0, AttribFormat.VEC2, 0)               // position (clip space)
                .attribute(1, AttribFormat.VEC3, 2 * Float.BYTES) // color
                .build();
        triPipeline = renderer.createPipeline(PipelineSpec.builder()
                .vertexShader(triVs).fragmentShader(triFs)
                .vertexLayout(triLayout).label("demo-triangle").build());
        triBuffer = renderer.createVertexBuffer(new float[] {
                //  x      y       r   g   b   (clip space; scales with the window)
                0.55f, 0.58f,   1f, 0f, 0f,
                0.92f, 0.58f,   0f, 1f, 0f,
                0.73f, 0.92f,   0f, 0f, 1f,
        });
        renderer.setSceneRenderer(frame -> {
            frame.bind(triPipeline);
            frame.bindVertexBuffer(triBuffer);
            frame.draw(3);
        });
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
     * The demo scene -- a tidy, row-organized tour of the engine. Everything is
     * authored in a fixed REFERENCE space (the initial {@code WIDTH x HEIGHT}) and
     * wrapped in ONE uniform scale transform sized to the live window, so resizing
     * the window enlarges the whole scene (undistorted) for closer inspection.
     * (The custom L1 triangle is in clip space, so it already scales with the
     * window on its own.) Interactive bits convert the mouse from real pixels back
     * into reference space (divide by the scale).
     */
    private void drawShapes() {
        g.begin();
        float s = Math.min(g.width() / (float) WIDTH, g.height() / (float) HEIGHT);
        g.push();
        g.scale(s);

        // Row 1 -- FILLS: rounded-rect, circle, ellipse, triangle, quad.
        g.fillRoundedRect(40, 80, 120, 80, 16, Color.rgb(40, 120, 220));
        g.fillCircle(250, 120, 45, Color.rgb(80, 200, 130));
        g.fillEllipse(390, 120, 70, 40, Color.rgb(245, 200, 40));
        g.fillTriangle(500, 160, 600, 160, 550, 75, Color.rgb(200, 80, 220));
        g.fillQuad(640, 80, 760, 92, 750, 160, 630, 150, Color.rgb(40, 200, 220));

        // Row 2 -- STROKES: rect, circle ring, thick line, mitered triangle outline.
        g.strokeRect(40, 210, 120, 80, 6, Color.rgb(20, 20, 20));
        g.strokeCircle(250, 250, 45, 6, Color.rgb(20, 20, 20));
        g.line(330, 295, 470, 205, 6, Color.rgb(255, 140, 0));
        g.strokeTriangle(500, 290, 600, 290, 550, 205, 6, Color.rgb(20, 20, 20));

        // Row 3 -- IMAGES (two textures = two draw runs) + TEXT (SDF glyphs).
        g.image(demoImage, 40, 330, 90, 90);
        g.image(demoImage2, 150, 330, 90, 90);
        g.text("jvre", 280, 330, 52, Color.rgb(20, 20, 20));
        g.text("L2: shapes, images, text.\nL1: the RGB triangle (custom pipeline).",
                280, 388, 17, Color.rgb(70, 70, 70));

        // A nested TRANSFORM group (rotated rounded-rect + label, drawn as a unit).
        g.push();
        g.translate(660, 250);
        g.rotate((float) Math.toRadians(-15));
        g.fillRoundedRect(-60, -28, 120, 56, 12, Color.rgba(70, 160, 210, 220));
        g.text("rotated", -50, -16, 22, Color.WHITE);
        g.pop();

        // Row 4 -- ANIMATION: a dot slides via time(), a square spins via dt().
        float t = renderer.time();
        g.fillCircle(80 + (float) Math.sin(t * 2.0) * 30f, 500, 11, Color.rgb(255, 220, 40));
        spin += renderer.dt() * 1.5f;
        g.push();
        g.translate(180, 500);
        g.rotate(spin);
        g.fillRect(-13, -13, 26, 26, Color.rgb(120, 90, 230));
        g.pop();

        // INPUT: a text field (typed text; Backspace deletes, Escape clears) ...
        Input in = window.input();
        typed.append(in.typedChars());
        if (in.keyPressed(Key.BACKSPACE) && typed.length() > 0) {
            typed.deleteCharAt(typed.length() - 1);
        }
        if (in.keyPressed(Key.ESCAPE)) {
            typed.setLength(0);
        }
        g.text("type: " + typed + "_", 280, 470, 20, Color.rgb(20, 20, 20));

        // ... and a box tracking the cursor (red while held, scroll resizes it).
        // The mouse is in real pixels; divide by s to place it in our scaled space.
        cursorSize = Math.max(12f, Math.min(160f, cursorSize + in.scrollY() * 6f));
        Color box = in.mouseDown(MouseButton.LEFT)
                ? Color.rgb(235, 70, 70) : Color.rgba(60, 200, 120, 200);
        float half = cursorSize * 0.5f;
        g.fillRoundedRect(in.mouseX() / s - half, in.mouseY() / s - half,
                cursorSize, cursorSize, 10, box);

        g.pop();
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
        // Caller-owned custom pipeline + its geometry (close before the renderer).
        if (triBuffer != null) {
            triBuffer.close();
        }
        if (triPipeline != null) {
            triPipeline.close();
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
