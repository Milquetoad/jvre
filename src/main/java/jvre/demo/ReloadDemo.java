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
import jvre.core.VertexLayout;

import org.lwjgl.system.Configuration;

/**
 * Shader HOT-REBUILD, headless (Roadmap Batch 4). Renders a solid colour into a
 * target through a custom pipeline, reads it back, then {@link
 * Pipeline#reloadShaders} swaps in a DIFFERENT fragment shader IN PLACE -- same
 * pipeline object -- and renders again. The two readbacks must differ: proof the
 * live-reload hook re-bakes the GPU pipeline without recreating anything around it.
 *
 * The point: this is the mechanism behind shader live-reload (re-compile on file
 * save, see it instantly). Run: {@code gradlew runReload}.
 */
public final class ReloadDemo {

    private static final int S = 64;
    private static final float[] TRI = { -1, -1, 0, 0,  3, -1, 2, 0,  -1, 3, 0, 2 };
    private static final String VERT = """
            #version 450
            layout(location=0) in vec2 p; layout(location=1) in vec2 uv;
            void main(){ gl_Position = vec4(p, 0, 1); }""";
    private static final String RED  = """
            #version 450
            layout(location=0) out vec4 o;
            void main(){ o = vec4(1, 0, 0, 1); }""";
    private static final String BLUE = """
            #version 450
            layout(location=0) out vec4 o;
            void main(){ o = vec4(0, 0, 1, 1); }""";

    public static void main(String[] args) {
        Configuration.STACK_SIZE.set(512);
        Diagnostics.init("jvre reload demo");
        Instance instance = new Instance("jvre reload", true, true);   // headless
        Renderer r = new Renderer(instance, RendererOptions.builder().build());

        RenderTarget target = r.createRenderTarget(S, S);
        Buffer tri = r.createVertexBuffer(TRI);
        VertexLayout layout = VertexLayout.builder(4 * Float.BYTES)
                .attribute(0, AttribFormat.VEC2, 0).attribute(1, AttribFormat.VEC2, 8).build();
        byte[] vs = ShaderCompiler.compileVertex(VERT, "reload.vert");

        Pipeline p = r.createPipeline(PipelineSpec.builder()
                .vertexShader(vs).fragmentShader(ShaderCompiler.compileFragment(RED, "red"))
                .vertexLayout(layout).blend(false).label("reload").build());

        // Pass 1: the original (red) shader.
        int red = renderAndSampleCentre(r, target, p, tri);

        // Hot-reload the FRAGMENT shader to blue -- SAME pipeline object, in place.
        p.reloadShaders(VERT, BLUE);

        // Pass 2: the reloaded (blue) shader, through the very same pipeline.
        int blue = renderAndSampleCentre(r, target, p, tri);

        System.out.printf("before reload: centre = 0x%08X (expect red ~0xFF0000FF)%n", red);
        System.out.printf("after  reload: centre = 0x%08X (expect blue ~0x0000FFFF)%n", blue);
        boolean changed = (red & 0x00FFFFFF) != (blue & 0x00FFFFFF);
        System.out.println(changed
                ? "OK -- the in-place shader reload changed the rendered output."
                : "FAIL -- output did not change after reloadShaders.");

        p.close(); tri.close(); target.close();
        r.close(); instance.close();
    }

    /** Draw the fullscreen triangle into the target, read it back, return the
     *  centre pixel packed as 0xRRGGBBAA. */
    private static int renderAndSampleCentre(Renderer r, RenderTarget target, Pipeline p, Buffer tri) {
        r.drawToTarget(target, f -> { f.bind(p); f.bindVertexBuffer(tri); f.draw(3); });
        r.render();
        byte[] px = r.readPixels(target);
        int mid = (S / 2 * S + S / 2) * 4;
        return ((px[mid] & 0xFF) << 24) | ((px[mid + 1] & 0xFF) << 16)
                | ((px[mid + 2] & 0xFF) << 8) | (px[mid + 3] & 0xFF);
    }
}
