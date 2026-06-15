package jvre.demo;

import jvre.core.Color;
import jvre.core.Diagnostics;
import jvre.core.Input;
import jvre.core.Instance;
import jvre.core.Renderer;
import jvre.core.Renderer2D;
import jvre.core.RendererOptions;
import jvre.core.Surface;
import jvre.core.Window;

import org.lwjgl.system.Configuration;

/**
 * The phase-3b GUI DEMO: a worked example showing {@link Gui} -- jvre's tiny
 * immediate-mode GUI -- driving a live scene. NOT part of the library (it lives
 * in {@code jvre.demo}, excluded from the published jar); run it with
 * {@code gradlew runGuiDemo}.
 *
 * <p>The whole UI is rebuilt every frame from plain method calls (no widget
 * objects, no callbacks): a few buttons and sliders control a ball bouncing in
 * the window. It doubles as a real stress test of the L2 surface -- shapes, text,
 * text measurement, and the input snapshot, all exercised together.
 */
public final class GuiDemo {

    private static final int WIDTH = 900;
    private static final int HEIGHT = 600;

    private Window window;
    private Instance instance;
    private Surface surface;
    private Renderer renderer;
    private Renderer2D g;
    private Gui gui;

    // The scene the GUI controls: a ball bouncing in the window.
    private float ballX = 300, ballY = 300;
    private float velX = 220, velY = 170;   // px/sec
    private float radius = 40;               // slider-controlled
    private float speed = 1.0f;              // slider-controlled time scale
    private float hue = 200;                 // slider-controlled colour
    private boolean paused = false;          // button-toggled
    private int bounces = 0;                 // counter, button-resettable

    public static void main(String[] args) {
        Configuration.STACK_SIZE.set(512);
        Diagnostics.init("jvre GUI demo");
        try {
            new GuiDemo().run();
        } catch (Throwable t) {
            Diagnostics.reportFault(t);
            System.exit(1);
        }
    }

    public void run() {
        init();
        loop();
        cleanup();
    }

    private void init() {
        window = new Window(WIDTH, HEIGHT, "jvre - immediate-mode GUI demo");
        instance = new Instance("jvre GUI demo", true);
        surface = new Surface(instance, window);
        renderer = new Renderer(instance, surface, window, RendererOptions.builder()
                .clearColor(0.10f, 0.11f, 0.14f)
                .vsync(true)
                .msaa(4)
                .build());
        g = renderer.renderer2D();
        renderer.font();   // bake the default font up front (avoid a frame-1 hitch)
        gui = new Gui(g, window.input());
    }

    private void loop() {
        System.out.println("GUI demo running. Close the window to exit.");
        while (!window.shouldClose()) {
            window.pollEvents();
            // Clamp dt: on Windows the resize/move modal loop blocks pollEvents, so
            // no frames render during a drag and dt() then reports the whole frozen
            // span at once -- without a cap the ball would teleport one giant step.
            // A max step (here ~3 frames at 60Hz) keeps any stall from detonating
            // a time-based sim -- the standard guard.
            update(Math.min(renderer.dt(), 0.05f));
            draw();
            renderer.drawFrame();
        }
        renderer.waitIdle();
    }

    /** Advance the bouncing ball (unless paused), scaled by the speed slider. */
    private void update(float dt) {
        if (paused) {
            return;
        }
        float step = dt * speed;
        ballX += velX * step;
        ballY += velY * step;
        // Reflect off the walls; count each bounce.
        if (ballX - radius < 0)        { ballX = radius;            velX = Math.abs(velX); bounces++; }
        if (ballX + radius > g.width()) { ballX = g.width() - radius; velX = -Math.abs(velX); bounces++; }
        if (ballY - radius < 0)        { ballY = radius;            velY = Math.abs(velY); bounces++; }
        if (ballY + radius > g.height()){ ballY = g.height() - radius; velY = -Math.abs(velY); bounces++; }
    }

    private void draw() {
        g.begin();

        // The scene: the ball, coloured by the hue slider.
        g.fillCircle(ballX, ballY, radius, fromHue(hue));

        // The UI panel. Each call both DRAWS the widget and reports interaction;
        // we act on the result immediately (immediate mode).
        gui.begin(20, 20);
        gui.label("jvre immediate-mode GUI");
        gui.label("Bounces: " + bounces);
        if (gui.button(paused ? "Resume" : "Pause")) {
            paused = !paused;
        }
        if (gui.button("Reset bounces")) {
            bounces = 0;
        }
        radius = gui.slider("Radius", radius, 10, 120);
        speed = gui.slider("Speed", speed, 0, 3);
        hue = gui.slider("Hue", hue, 0, 360);
        gui.end();

        g.end();
    }

    /** A fully-saturated colour from a hue angle in [0, 360) -- so the Hue slider
     *  sweeps the rainbow (keeps the demo self-contained; no Color HSV needed). */
    private static Color fromHue(float h) {
        float c = 1f, x = c * (1f - Math.abs((h / 60f) % 2f - 1f));
        float r, g, b;
        if      (h <  60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else              { r = c; g = 0; b = x; }
        return Color.rgb((int) (r * 255), (int) (g * 255), (int) (b * 255));
    }

    private void cleanup() {
        renderer.close();
        surface.close();
        instance.close();
        window.close();
    }
}
