package jvre.demo;

import jvre.core.AttribFormat;
import jvre.core.Buffer;
import jvre.core.Diagnostics;
import jvre.core.DynamicTexture;
import jvre.core.Instance;
import jvre.core.Pipeline;
import jvre.core.PipelineSpec;
import jvre.core.RenderTarget;
import jvre.core.Renderer;
import jvre.core.RendererOptions;
import jvre.core.ShaderCompiler;
import jvre.core.Stage;
import jvre.core.VertexLayout;

import org.lwjgl.system.Configuration;

/**
 * CPU-updatable {@link DynamicTexture} channels, verified HEADLESS.
 *
 * <p>Proves the defining property -- the texture's pixels CHANGE between frames. A
 * 1x1 dynamic texture is bound as a pipeline channel; it is filled RED and rendered
 * into target A, then re-filled GREEN (a second {@code update}) and rendered into
 * target B. Reading both back and seeing A red, B green proves the new pixels really
 * reached the GPU on the second frame -- i.e. the update + per-frame upload works.
 *
 * <p>Lives in {@code jvre.demo} (excluded from the published jar). Run: {@code
 * gradlew runDynamicTexture}.
 */
public final class DynamicTextureDemo {

    private static final int S = 32;
    private static final float[] TRI = { -1, -1, 0, 0,  3, -1, 2, 0,  -1, 3, 0, 2 };

    private static final String VERT = """
            #version 450
            layout(location=0) in vec2 p; layout(location=1) in vec2 uv;
            layout(location=0) out vec2 vUv;
            void main(){ vUv = uv; gl_Position = vec4(p, 0, 1); }""";

    private static final String FRAG = """
            #version 450
            layout(location=0) in vec2 vUv;
            layout(set=0, binding=1) uniform sampler2D src;
            layout(location=0) out vec4 o;
            void main(){ o = texture(src, vUv); }""";

    public static void main(String[] args) {
        Configuration.STACK_SIZE.set(512);
        Diagnostics.init("jvre dynamic-texture demo");
        Instance instance = new Instance("jvre dyntex", true, true);   // headless
        Renderer r = new Renderer(instance, RendererOptions.builder().build());

        DynamicTexture dyn = r.createDynamicTexture(1, 1);   // a single CPU-updatable texel
        RenderTarget a = r.createRenderTarget(S, S);
        RenderTarget b = r.createRenderTarget(S, S);
        Buffer tri = r.createVertexBuffer(TRI);
        VertexLayout layout = VertexLayout.builder(4 * Float.BYTES)
                .attribute(0, AttribFormat.VEC2, 0).attribute(1, AttribFormat.VEC2, 8).build();
        byte[] vs = ShaderCompiler.compileVertex(VERT, "v");
        Pipeline pipe = r.createPipeline(PipelineSpec.builder().vertexShader(vs)
                .fragmentShader(ShaderCompiler.compileFragment(FRAG, "dyn"))
                .vertexLayout(layout).blend(false).texture(Stage.FRAGMENT).label("dyn").build(), a);

        // Frame 1: texel = RED -> target A.
        dyn.update(new byte[] { (byte) 255, 0, 0, (byte) 255 });
        r.drawToTarget(a, f -> { f.bind(pipe); f.texture(0, dyn); f.bindVertexBuffer(tri); f.draw(3); });
        r.render();
        byte[] pxA = r.readPixels(a);

        // Frame 2: texel = GREEN -> target B (same dynamic texture, new pixels).
        dyn.update(new byte[] { 0, (byte) 255, 0, (byte) 255 });
        r.drawToTarget(b, f -> { f.bind(pipe); f.texture(0, dyn); f.bindVertexBuffer(tri); f.draw(3); });
        r.render();
        byte[] pxB = r.readPixels(b);

        int mid = (S / 2 * S + S / 2) * 4;
        int aR = pxA[mid] & 0xFF, aG = pxA[mid + 1] & 0xFF;
        int bR = pxB[mid] & 0xFF, bG = pxB[mid + 1] & 0xFF;
        System.out.println("frame 1 (red)   = R" + aR + " G" + aG + " (expect R255 G0)");
        System.out.println("frame 2 (green) = R" + bR + " G" + bG + " (expect R0 G255)");
        boolean pass = aR > 250 && aG < 5 && bR < 5 && bG > 250;
        System.out.println(pass ? "PASS -- dynamic texture pixels changed between frames"
                : "FAIL -- dynamic update did not take effect");

        pipe.close(); tri.close(); b.close(); a.close(); dyn.close();
        r.close(); instance.close();

        if (!pass) {
            throw new IllegalStateException("dynamic-texture verification FAILED");
        }
    }
}
