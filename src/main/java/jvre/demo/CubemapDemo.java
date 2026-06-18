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
import jvre.core.Texture;
import jvre.core.VertexLayout;

import org.lwjgl.system.Configuration;

/**
 * Cubemap texture channels ({@code samplerCube}), verified HEADLESS.
 *
 * <p>Builds a cubemap whose 6 faces are distinct primary colours (in Vulkan's fixed
 * order +X,-X,+Y,-Y,+Z,-Z), binds it as a pipeline texture CHANNEL, and runs a pass
 * whose shader samples it with a 3D DIRECTION vector -- the defining feature of a
 * cube map (the hardware resolves direction -> face + texel). The screen is split:
 * the left half samples direction {@code (0,0,1)} (the +Z face), the right half
 * samples {@code (1,0,0)} (the +X face). Reading the two halves back proves face
 * selection works AND that a {@code samplerCube} channel binds correctly.
 *
 * <p>Pure 0/255 channels are used so the sRGB encode/decode round-trip is exact and
 * the assertion is crisp. Lives in {@code jvre.demo} (excluded from the published
 * jar). Run: {@code gradlew runCubemap}.
 */
public final class CubemapDemo {

    private static final int S = 64;
    private static final float[] TRI = { -1, -1, 0, 0,  3, -1, 2, 0,  -1, 3, 0, 2 };

    private static final String VERT = """
            #version 450
            layout(location=0) in vec2 p; layout(location=1) in vec2 uv;
            layout(location=0) out vec2 vUv;
            void main(){ vUv = uv; gl_Position = vec4(p, 0, 1); }""";

    // Sample the cube along a direction chosen by which half of the screen we're in.
    private static final String FRAG = """
            #version 450
            layout(location=0) in vec2 vUv;
            layout(set=0, binding=1) uniform samplerCube cube;
            layout(location=0) out vec4 o;
            void main(){
                vec3 dir = (vUv.x < 0.5) ? vec3(0, 0, 1)   // +Z face
                                         : vec3(1, 0, 0);  // +X face
                o = texture(cube, dir);
            }""";

    public static void main(String[] args) {
        Configuration.STACK_SIZE.set(512);
        Diagnostics.init("jvre cubemap demo");
        Instance instance = new Instance("jvre cubemap", true, true);   // headless
        Renderer r = new Renderer(instance, RendererOptions.builder().build());

        // 6 faces in Vulkan order: +X,-X,+Y,-Y,+Z,-Z. Pure primaries (exact sRGB).
        byte[][] faces = {
                face(255, 0, 0),     // +X red
                face(0, 255, 255),   // -X cyan
                face(0, 255, 0),     // +Y green
                face(255, 0, 255),   // -Y magenta
                face(0, 0, 255),     // +Z blue
                face(255, 255, 0),   // -Z yellow
        };
        Texture cube = r.createCubemap(faces, S);

        RenderTarget ldr = r.createRenderTarget(S, S);
        Buffer tri = r.createVertexBuffer(TRI);
        VertexLayout layout = VertexLayout.builder(4 * Float.BYTES)
                .attribute(0, AttribFormat.VEC2, 0).attribute(1, AttribFormat.VEC2, 8).build();
        byte[] vs = ShaderCompiler.compileVertex(VERT, "v");

        Pipeline pipe = r.createPipeline(PipelineSpec.builder().vertexShader(vs)
                .fragmentShader(ShaderCompiler.compileFragment(FRAG, "cube"))
                .vertexLayout(layout).blend(false).texture(Stage.FRAGMENT).label("cube").build(), ldr);

        r.drawToTarget(ldr, f -> {
            f.bind(pipe);
            f.texture(0, cube);   // bind the cubemap as channel 0
            f.bindVertexBuffer(tri);
            f.draw(3);
        });
        r.render();

        byte[] px = r.readPixels(ldr);
        int row = S / 2 * S;
        int left = (row + S / 4) * 4;        // left half -> +Z (blue)
        int right = (row + 3 * S / 4) * 4;   // right half -> +X (red)
        int lB = px[left + 2] & 0xFF, lR = px[left] & 0xFF;
        int rR = px[right] & 0xFF, rB = px[right + 2] & 0xFF;
        System.out.println("left (+Z) = R" + lR + " B" + lB + " (expect blue: R0 B255)");
        System.out.println("right(+X) = R" + rR + " B" + rB + " (expect red:  R255 B0)");
        boolean pass = lB > 250 && lR < 5 && rR > 250 && rB < 5;
        System.out.println(pass ? "PASS -- samplerCube face selection + channel binding work"
                : "FAIL -- cubemap sampling wrong");

        pipe.close(); tri.close(); ldr.close(); cube.close();
        r.close(); instance.close();

        if (!pass) {
            throw new IllegalStateException("cubemap verification FAILED");
        }
    }

    /** A solid S x S RGBA face of the given colour. */
    private static byte[] face(int red, int green, int blue) {
        byte[] px = new byte[S * S * 4];
        for (int i = 0; i < S * S; i++) {
            px[i * 4]     = (byte) red;
            px[i * 4 + 1] = (byte) green;
            px[i * 4 + 2] = (byte) blue;
            px[i * 4 + 3] = (byte) 255;
        }
        return px;
    }
}
