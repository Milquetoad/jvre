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
    final int uniformBufferSize;   // 0 = no UBO; else a UBO at binding 0
    final Stage uniformStage;
    final int pushSize;            // 0 = no push constants
    final Stage pushStage;
    // One combined-image-sampler per declared texture CHANNEL, at bindings 1..N (in
    // order). Empty = no textures. textureStages.get(c) is channel c's shader stage.
    final java.util.List<Stage> textureStages;

    private PipelineSpec(Builder b) {
        this.vertexSpirv = b.vertexSpirv;
        this.fragmentSpirv = b.fragmentSpirv;
        this.vertexLayout = b.vertexLayout;
        this.cull = b.cull;
        this.depthTest = b.depthTest;
        this.depthWrite = b.depthWrite;
        this.blend = b.blend;
        this.label = b.label;
        this.uniformBufferSize = b.uniformBufferSize;
        this.uniformStage = b.uniformStage;
        this.pushSize = b.pushSize;
        this.pushStage = b.pushStage;
        this.textureStages = java.util.List.copyOf(b.textureStages);
    }

    /** Number of texture channels declared (bindings 1..N). */
    int textureCount() {
        return textureStages.size();
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
        private int uniformBufferSize = 0;
        private Stage uniformStage = Stage.VERTEX;
        private int pushSize = 0;
        private Stage pushStage = Stage.FRAGMENT;
        private final java.util.List<Stage> textureStages = new java.util.ArrayList<>();

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

        /** Declare a uniform buffer at binding 0 of {@code sizeBytes}, visible to
         *  {@code stage}. jvre manages a per-frame UBO + descriptor set; fill it
         *  each frame with {@code FrameRenderer.uniform(...)}. (v1: one UBO.) */
        public Builder uniformBuffer(int sizeBytes, Stage stage) {
            this.uniformBufferSize = sizeBytes;
            this.uniformStage = stage;
            return this;
        }

        /** Declare a push-constant range of {@code sizeBytes} visible to {@code
         *  stage}; set it each frame with {@code FrameRenderer.pushConstants(...)}. */
        public Builder pushConstants(int sizeBytes, Stage stage) {
            this.pushSize = sizeBytes;
            this.pushStage = stage;
            return this;
        }

        /**
         * Declare a texture CHANNEL (combined image sampler), visible to {@code
         * stage}. ADDITIVE: each call adds the next channel, at bindings 1, 2, 3, ...
         * in call order -- so one call is a single texture at binding 1 (a shader's
         * {@code layout(binding=1) sampler2D}), two calls are bindings 1 and 2 (e.g.
         * Shadertoy-style {@code iChannel0}/{@code iChannel1}), etc. Supply each
         * channel every frame with {@code FrameRenderer.texture(channel, ...)} (channel
         * 0 is {@code texture(...)}). This is what makes multi-input post-processing /
         * effect CHAINS work -- a pass samples several targets at once.
         */
        public Builder texture(Stage stage) {
            this.textureStages.add(stage);
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
