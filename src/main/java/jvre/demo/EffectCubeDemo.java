package jvre.demo;

import jvre.core.Diagnostics;
import jvre.core.Instance;
import jvre.core.Renderer;
import jvre.core.RendererOptions;
import jvre.core.ShaderEffect;
import jvre.core.Surface;
import jvre.core.Texture;
import jvre.core.Window;

import org.lwjgl.system.Configuration;

/**
 * A {@link ShaderEffect} sampling a CUBEMAP iChannel -- the effect-altitude proof
 * for Batch 6's cube channels. The fragment shader declares {@code samplerCube
 * iChannel0} and samples it along a view direction built from the pixel position,
 * i.e. a minimal skybox: the 6 coloured cube faces wrap around the screen.
 *
 * <p>Windowed (effects render in the swapchain pass; there is no headless effect
 * seam), but FRAME-CAPPED so it exits on its own -- which lets the validation log be
 * checked automatically. The picture itself is an owner eyeball. Run: {@code gradlew
 * runEffectCube}.
 */
public final class EffectCubeDemo {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final int FACE = 256;
    private static final int FRAMES = 180;   // ~3s at 60fps, then self-exit

    // A samplerCube iChannel, sampled by a direction derived from the pixel -- a
    // minimal skybox. The push block is jvre's standard effect contract.
    private static final String FRAG = """
            #version 450
            layout(location = 0) out vec4 outColor;
            layout(set = 0, binding = 0) uniform samplerCube iChannel0;
            layout(push_constant) uniform Push {
                vec2 uResolution; vec2 uMouse; float uTime;
            } pc;
            void main() {
                vec2 uv = (gl_FragCoord.xy / pc.uResolution) * 2.0 - 1.0;
                uv.x *= pc.uResolution.x / pc.uResolution.y;            // aspect
                // Spin the view over time so all faces sweep past.
                float a = pc.uTime * 0.5;
                vec3 dir = normalize(vec3(uv, 1.2));
                dir = vec3(dir.x * cos(a) - dir.z * sin(a), dir.y,
                           dir.x * sin(a) + dir.z * cos(a));
                outColor = texture(iChannel0, dir);
            }
            """;

    public static void main(String[] args) {
        Configuration.STACK_SIZE.set(512);
        Diagnostics.init("jvre effect-cube demo");

        Window window = new Window(WIDTH, HEIGHT, "jvre - effect samplerCube iChannel");
        Instance instance = new Instance("jvre effect-cube", true);
        Surface surface = new Surface(instance, window);
        Renderer renderer = new Renderer(instance, surface, window, RendererOptions.builder().build());

        ShaderEffect fx = ShaderEffect.fromFragmentSource(FRAG, "cube-sky");
        renderer.setEffect(fx);

        byte[][] faces = {
                face(255, 60, 60),    // +X red
                face(60, 255, 255),   // -X cyan
                face(60, 255, 60),    // +Y green
                face(255, 60, 255),   // -Y magenta
                face(60, 60, 255),    // +Z blue
                face(255, 255, 60),   // -Z yellow
        };
        Texture cube = renderer.createCubemap(faces, FACE);
        renderer.setEffectChannel(0, cube);

        System.out.println("effect-cube demo: a samplerCube iChannel skybox, " + FRAMES + " frames.");
        int frames = 0;
        while (!window.shouldClose() && frames++ < FRAMES) {
            window.pollEvents();
            renderer.drawFrame();
        }
        renderer.waitIdle();

        cube.close();
        renderer.close();
        surface.close();
        instance.close();
        window.close();
        System.out.println("effect-cube demo done (" + frames + " frames).");
    }

    private static byte[] face(int r, int g, int b) {
        byte[] px = new byte[FACE * FACE * 4];
        for (int i = 0; i < FACE * FACE; i++) {
            px[i * 4] = (byte) r;
            px[i * 4 + 1] = (byte) g;
            px[i * 4 + 2] = (byte) b;
            px[i * 4 + 3] = (byte) 255;
        }
        return px;
    }
}
