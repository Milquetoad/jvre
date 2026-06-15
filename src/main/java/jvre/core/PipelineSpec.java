package jvre.core;

/**
 * A user's description of a graphics pipeline -- the L1 escape hatch's pipeline
 * half. The user supplies shaders (SPIR-V; compile GLSL with {@link
 * ShaderCompiler} first), a {@link VertexLayout}, and a few fixed-function
 * choices; {@link Renderer#createPipeline} wires in the swapchain formats +
 * sample count it owns and bakes a real pipeline.
 *
 * v1 (beat 1): no descriptors / push constants -- bound resources (UBOs,
 * textures) arrive in the next beat (what the cube needs). Defaults suit a simple
 * overlay: no depth test, no cull, alpha blend on.
 *
 * <pre>{@code
 * Pipeline tri = renderer.createPipeline(PipelineSpec.builder()
 *     .vertexShader(vs).fragmentShader(fs)
 *     .vertexLayout(layout)
 *     .build());
 * }</pre>
 */
public final class PipelineSpec {

    final byte[] vertexSpirv;
    final byte[] fragmentSpirv;
    final VertexLayout vertexLayout;
    final Cull cull;
    final boolean depthTest;
    final boolean depthWrite;
    final boolean blend;
    final String label;

    private PipelineSpec(Builder b) {
        this.vertexSpirv = b.vertexSpirv;
        this.fragmentSpirv = b.fragmentSpirv;
        this.vertexLayout = b.vertexLayout;
        this.cull = b.cull;
        this.depthTest = b.depthTest;
        this.depthWrite = b.depthWrite;
        this.blend = b.blend;
        this.label = b.label;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private byte[] vertexSpirv;
        private byte[] fragmentSpirv;
        private VertexLayout vertexLayout;
        private Cull cull = Cull.NONE;
        private boolean depthTest = false;
        private boolean depthWrite = false;
        private boolean blend = true;
        private String label = "custom";

        /** Vertex-shader SPIR-V (e.g. {@code ShaderCompiler.compileVertex(src, name)}). */
        public Builder vertexShader(byte[] spirv) {
            this.vertexSpirv = spirv;
            return this;
        }

        /** Fragment-shader SPIR-V. */
        public Builder fragmentShader(byte[] spirv) {
            this.fragmentSpirv = spirv;
            return this;
        }

        public Builder vertexLayout(VertexLayout layout) {
            this.vertexLayout = layout;
            return this;
        }

        public Builder cull(Cull cull) {
            this.cull = cull;
            return this;
        }

        /** Enable depth testing (occlusion). Needs the depth attachment, which jvre always carries. */
        public Builder depthTest(boolean on) {
            this.depthTest = on;
            return this;
        }

        /** Write depth (usually paired with {@link #depthTest}). */
        public Builder depthWrite(boolean on) {
            this.depthWrite = on;
            return this;
        }

        /** Straight alpha blending (src over dst). On by default. */
        public Builder blend(boolean on) {
            this.blend = on;
            return this;
        }

        /** A label used in error messages / shader-module diagnostics. */
        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public PipelineSpec build() {
            if (vertexSpirv == null || fragmentSpirv == null) {
                throw new IllegalStateException("PipelineSpec needs both a vertex and a fragment shader");
            }
            if (vertexLayout == null) {
                throw new IllegalStateException("PipelineSpec needs a vertexLayout");
            }
            return new PipelineSpec(this);
        }
    }
}
