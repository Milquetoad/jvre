package jvre.demo;

import jvre.core.Diagnostics;
import jvre.core.DynamicTexture;
import jvre.core.Input;
import jvre.core.Instance;
import jvre.core.Key;
import jvre.core.Renderer;
import jvre.core.RendererOptions;
import jvre.core.ShaderEffect;
import jvre.core.Surface;
import jvre.core.Window;

import org.lwjgl.system.Configuration;

/**
 * The Shadertoy "keyboard channel", built APP-SIDE on a {@link DynamicTexture} +
 * {@link Input} -- the worked example for Batch 6d. The engine provides the
 * mechanism (a CPU-updatable texture bound as a channel); THIS demo owns the
 * *policy*: the 256x3 layout (row 0 = key down, row 1 = pressed-this-frame, row 2 =
 * sticky toggle) and the GLFW-key -> JavaScript-keyCode column map. That map is
 * deliberately not in jvre core -- it's a foreign-platform convention, and keeping
 * it here lets a user pick whatever column scheme they want (see the roadmap note).
 *
 * <p>The effect samples the texture as {@code iChannel0}: each frame, the vertical
 * stripe for a held key lights green, and toggled keys keep a blue tint. Windowed +
 * interactive (run unbounded), or pass a frame count to self-exit for a check.
 * Run: {@code gradlew runKeyboard} (or {@code --args="180"}).
 */
public final class KeyboardDemo {

    private static final int WIDTH = 900;
    private static final int HEIGHT = 240;

    // The Shadertoy keyboard texture is 256 wide (one column per JS keyCode) x 3 rows.
    private static final int COLS = 256;
    private static final int ROWS = 3;

    // The APP-SIDE keycode map: a GLFW Key -> its JavaScript keyCode column. Letters
    // and SPACE happen to share GLFW/JS values, but arrows do NOT (GLFW LEFT=263 vs
    // JS 37), which is exactly why the map is explicit and lives here, not in jvre.
    private record Mapped(Key key, int jsCode) {}

    private static final Mapped[] KEYS = buildKeyMap();

    private static final String FRAG = """
            #version 450
            layout(location = 0) out vec4 outColor;
            layout(set = 0, binding = 0) uniform sampler2D iChannel0;   // the keyboard texture
            layout(push_constant) uniform Push {
                vec2 uResolution; vec2 uMouse; float uTime;
            } pc;
            void main() {
                vec2 uv = gl_FragCoord.xy / pc.uResolution;
                float down   = texture(iChannel0, vec2(uv.x, 0.17)).r;   // row 0: held now
                float toggle = texture(iChannel0, vec2(uv.x, 0.83)).r;   // row 2: sticky toggle
                vec3 base = mix(vec3(0.06, 0.06, 0.09), vec3(0.10, 0.10, 0.28), toggle);
                vec3 col  = mix(base, vec3(0.20, 0.95, 0.45), down);
                outColor = vec4(col, 1.0);
            }
            """;

    public static void main(String[] args) {
        int cap = args.length > 0 ? Integer.parseInt(args[0]) : Integer.MAX_VALUE;

        Configuration.STACK_SIZE.set(512);
        Diagnostics.init("jvre keyboard demo");

        Window window = new Window(WIDTH, HEIGHT, "jvre - keyboard channel (press keys)");
        Instance instance = new Instance("jvre keyboard", true);
        Surface surface = new Surface(instance, window);
        Renderer renderer = new Renderer(instance, surface, window, RendererOptions.builder().build());

        renderer.setEffect(ShaderEffect.fromFragmentSource(FRAG, "keyboard"));

        // The dynamic texture that carries keyboard state to the shader, bound once
        // (it resolves to the right per-frame copy at draw).
        DynamicTexture keyboard = renderer.createDynamicTexture(COLS, ROWS);
        renderer.setEffectChannel(0, keyboard);

        boolean[] toggle = new boolean[COLS];   // sticky per-column toggle state
        byte[] px = new byte[COLS * ROWS * 4];

        System.out.println("keyboard demo: press letter keys / space / arrows -> stripes light up.");
        int frames = 0;
        while (!window.shouldClose() && frames++ < cap) {
            window.pollEvents();
            Input in = window.input();

            // Rebuild the 256x3 texture from this frame's input snapshot.
            java.util.Arrays.fill(px, (byte) 0);
            for (Mapped m : KEYS) {
                boolean down = in.keyDown(m.key);
                boolean pressed = in.keyPressed(m.key);
                if (pressed) {
                    toggle[m.jsCode] = !toggle[m.jsCode];
                }
                setCell(px, m.jsCode, 0, down);
                setCell(px, m.jsCode, 1, pressed);
                setCell(px, m.jsCode, 2, toggle[m.jsCode]);
            }
            keyboard.update(px);

            renderer.drawFrame();
        }
        renderer.waitIdle();

        keyboard.close();
        renderer.close();
        surface.close();
        instance.close();
        window.close();
        System.out.println("keyboard demo done (" + frames + " frames).");
    }

    /** Write the red channel of cell (column = keyCode, row) to 255 / 0. */
    private static void setCell(byte[] px, int col, int row, boolean on) {
        int i = (row * COLS + col) * 4;
        px[i] = (byte) (on ? 255 : 0);
        px[i + 3] = (byte) 255;
    }

    /** The curated GLFW-key -> JS-keyCode map this demo uses. */
    private static Mapped[] buildKeyMap() {
        java.util.List<Mapped> list = new java.util.ArrayList<>();
        Key[] letters = {
                Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J,
                Key.K, Key.L, Key.M, Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T,
                Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
        };
        for (int i = 0; i < letters.length; i++) {
            list.add(new Mapped(letters[i], 65 + i));   // JS 'A'=65 .. 'Z'=90
        }
        list.add(new Mapped(Key.SPACE, 32));
        list.add(new Mapped(Key.LEFT, 37));   // JS arrows -- distinct from GLFW values
        list.add(new Mapped(Key.UP, 38));
        list.add(new Mapped(Key.RIGHT, 39));
        list.add(new Mapped(Key.DOWN, 40));
        return list.toArray(new Mapped[0]);
    }
}
