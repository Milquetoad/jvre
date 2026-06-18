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
import jvre.core.VertexLayout;

import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Multi-input texture CHANNELS / effect CHAINS, headless. Renders two patterns into
 * two offscreen targets (A = red x-gradient, B = green y-gradient), then a third
 * pass samples BOTH at once (channel 0 = A, channel 1 = B) and adds them -- a
 * post-processing pass with two inputs. Writes the blended result to a PNG.
 *
 * The point: {@code PipelineSpec.texture()} is additive (each call = the next
 * {@code sampler2D} channel at bindings 1, 2, ...), and a pass can sample several
 * targets -- the foundation of effect chains. Run: {@code gradlew runChannels}.
 */
public final class ChannelsDemo {

    private static final int S = 256;
    private static final float[] TRI = { -1, -1, 0, 0,  3, -1, 2, 0,  -1, 3, 0, 2 };
    private static final String VERT = """
            #version 450
            layout(location=0) in vec2 p; layout(location=1) in vec2 uv;
            layout(location=0) out vec2 vUv;
            void main(){ vUv = uv; gl_Position = vec4(p, 0, 1); }""";
    private static final String RED_X = """
            #version 450
            layout(location=0) in vec2 vUv; layout(location=0) out vec4 o;
            void main(){ o = vec4(vUv.x, 0, 0, 1); }""";       // red gradient L->R
    private static final String GREEN_Y = """
            #version 450
            layout(location=0) in vec2 vUv; layout(location=0) out vec4 o;
            void main(){ o = vec4(0, vUv.y, 0, 1); }""";       // green gradient top->bottom
    private static final String BLEND = """
            #version 450
            layout(location=0) in vec2 vUv;
            layout(set=0, binding=1) uniform sampler2D ch0;    // target A
            layout(set=0, binding=2) uniform sampler2D ch1;    // target B
            layout(location=0) out vec4 o;
            void main(){ o = texture(ch0, vUv) + texture(ch1, vUv); }""";

    public static void main(String[] args) {
        Configuration.STACK_SIZE.set(512);
        Diagnostics.init("jvre channels demo");
        Instance instance = new Instance("jvre channels", true, true);   // headless
        Renderer r = new Renderer(instance, RendererOptions.builder().build());

        RenderTarget a = r.createRenderTarget(S, S);
        RenderTarget b = r.createRenderTarget(S, S);
        RenderTarget c = r.createRenderTarget(S, S);
        Buffer tri = r.createVertexBuffer(TRI);
        VertexLayout layout = VertexLayout.builder(4 * Float.BYTES)
                .attribute(0, AttribFormat.VEC2, 0).attribute(1, AttribFormat.VEC2, 8).build();
        byte[] vs = ShaderCompiler.compileVertex(VERT, "v");

        Pipeline pa = r.createPipeline(spec(vs, RED_X, layout, 0, "A"), a);
        Pipeline pb = r.createPipeline(spec(vs, GREEN_Y, layout, 0, "B"), b);
        // TWO texture channels (two .texture() calls -> bindings 1 and 2).
        Pipeline blend = r.createPipeline(spec(vs, BLEND, layout, 2, "blend"), c);

        r.drawToTarget(a, f -> { f.bind(pa); f.bindVertexBuffer(tri); f.draw(3); });
        r.drawToTarget(b, f -> { f.bind(pb); f.bindVertexBuffer(tri); f.draw(3); });
        r.drawToTarget(c, f -> {
            f.bind(blend);
            f.texture(0, a.texture());   // channel 0 = A
            f.texture(1, b.texture());   // channel 1 = B
            f.bindVertexBuffer(tri);
            f.draw(3);
        });
        r.render();

        byte[] px = r.readPixels(c);
        int mid = (S / 2 * S + S / 2) * 4;   // centre pixel: ~(0.5 red, 0.5 green)
        System.out.println("centre = R" + (px[mid] & 0xFF) + " G" + (px[mid + 1] & 0xFF)
                + " B" + (px[mid + 2] & 0xFF) + " (expect ~R128 G128 B0 -- A+B blended)");
        writePng("jvre-channels.png", px, S, S);

        blend.close(); pb.close(); pa.close(); tri.close();
        c.close(); b.close(); a.close();
        r.close(); instance.close();
    }

    private static PipelineSpec spec(byte[] vs, String frag, VertexLayout layout, int channels, String name) {
        PipelineSpec.Builder sb = PipelineSpec.builder().vertexShader(vs)
                .fragmentShader(ShaderCompiler.compileFragment(frag, name)).vertexLayout(layout)
                .blend(false).label(name);
        for (int i = 0; i < channels; i++) {
            sb.texture(Stage.FRAGMENT);
        }
        return sb.build();
    }

    private static void writePng(String path, byte[] rgba, int w, int h) {
        ByteBuffer buf = MemoryUtil.memAlloc(rgba.length);
        buf.put(rgba).flip();
        STBImageWrite.stbi_write_png(path, w, h, 4, buf, w * 4);
        MemoryUtil.memFree(buf);
        System.out.println("Wrote " + new File(path).getAbsolutePath());
    }
}
