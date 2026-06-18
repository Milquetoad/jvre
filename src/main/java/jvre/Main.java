package jvre;

import jvre.core.AttribFormat;
import jvre.core.Buffer;
import jvre.core.Camera;
import jvre.core.Color;
import jvre.core.Cull;
import jvre.core.CursorShape;
import jvre.core.Diagnostics;
import jvre.core.Filter;
import jvre.core.Font;
import jvre.core.Input;
import jvre.core.Instance;
import jvre.core.Key;
import jvre.core.MouseButton;
import jvre.core.Pipeline;
import jvre.core.PipelineSpec;
import jvre.core.Renderer;
import jvre.core.Renderer2D;
import jvre.core.RendererOptions;
import jvre.core.ShaderCompiler;
import jvre.core.ShaderEffect;
import jvre.core.Stage;
import jvre.core.Surface;
import jvre.core.Texture;
import jvre.core.TextureOptions;
import jvre.core.VertexLayout;
import jvre.core.Window;
import jvre.core.WrapMode;

import org.joml.Matrix4f;
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

    // Render-to-texture proof (Roadmap 4a): when true, the L1 cube renders into an
    // OFFSCREEN target each frame, and the result is sampled back into the 2D scene
    // via g.image() -- an offscreen pass composited as a sprite. (When false, the
    // cube renders directly in the main pass, the original dogfood.)
    private static final boolean DEMO_RTT = DEMO_2D;

    // Bright orange (RGB in [0,1]) -- the renderer clears to this every frame.
    private static final float CLEAR_R = 1.0f;
    private static final float CLEAR_G = 0.4f;
    private static final float CLEAR_B = 0.0f;

    // Flip to false to build a "release" run with no validation overhead.
    private static final boolean ENABLE_VALIDATION = true;

    // Present mode: true = vsync (FIFO, capped to the refresh rate); false =
    // uncapped (MAILBOX where available). Toggle + watch the console FPS line.
    private static final boolean VSYNC = true;

    // Anti-aliasing sample count: 1 (off), 2, 4, 8 (clamped to the device max).
    // Set to 1 to see jaggies, 4/8 for smooth edges.
    private static final int MSAA = 4;

    // GPU override: a name substring ("RTX", "Intel", ...) to prefer over the
    // default scoring, or null = auto-pick the best (discrete > integrated).
    // Watch the "Picked GPU:" console line.
    private static final String PREFER_GPU = null;

    // FPS counter (proves the vsync knob): frames since the last 1s report.
    private long fpsLastNanos = System.nanoTime();
    private int fpsFrames = 0;

    private Window window;
    private Instance instance;
    private Surface surface;
    private Renderer renderer;
    private Renderer2D g;   // the L2 surface, when DEMO_2D
    private float cursorSize = 48;   // the interactive cursor box; scroll resizes it
    private float spin = 0f;          // accumulated rotation, integrated from dt()
    private final StringBuilder typed = new StringBuilder();   // the demo text field's contents
    private String lastDropped = "";   // Batch 1: names of the last files dropped on the window
    private Texture demoImage;    // a generated texture drawn via g.image(), when DEMO_2D
    private Texture demoImage2;   // a SECOND texture -- proves multi-texture batching (flush-on-switch)
    private Font customFont;      // a font loaded via the public renderer.loadFont (DEMO_2D)
    private Pipeline cubePipeline; // a USER-DEFINED pipeline (the L1 escape hatch)
    private Buffer cubeVerts;      // its vertex geometry (an indexed, textured cube)
    private Buffer cubeIndices;    // its UINT16 indices
    private Texture cubeTexture;   // its checker texture
    private final Camera camera = new Camera();  // computes the cube's view + projection
    private jvre.core.RenderTarget cubeTarget;   // offscreen target the cube renders into (DEMO_RTT)
    private jvre.core.RenderTarget canvasTarget; // offscreen L2 canvas target (DEMO_RTT)
    private Renderer2D gc;                        // a Renderer2D drawing into canvasTarget
    private boolean screenshotRequested;         // F2 -> read the canvas target back + write a PNG (4d)

    // A user's own shaders for the custom pipeline -- compiled at runtime via
    // ShaderCompiler (no build-time step), exactly as a jvre consumer would. The
    // vertex shader reads a UBO (mat4 MVP @ binding 0); the fragment shader a push
    // constant (a brightness pulse).
    private static final String CUBE_VERT = """
            #version 450
            layout(location = 0) in vec3 inPos;
            layout(location = 1) in vec3 inColor;
            layout(location = 2) in vec2 inUv;
            layout(set = 0, binding = 0) uniform U { mat4 mvp; } u;
            layout(location = 0) out vec3 vColor;
            layout(location = 1) out vec2 vUv;
            void main() { gl_Position = u.mvp * vec4(inPos, 1.0); vColor = inColor; vUv = inUv; }
            """;
    private static final String CUBE_FRAG = """
            #version 450
            layout(location = 0) in vec3 vColor;
            layout(location = 1) in vec2 vUv;
            layout(set = 0, binding = 1) uniform sampler2D tex;
            layout(push_constant) uniform Push { float pulse; } pc;
            layout(location = 0) out vec4 outColor;
            void main() {
                vec4 t = texture(tex, vUv * 2.0);    // grayscale checker, UV 0..2 (tiles 2x2 with REPEAT)
                outColor = vec4(t.rgb * vColor * pc.pulse, t.a);  // tinted per face, pulsed
            }
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
        renderer = new Renderer(instance, surface, window, RendererOptions.builder()
                .clearColor(CLEAR_R, CLEAR_G, CLEAR_B)
                .vsync(VSYNC)
                .msaa(MSAA)
                .preferGpu(PREFER_GPU)
                .build());

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
            // Two distinct textures in one frame -- exercises the flush-on-
            // texture-switch batching. The first is LOADED from a PNG file via
            // stb_image (renderer.loadImage); the second is synthesized in code
            // (renderer.createImage) -- both paths, side by side.
            demoImage = renderer.loadImage("/demo/test-image.png");
            demoImage2 = renderer.createImage(makeDemoImage(64, true), 64, 64);
            // Bake the default font now (at startup) rather than lazily on the
            // first text() call mid-loop -- avoids a one-time hitch in frame 1.
            renderer.font();
            // A CUSTOM font via the public loadFont path (4b) -- caller-owned, baked
            // at 64px. We reuse the bundled DejaVu TTF (shipping a second typeface is
            // a licensing matter), but ANY .ttf on the classpath loads identically;
            // this exercises loadFont -> text(Font, ...) -> close().
            customFont = renderer.loadFont("/fonts/DejaVuSans.ttf", 64f);
        }

        // The L1 escape-hatch DOGFOOD: reproduce jvre's own textured 3D cube using
        // NOTHING but the public API -- the user's shaders, the [pos|color|uv]
        // vertex layout, a UBO (MVP from a Camera), a checker texture, a push pulse,
        // depth + back-face cull. Recorded through the scene seam, UNDER the L2
        // content (a 3D world + a 2D UI overlay in one frame).
        byte[] cubeVs = ShaderCompiler.compileVertex(CUBE_VERT, "cube.vert");
        byte[] cubeFs = ShaderCompiler.compileFragment(CUBE_FRAG, "cube.frag");
        VertexLayout cubeLayout = VertexLayout.builder(8 * Float.BYTES)
                .attribute(0, AttribFormat.VEC3, 0)               // position
                .attribute(1, AttribFormat.VEC3, 3 * Float.BYTES) // color
                .attribute(2, AttribFormat.VEC2, 6 * Float.BYTES) // uv
                .build();
        cubePipeline = renderer.createPipeline(PipelineSpec.builder()
                .vertexShader(cubeVs).fragmentShader(cubeFs)
                .vertexLayout(cubeLayout)
                .depthTest(true).depthWrite(true).cull(Cull.BACK)  // closed opaque mesh
                .uniformBuffer(16 * Float.BYTES, Stage.VERTEX)     // mat4 MVP
                .texture(Stage.FRAGMENT)                           // the checker
                .pushConstants(Float.BYTES, Stage.FRAGMENT)        // float pulse
                .label("demo-cube").build());
        // Batch 2: the cube's texture exercises the whole sampler config --
        //  - WrapMode.REPEAT: it samples UV 0..2 (CUBE_FRAG), so the checker TILES
        //    2x2 per face instead of clamping/stretching at the edge;
        //  - mipmaps + anisotropy: smoother, less shimmery checker on the tilted /
        //    receding cube faces (the minification regime), generated GPU-side.
        cubeTexture = renderer.createImage(makeChecker(256, 32), 256, 256,
                TextureOptions.builder().filter(Filter.LINEAR).wrap(WrapMode.REPEAT)
                        .mipmaps(true).anisotropy(true).build());
        cubeVerts = renderer.createVertexBuffer(new float[] {
                //    x      y      z       r     g     b      u   v   (per-face, CCW from outside)
                -0.5f,-0.5f, 0.5f, 1.0f,0.3f,0.3f, 0f,0f,  0.5f,-0.5f, 0.5f, 1.0f,0.3f,0.3f, 1f,0f,
                 0.5f, 0.5f, 0.5f, 1.0f,0.3f,0.3f, 1f,1f, -0.5f, 0.5f, 0.5f, 1.0f,0.3f,0.3f, 0f,1f, // +Z red
                 0.5f,-0.5f,-0.5f, 0.3f,1.0f,0.3f, 0f,0f, -0.5f,-0.5f,-0.5f, 0.3f,1.0f,0.3f, 1f,0f,
                -0.5f, 0.5f,-0.5f, 0.3f,1.0f,0.3f, 1f,1f,  0.5f, 0.5f,-0.5f, 0.3f,1.0f,0.3f, 0f,1f, // -Z green
                 0.5f,-0.5f, 0.5f, 0.3f,0.3f,1.0f, 0f,0f,  0.5f,-0.5f,-0.5f, 0.3f,0.3f,1.0f, 1f,0f,
                 0.5f, 0.5f,-0.5f, 0.3f,0.3f,1.0f, 1f,1f,  0.5f, 0.5f, 0.5f, 0.3f,0.3f,1.0f, 0f,1f, // +X blue
                -0.5f,-0.5f,-0.5f, 1.0f,1.0f,0.3f, 0f,0f, -0.5f,-0.5f, 0.5f, 1.0f,1.0f,0.3f, 1f,0f,
                -0.5f, 0.5f, 0.5f, 1.0f,1.0f,0.3f, 1f,1f, -0.5f, 0.5f,-0.5f, 1.0f,1.0f,0.3f, 0f,1f, // -X yellow
                -0.5f, 0.5f, 0.5f, 0.3f,1.0f,1.0f, 0f,0f,  0.5f, 0.5f, 0.5f, 0.3f,1.0f,1.0f, 1f,0f,
                 0.5f, 0.5f,-0.5f, 0.3f,1.0f,1.0f, 1f,1f, -0.5f, 0.5f,-0.5f, 0.3f,1.0f,1.0f, 0f,1f, // +Y cyan
                -0.5f,-0.5f,-0.5f, 1.0f,0.3f,1.0f, 0f,0f,  0.5f,-0.5f,-0.5f, 1.0f,0.3f,1.0f, 1f,0f,
                 0.5f,-0.5f, 0.5f, 1.0f,0.3f,1.0f, 1f,1f, -0.5f,-0.5f, 0.5f, 1.0f,0.3f,1.0f, 0f,1f, // -Y magenta
        });
        cubeIndices = renderer.createIndexBuffer(new short[] {
                 0, 1, 2,  2, 3, 0,    4, 5, 6,  6, 7, 4,    8, 9,10, 10,11, 8,
                12,13,14, 14,15,12,   16,17,18, 18,19,16,   20,21,22, 22,23,20,
        });
        if (DEMO_RTT) {
            // Render-to-texture: a square offscreen target (inherits the renderer's
            // MSAA) the cube tumbles inside, centered. Enqueued each frame in the
            // loop; sampled back in drawShapes via g.image(cubeTarget.texture(),...).
            // 1024^2: comfortably above the sprite's on-screen pixel footprint even
            // on a maximized 4K window, so it stays crisp (the "match target
            // resolution to its display footprint" principle, with headroom).
            cubeTarget = renderer.createRenderTarget(1024, 1024);
            // The L2 createGraphics analog: an offscreen canvas we draw SHAPES into
            // (gc), composited back as a sprite. Same RTT plumbing, L2 content.
            canvasTarget = renderer.createRenderTarget(512, 512);
            gc = renderer.createCanvas(canvasTarget);
        } else {
            // The original dogfood: the cube renders directly in the main pass,
            // offset to the lower-right, under the 2D UI.
            renderer.setSceneRenderer(frame -> renderCube(frame,
                    g.width() / (float) g.height(), 1.4f, -1.2f));
        }
    }

    /**
     * Record the tumbling textured cube through the L1 {@link jvre.core.FrameRenderer}
     * seam -- shared by the main-pass dogfood and the offscreen render-to-texture
     * path. {@code aspect} is the target's aspect ratio (1.0 for the square offscreen
     * target, the window's for the main pass); {@code dx}/{@code dy} offset the model
     * in view space (0,0 = centered, which is what the offscreen target wants).
     */
    private void renderCube(jvre.core.FrameRenderer frame, float aspect, float dx, float dy) {
        float t = renderer.time();
        camera.perspective(45f, aspect, 0.1f, 100f).lookAt(0, 0, 4,  0, 0, 0,  0, 1, 0);
        Matrix4f model = new Matrix4f()
                .translate(dx, dy, 0f)
                .rotateX(t * 0.6f).rotateY(t);           // tumble so all faces show
        float[] mvp = new Matrix4f(camera.viewProjection()).mul(model).get(new float[16]);
        float pulse = 0.85f + 0.15f * (float) Math.sin(t * 3.0);
        frame.bind(cubePipeline);
        frame.uniform(mvp);                          // write this frame's UBO
        frame.texture(cubeTexture);                  // bind this frame's texture
        frame.pushConstants(new float[] { pulse });
        frame.bindVertexBuffer(cubeVerts);
        frame.bindIndexBuffer(cubeIndices);
        frame.drawIndexed(36);
    }

    /**
     * Draw L2 shapes into the offscreen canvas (the createGraphics analog). Exactly
     * the same Renderer2D API as the main surface -- begin / shapes / end -- but
     * {@code gc.width()/height()} report the TARGET's size, and the result lands in
     * {@code canvasTarget.texture()} for drawShapes to composite. A self-contained
     * little animated scene to prove "draw shapes anywhere, sample the image."
     */
    private void drawCanvas() {
        gc.begin();
        float t = renderer.time();
        int w = gc.width();
        int h = gc.height();
        // A translucent panel so the sprite reads as a framed mini-scene.
        gc.fillRoundedRect(0, 0, w, h, 36, Color.rgba(28, 32, 46, 235));
        // A circle sliding left/right + a square spinning at the centre.
        float cx = w * 0.5f + (float) Math.sin(t * 1.7) * (w * 0.28f);
        gc.fillCircle(cx, h * 0.40f, h * 0.10f, Color.rgb(90, 200, 255));
        gc.push();
        gc.translate(w * 0.5f, h * 0.40f);
        gc.rotate(t);
        float sq = h * 0.13f;
        gc.fillRect(-sq, -sq, sq * 2, sq * 2, Color.rgba(255, 180, 60, 200));
        gc.pop();
        gc.text("L2 canvas", w * 0.10f, h * 0.66f, h * 0.12f, Color.WHITE);
        gc.text("drawn offscreen", w * 0.10f, h * 0.80f, h * 0.07f, Color.rgb(170, 180, 200));
        gc.end();
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

    /** A grayscale checkerboard (white / dark gray), {@code cell} texels per square,
     *  as opaque R8G8B8A8 -- the cube's texture, tinted per face by the vertex color. */
    private static byte[] makeChecker(int size, int cell) {
        byte[] px = new byte[size * size * 4];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int v = (((x / cell) + (y / cell)) & 1) == 0 ? 0xFF : 0x40;
                int i = (y * size + x) * 4;
                px[i] = px[i + 1] = px[i + 2] = (byte) v;
                px[i + 3] = (byte) 255;
            }
        }
        return px;
    }

    private void mainLoop() {
        System.out.println("Entering render loop. Close the window to exit.");
        if (canvasTarget != null) {
            System.out.println("Press F2 to save a PNG screenshot of the L2 canvas (render-to-texture readback).");
        }
        if (EFFECT != null) {
            System.out.println("Press F5 to LIVE-RELOAD the effect shader (edit "
                    + EFFECT + " and press F5 -- no restart).");
        }
        while (!window.shouldClose()) {
            window.pollEvents();

            // F5: live-reload the effect from its source file. Re-compiles + re-runs
            // the effect-contract check at ShaderEffect creation, so a broken edit
            // throws HERE -- we print it and KEEP the running shader (the new effect
            // is never installed). A good edit swaps in place via setEffect's
            // hot-rebuild. The whole point of the Batch 4 reload hook, dogfooded.
            if (EFFECT != null && window.input().keyPressed(Key.F5)) {
                try {
                    renderer.setEffect(ShaderEffect.fromFragment(EFFECT));
                    System.out.println("Effect reloaded: " + EFFECT);
                } catch (RuntimeException e) {
                    System.out.println("Effect reload failed (keeping the running shader):\n"
                            + e.getMessage());
                }
            }

            // Offscreen pass: render the cube into its target BEFORE drawFrame
            // records it (and the main pass that samples it). Immediate mode --
            // enqueued every frame, like the shape batch.
            if (cubeTarget != null) {
                renderer.drawToTarget(cubeTarget, frame -> renderCube(frame, 1f, 0f, 0f));
            }
            if (gc != null) {
                drawCanvas();   // draw L2 shapes into the offscreen canvas this frame
            }
            if (g != null) {
                drawShapes();
            }
            renderer.drawFrame();

            // 4d: act on a screenshot request now that the canvas target is rendered.
            if (screenshotRequested) {
                screenshotRequested = false;
                saveCanvasScreenshot();
            }

            // FPS report once a second -- vsync ON caps near the refresh rate;
            // OFF runs uncapped (the present-mode knob, made observable).
            fpsFrames++;
            long n = System.nanoTime();
            if (n - fpsLastNanos >= 1_000_000_000L) {
                System.out.println("FPS: " + fpsFrames + (VSYNC ? " (vsync)" : " (uncapped)"));
                // Batch 1: live window title (runtime setTitle).
                window.setTitle(TITLE + "  |  " + fpsFrames + " FPS");
                fpsFrames = 0;
                fpsLastNanos = n;
            }
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

        // Row 2 -- STROKES: rect, circle ring, ellipse ring, thick line, mitered triangle outline.
        g.strokeRect(40, 210, 120, 80, 6, Color.rgb(20, 20, 20));
        g.strokeCircle(250, 250, 45, 6, Color.rgb(20, 20, 20));
        g.strokeEllipse(390, 250, 70, 40, 6, Color.rgb(20, 20, 20));
        g.line(330, 295, 470, 205, 6, Color.rgb(255, 140, 0));
        g.strokeTriangle(560, 290, 660, 290, 610, 205, 6, Color.rgb(20, 20, 20));

        // Row 3 -- IMAGES (two textures = two draw runs) + TEXT (SDF glyphs).
        g.image(demoImage, 40, 330, 90, 90);
        g.image(demoImage2, 150, 330, 90, 90);
        g.text("jvre", 280, 330, 52, Color.rgb(20, 20, 20));
        g.text("L2: shapes, images, text.\nL1: the textured cube (custom pipeline: UBO + texture + push + camera).",
                280, 388, 17, Color.rgb(70, 70, 70));

        // RENDER-TO-TEXTURE showcase (right column): two offscreen passes rendered
        // this frame, each sampled back as a sprite. Top = an entire 3D pass (the
        // cube) used as an image (L1); bottom = L2 shapes drawn into a canvas (the
        // createGraphics analog). Both are just textures here.
        if (cubeTarget != null) {
            g.text("render-to-texture:", 612, 286, 13, Color.rgb(40, 40, 40));
            g.image(cubeTarget.texture(), 620, 296, 130, 130);     // L1: cube into a target
            g.image(canvasTarget.texture(), 620, 432, 130, 130);   // L2: shapes into a canvas
        }

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
        // 4d: F2 requests a readback screenshot of the L2 canvas target (acted on
        // after drawFrame, when this frame's canvas has actually been rendered).
        if (in.keyPressed(Key.F2) && canvasTarget != null) {
            screenshotRequested = true;
        }
        // Batch 1: clipboard -- Ctrl+C copies the field, Ctrl+V pastes into it.
        boolean ctrl = in.keyDown(Key.LEFT_CONTROL) || in.keyDown(Key.RIGHT_CONTROL);
        if (ctrl && in.keyPressed(Key.C)) {
            window.setClipboard(typed.toString());
        }
        if (ctrl && in.keyPressed(Key.V)) {
            String clip = window.clipboard();
            if (clip != null) {
                typed.append(clip);
            }
        }
        g.text("type: " + typed + "_  (Ctrl+C/V)", 280, 470, 20, Color.rgb(20, 20, 20));

        // Batch 1: file drop -- remember the names dropped this frame, and show them.
        String[] drops = in.droppedFiles();
        if (drops.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (String d : drops) {
                sb.append(new java.io.File(d).getName()).append("  ");
            }
            lastDropped = sb.toString().trim();
        }
        g.text("drop files here -> " + (lastDropped.isEmpty() ? "(none yet)" : lastDropped),
                280, 500, 14, Color.rgb(70, 70, 70));
        // Batch 1: HiDPI content scale, for clarity (L2 already draws in framebuffer px).
        g.text(String.format("content scale: %.2fx", window.contentScaleX()),
                280, 522, 13, Color.rgb(110, 110, 110));

        // 4b: a line drawn with a font from renderer.loadFont (the custom-font path).
        g.text(customFont, "loadFont(): your own TTF", 40, 556, 22, Color.rgb(20, 20, 20));

        // Batch 3: kerning -- pairs like AV / To / Wo / Ya / LY tuck together
        // (DejaVu carries ~220 kern pairs; the pen is nudged per pair).
        g.text("kerning: AVA To Wo Ya We LY P.", 40, 582, 17, Color.rgb(20, 20, 20));

        // 4c: RECT CLIP -- subsequent drawing restricted to a box (pushClip/popClip).
        // The grey outline shows the bounds; the circle slides through, clipped at
        // every edge, and the tall label is clipped top/bottom. The clip is mapped
        // through the active scale(s) transform, so it tracks the scaled layout.
        float clipX = 400, clipY = 478, clipW = 150, clipH = 64;
        g.pushClip(clipX, clipY, clipW, clipH);
        float tc = renderer.time();
        float bx = clipX + clipW * 0.5f + (float) Math.sin(tc * 1.6) * (clipW * 0.6f);
        g.fillCircle(bx, clipY + clipH * 0.5f, 40, Color.rgb(90, 200, 130));
        g.text("pushClip", clipX + 8, clipY + 6, 30, Color.rgb(20, 20, 20));
        g.popClip();
        // Frame drawn AFTER popClip, so the outline sits on TOP of the clipped
        // content (a clean window border) instead of being painted over by it.
        g.strokeRect(clipX, clipY, clipW, clipH, 2, Color.rgb(120, 120, 120));
        g.text("rect clip", clipX, clipY + clipH + 6, 13, Color.rgb(40, 40, 40));

        // ... and a box tracking the cursor (red while held, scroll resizes it).
        // The mouse is in real pixels; divide by s to place it in our scaled space.
        cursorSize = Math.max(12f, Math.min(160f, cursorSize + in.scrollY() * 6f));
        Color box = in.mouseDown(MouseButton.LEFT)
                ? Color.rgb(235, 70, 70) : Color.rgba(60, 200, 120, 200);
        float half = cursorSize * 0.5f;
        g.fillRoundedRect(in.mouseX() / s - half, in.mouseY() / s - half,
                cursorSize, cursorSize, 10, box);

        // Batch 1: standard OS cursor shapes by hover region -- an I-beam over the
        // text field, a hand over the clip "window", an arrow elsewhere. (Mouse in
        // real pixels -> reference space by dividing by the scale s.)
        float mx = in.mouseX() / s, my = in.mouseY() / s;
        if (mx >= 280 && mx <= 520 && my >= 452 && my <= 478) {
            window.setCursor(CursorShape.IBEAM);
        } else if (mx >= clipX && mx <= clipX + clipW && my >= clipY && my <= clipY + clipH) {
            window.setCursor(CursorShape.HAND);
        } else {
            window.setCursor(CursorShape.ARROW);
        }

        g.pop();
        g.end();
    }

    /**
     * Read the offscreen L2 canvas target back to CPU memory (RGBA8) and write it
     * to a PNG -- proving render-to-texture readback (4d) end to end. jvre gives you
     * the pixels (renderer.readPixels); the file format is the caller's choice, so
     * the demo encodes the PNG with stb_image_write at the edge.
     */
    private void saveCanvasScreenshot() {
        byte[] rgba = renderer.readPixels(canvasTarget);
        int w = canvasTarget.width();
        int h = canvasTarget.height();
        java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(rgba.length);
        buf.put(rgba).flip();
        String path = "jvre-canvas.png";
        boolean ok = org.lwjgl.stb.STBImageWrite.stbi_write_png(path, w, h, 4, buf, w * 4);
        org.lwjgl.system.MemoryUtil.memFree(buf);
        System.out.println(ok
                ? "Saved canvas screenshot (" + w + "x" + h + "): " + new java.io.File(path).getAbsolutePath()
                : "Screenshot failed (stb_image_write).");
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
        // Caller-owned custom font (close before the renderer, like the images).
        if (customFont != null) {
            customFont.close();
        }
        // Caller-owned offscreen targets (free their own images; before the
        // renderer). The canvas's Renderer2D is renderer-owned -- only the target
        // is ours to close.
        if (cubeTarget != null) {
            cubeTarget.close();
        }
        if (canvasTarget != null) {
            canvasTarget.close();
        }
        // Caller-owned custom pipeline + its geometry (close before the renderer).
        if (cubeTexture != null) {
            cubeTexture.close();
        }
        if (cubeIndices != null) {
            cubeIndices.close();
        }
        if (cubeVerts != null) {
            cubeVerts.close();
        }
        if (cubePipeline != null) {
            cubePipeline.close();
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
