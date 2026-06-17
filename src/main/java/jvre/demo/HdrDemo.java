package jvre.demo;

import jvre.core.AttribFormat;
import jvre.core.Buffer;
import jvre.core.Diagnostics;
import jvre.core.Instance;
import jvre.core.Pipeline;
import jvre.core.PipelineSpec;
import jvre.core.RenderTarget;
import jvre.core.Renderer;
import jvre.core.RendererOptions;
import jvre.core.ShaderCompiler;
import jvre.core.Stage;
import jvre.core.Surface;
import jvre.core.TargetFormat;
import jvre.core.VertexLayout;
import jvre.core.Window;

import org.lwjgl.system.Configuration;

/**
 * Float/HDR render targets, demonstrated as a POST-PROCESSING chain:
 * <ol>
 *   <li>render a high-dynamic-range gradient (values well above 1) into an
 *       {@link TargetFormat#HDR} float target, then</li>
 *   <li>tone-map it to the LDR screen -- SPLIT down the middle: the LEFT half
 *       naively clamps to [0,1] (so bright regions blow out to flat white, like an
 *       8-bit buffer would), the RIGHT half applies Reinhard tone-mapping (so the
 *       same bright regions keep their detail).</li>
 * </ol>
 * The visible difference between the halves is the proof: the float target
 * preserved values &gt; 1 that an 8-bit target would have clipped at storage time.
 *
 * <p>Lives in {@code jvre.demo} (excluded from the published jar). Run: {@code
 * gradlew runHdr}.
 */
public final class HdrDemo {

    private static final int WIDTH = 900;
    private static final int HEIGHT = 600;

    // A fullscreen triangle (oversized; clip space) carrying UVs in [0,1] across the
    // visible area: [x y | u v]. One buffer drives both passes.
    private static final float[] FULLSCREEN_TRI = {
            -1f, -1f,  0f, 0f,
             3f, -1f,  2f, 0f,
            -1f,  3f,  0f, 2f,
    };

    private static final String VERT = """
            #version 450
            layout(location = 0) in vec2 inPos;
            layout(location = 1) in vec2 inUv;
            layout(location = 0) out vec2 vUv;
            void main() { vUv = inUv; gl_Position = vec4(inPos, 0.0, 1.0); }
            """;

    // The HDR source: a warm radial glow whose centre reaches ~8.0 (far above 1),
    // plus a left->right ramp. Written into the FLOAT target, so nothing clips here.
    private static final String HDR_FRAG = """
            #version 450
            layout(location = 0) in vec2 vUv;
            layout(location = 0) out vec4 o;
            void main() {
                float d = distance(vUv, vec2(0.5));
                float hdr = 8.0 * exp(-d * d * 7.0) + vUv.x * 1.4;   // peak ~8, ramp to ~1.4
                o = vec4(vec3(hdr) * vec3(1.0, 0.82, 0.55), 1.0);    // warm tint
            }
            """;

    // The tone-map pass: sample the HDR target, and split the screen -- naive clamp
    // (left, clips) vs Reinhard (right, keeps highlight detail).
    private static final String TONEMAP_FRAG = """
            #version 450
            layout(location = 0) in vec2 vUv;
            layout(set = 0, binding = 1) uniform sampler2D hdrTex;
            layout(location = 0) out vec4 o;
            void main() {
                vec3 hdr = texture(hdrTex, vUv).rgb;
                vec3 mapped = (vUv.x < 0.5)
                        ? min(hdr, vec3(1.0))           // clamp: bright areas blow out (LDR look)
                        : hdr / (hdr + vec3(1.0));        // Reinhard: detail preserved
                if (abs(vUv.x - 0.5) < 0.0015) mapped = vec3(0.1);   // a thin divider
                o = vec4(mapped, 1.0);
            }
            """;

    public static void main(String[] args) {
        Configuration.STACK_SIZE.set(512);
        Diagnostics.init("jvre HDR demo");

        Window window = new Window(WIDTH, HEIGHT, "jvre - HDR (clamp | Reinhard)");
        Instance instance = new Instance("jvre HDR", true);
        Surface surface = new Surface(instance, window);
        Renderer renderer = new Renderer(instance, surface, window, RendererOptions.builder().build());

        // The float intermediate (HDR), and the shared fullscreen-triangle geometry.
        RenderTarget hdr = renderer.createRenderTarget(WIDTH, HEIGHT, TargetFormat.HDR);
        Buffer tri = renderer.createVertexBuffer(FULLSCREEN_TRI);
        VertexLayout layout = VertexLayout.builder(4 * Float.BYTES)
                .attribute(0, AttribFormat.VEC2, 0)
                .attribute(1, AttribFormat.VEC2, 2 * Float.BYTES)
                .build();

        byte[] vs = ShaderCompiler.compileVertex(VERT, "fs.vert");
        // The HDR pass is baked FOR THE FLOAT TARGET (createPipeline(spec, target)) --
        // its colour format differs from the screen's.
        Pipeline hdrPipe = renderer.createPipeline(PipelineSpec.builder()
                .vertexShader(vs).fragmentShader(ShaderCompiler.compileFragment(HDR_FRAG, "hdr.frag"))
                .vertexLayout(layout).blend(false).label("hdr-source").build(), hdr);
        // The tone-map pass is baked for the SCREEN, and samples the HDR target.
        Pipeline tonemapPipe = renderer.createPipeline(PipelineSpec.builder()
                .vertexShader(vs).fragmentShader(ShaderCompiler.compileFragment(TONEMAP_FRAG, "tonemap.frag"))
                .vertexLayout(layout).blend(false).texture(Stage.FRAGMENT).label("tonemap").build());

        // Each frame: render HDR into the float target, then tone-map it to the screen.
        renderer.setSceneRenderer(frame -> {
            frame.bind(tonemapPipe);
            frame.texture(hdr.texture());
            frame.bindVertexBuffer(tri);
            frame.draw(3);
        });

        System.out.println("HDR demo: LEFT half = clamp (clips), RIGHT half = Reinhard tone-map.");
        while (!window.shouldClose()) {
            window.pollEvents();
            renderer.drawToTarget(hdr, frame -> {
                frame.bind(hdrPipe);
                frame.bindVertexBuffer(tri);
                frame.draw(3);
            });
            renderer.drawFrame();
        }
        renderer.waitIdle();

        tonemapPipe.close();
        hdrPipe.close();
        tri.close();
        hdr.close();
        renderer.close();
        surface.close();
        instance.close();
        window.close();
        System.out.println("HDR demo done.");
    }
}
