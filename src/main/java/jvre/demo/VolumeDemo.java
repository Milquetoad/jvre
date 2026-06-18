package jvre.demo;

import jvre.core.AttribFormat;
import jvre.core.Buffer;
import jvre.core.Diagnostics;
import jvre.core.Filter;
import jvre.core.Instance;
import jvre.core.Pipeline;
import jvre.core.PipelineSpec;
import jvre.core.RenderTarget;
import jvre.core.Renderer;
import jvre.core.RendererOptions;
import jvre.core.ShaderCompiler;
import jvre.core.Stage;
import jvre.core.Texture;
import jvre.core.TextureOptions;
import jvre.core.VertexLayout;

import org.lwjgl.system.Configuration;

/**
 * 3D volume texture channels ({@code sampler3D}), verified HEADLESS.
 *
 * <p>Builds a 4-deep volume whose z-slices are distinct primary colours, binds it as
 * a pipeline texture CHANNEL, and runs a pass that samples it with a 3D coordinate
 * {@code (u,v,w)} -- the defining feature of a 3D texture (a third sampling axis).
 * The screen is split: the left half samples {@code w} at the centre of slice 0, the
 * right half at the centre of slice 3. Reading the halves back proves the third axis
 * selects the right slice AND that a {@code sampler3D} channel binds correctly.
 *
 * <p>NEAREST filtering + pure 0/255 channels make the assertion crisp (no blending
 * between slices, exact sRGB round-trip). Lives in {@code jvre.demo} (excluded from
 * the published jar). Run: {@code gradlew runVolume}.
 */
public final class VolumeDemo {

    private static final int S = 64;    // render-target size (the picture)
    private static final int V = 4;     // volume is V x V x V voxels
    private static final float[] TRI = { -1, -1, 0, 0,  3, -1, 2, 0,  -1, 3, 0, 2 };

    private static final String VERT = """
            #version 450
            layout(location=0) in vec2 p; layout(location=1) in vec2 uv;
            layout(location=0) out vec2 vUv;
            void main(){ vUv = uv; gl_Position = vec4(p, 0, 1); }""";

    // Sample the volume; the third coordinate (w) picks the slice.
    private static final String FRAG = """
            #version 450
            layout(location=0) in vec2 vUv;
            layout(set=0, binding=1) uniform sampler3D vol;
            layout(location=0) out vec4 o;
            void main(){
                float w = (vUv.x < 0.5) ? 0.125 : 0.875;   // centre of slice 0 vs slice 3
                o = texture(vol, vec3(0.5, 0.5, w));
            }""";

    public static void main(String[] args) {
        Configuration.STACK_SIZE.set(512);
        Diagnostics.init("jvre volume demo");
        Instance instance = new Instance("jvre volume", true, true);   // headless
        Renderer r = new Renderer(instance, RendererOptions.builder().build());

        // A V^3 volume; each z-slice a solid primary colour.
        int[][] sliceColors = { {255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 0} };  // R,G,B,yellow
        byte[] voxels = new byte[V * V * V * 4];
        for (int z = 0; z < V; z++) {
            int[] c = sliceColors[z];
            for (int y = 0; y < V; y++) {
                for (int x = 0; x < V; x++) {
                    int i = ((z * V + y) * V + x) * 4;   // slice-major layout
                    voxels[i] = (byte) c[0];
                    voxels[i + 1] = (byte) c[1];
                    voxels[i + 2] = (byte) c[2];
                    voxels[i + 3] = (byte) 255;
                }
            }
        }
        // NEAREST so a slice-centre sample returns exactly that slice's colour.
        Texture vol = r.createVolume(voxels, V, V, V,
                TextureOptions.builder().filter(Filter.NEAREST).build());

        RenderTarget ldr = r.createRenderTarget(S, S);
        Buffer tri = r.createVertexBuffer(TRI);
        VertexLayout layout = VertexLayout.builder(4 * Float.BYTES)
                .attribute(0, AttribFormat.VEC2, 0).attribute(1, AttribFormat.VEC2, 8).build();
        byte[] vs = ShaderCompiler.compileVertex(VERT, "v");

        Pipeline pipe = r.createPipeline(PipelineSpec.builder().vertexShader(vs)
                .fragmentShader(ShaderCompiler.compileFragment(FRAG, "vol"))
                .vertexLayout(layout).blend(false).texture(Stage.FRAGMENT).label("vol").build(), ldr);

        r.drawToTarget(ldr, f -> {
            f.bind(pipe);
            f.texture(0, vol);   // bind the volume as channel 0
            f.bindVertexBuffer(tri);
            f.draw(3);
        });
        r.render();

        byte[] px = r.readPixels(ldr);
        int row = S / 2 * S;
        int left = (row + S / 4) * 4;        // left half -> slice 0 (red)
        int right = (row + 3 * S / 4) * 4;   // right half -> slice 3 (yellow)
        int lR = px[left] & 0xFF, lG = px[left + 1] & 0xFF;
        int rR = px[right] & 0xFF, rG = px[right + 1] & 0xFF;
        System.out.println("left (slice 0) = R" + lR + " G" + lG + " (expect red:    R255 G0)");
        System.out.println("right(slice 3) = R" + rR + " G" + rG + " (expect yellow: R255 G255)");
        boolean pass = lR > 250 && lG < 5 && rR > 250 && rG > 250;
        System.out.println(pass ? "PASS -- sampler3D slice selection + channel binding work"
                : "FAIL -- volume sampling wrong");

        pipe.close(); tri.close(); ldr.close(); vol.close();
        r.close(); instance.close();

        if (!pass) {
            throw new IllegalStateException("volume verification FAILED");
        }
    }
}
