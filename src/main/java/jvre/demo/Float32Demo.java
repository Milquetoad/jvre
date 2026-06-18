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
import jvre.core.TargetFormat;
import jvre.core.VertexLayout;

import org.lwjgl.system.Configuration;

/**
 * 32-bit float render targets ({@link TargetFormat#HDR_FLOAT32}), verified HEADLESS.
 *
 * <p>The test is chosen to be <b>32-bit-specific</b>, not just "a float target
 * works": pass 1 writes the value {@code 100000.0} into a float target. That value
 * is ABOVE the maximum a 16-bit half-float can represent (~65504) -- a 16-bit target
 * would store it as {@code +inf}. Pass 2 samples the target and asks whether the
 * value came back as a FINITE number near 100000. Only a genuine 32-bit float buffer
 * answers yes; a 16-bit one would have overflowed to infinity. So a white centre
 * pixel is proof we really got 32-bit storage.
 *
 * <p>Lives in {@code jvre.demo} (excluded from the published jar). Run: {@code
 * gradlew runFloat32}.
 */
public final class Float32Demo {

    private static final int S = 64;   // tiny: this is a numeric check, not a picture

    // Fullscreen triangle: [x y | u v].
    private static final float[] TRI = { -1, -1, 0, 0,  3, -1, 2, 0,  -1, 3, 0, 2 };

    private static final String VERT = """
            #version 450
            layout(location=0) in vec2 p; layout(location=1) in vec2 uv;
            layout(location=0) out vec2 vUv;
            void main(){ vUv = uv; gl_Position = vec4(p, 0, 1); }""";

    // Write a value beyond 16-bit float's range into the float target.
    private static final String HUGE = """
            #version 450
            layout(location=0) in vec2 vUv; layout(location=0) out vec4 o;
            void main(){ o = vec4(100000.0, 0.0, 0.0, 1.0); }""";

    // Sample it back: white IFF the value survived as a finite number near 100000
    // (a 16-bit target would have overflowed to +inf, so isinf would be true).
    private static final String CHECK = """
            #version 450
            layout(location=0) in vec2 vUv;
            layout(set=0, binding=1) uniform sampler2D src;
            layout(location=0) out vec4 o;
            void main(){
                float r = texture(src, vUv).r;
                bool ok = (r > 50000.0) && !isinf(r);   // finite AND huge -> genuine 32-bit
                o = vec4(vec3(ok ? 1.0 : 0.0), 1.0);
            }""";

    public static void main(String[] args) {
        Configuration.STACK_SIZE.set(512);
        Diagnostics.init("jvre float32 demo");
        Instance instance = new Instance("jvre float32", true, true);   // headless
        Renderer r = new Renderer(instance, RendererOptions.builder().build());

        // The 32-bit float target under test, and an LDR target for the readable result.
        RenderTarget f32 = r.createRenderTarget(S, S, TargetFormat.HDR_FLOAT32);
        RenderTarget ldr = r.createRenderTarget(S, S);
        Buffer tri = r.createVertexBuffer(TRI);
        VertexLayout layout = VertexLayout.builder(4 * Float.BYTES)
                .attribute(0, AttribFormat.VEC2, 0).attribute(1, AttribFormat.VEC2, 8).build();
        byte[] vs = ShaderCompiler.compileVertex(VERT, "v");

        // Pass 1 is baked FOR the float target (its colour format differs from the LDR one).
        Pipeline write = r.createPipeline(PipelineSpec.builder().vertexShader(vs)
                .fragmentShader(ShaderCompiler.compileFragment(HUGE, "huge"))
                .vertexLayout(layout).blend(false).label("write-f32").build(), f32);
        // Pass 2 is baked for the LDR target and samples the float target as channel 0.
        Pipeline check = r.createPipeline(PipelineSpec.builder().vertexShader(vs)
                .fragmentShader(ShaderCompiler.compileFragment(CHECK, "check"))
                .vertexLayout(layout).blend(false).texture(Stage.FRAGMENT).label("check").build(), ldr);

        r.drawToTarget(f32, f -> { f.bind(write); f.bindVertexBuffer(tri); f.draw(3); });
        r.drawToTarget(ldr, f -> {
            f.bind(check);
            f.texture(0, f32.texture());
            f.bindVertexBuffer(tri);
            f.draw(3);
        });
        r.render();

        byte[] px = r.readPixels(ldr);
        int mid = (S / 2 * S + S / 2) * 4;
        int red = px[mid] & 0xFF;
        boolean pass = red > 250;   // white = the 100000 came back finite (true 32-bit)
        System.out.println("float32 centre R=" + red
                + (pass ? " -> PASS (value > 16f range survived as finite -> genuine 32-bit)"
                        : " -> FAIL (value did not survive as a finite 32-bit float)"));

        check.close(); write.close(); tri.close();
        ldr.close(); f32.close();
        r.close(); instance.close();

        if (!pass) {
            throw new IllegalStateException("float32 target verification FAILED (centre R=" + red + ")");
        }
    }
}
