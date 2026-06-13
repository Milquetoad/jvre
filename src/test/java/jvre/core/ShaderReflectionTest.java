package jvre.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the Ring 3 effect-contract guard: a user fragment shader that COMPILES
 * but would break the effect pipeline must be rejected at creation, in jvre's
 * own terms. Like {@link ShaderCompilerTest} these need no GPU -- compilation
 * (shaderc) and reflection (SPIRV-Cross) are both CPU-side -- and they double as
 * proof the lwjgl-spvc natives load.
 *
 * Each shader is COMPLETE and valid GLSL: the point is exactly that it compiles
 * and is then caught by reflection, not by the compiler.
 */
class ShaderReflectionTest {

    @Test
    void acceptsAContractFollowingEffect() {
        // The real v1 contract: the 20-byte builtin push block, no resources.
        // (Every member is used so the optimizer keeps the full block.)
        String ok = """
                #version 450
                layout(location = 0) out vec4 outColor;
                layout(push_constant) uniform Push {
                    vec2 uResolution;
                    vec2 uMouse;
                    float uTime;
                } pc;
                void main() {
                    outColor = vec4(pc.uMouse / pc.uResolution, pc.uTime, 1.0);
                }
                """;
        ShaderEffect effect = ShaderEffect.fromFragmentSource(ok, "ok.frag");
        assertEquals("ok.frag", effect.name());
    }

    @Test
    void acceptsAnEffectWithNoPushBlock() {
        // No push block at all is fine -- jvre still has its range; the shader
        // just ignores it.
        String noPush = """
                #version 450
                layout(location = 0) out vec4 outColor;
                void main() {
                    outColor = vec4(gl_FragCoord.xy * 0.001, 0.0, 1.0);
                }
                """;
        assertNotNull(ShaderEffect.fromFragmentSource(noPush, "nopush.frag"));
    }

    @Test
    void rejectsAShaderThatBindsASampler() {
        // Compiles cleanly, but the effect pipeline has no descriptor set layout
        // -- this sampler would detonate at draw. Caught here instead.
        String sampler = """
                #version 450
                layout(location = 0) out vec4 outColor;
                layout(set = 0, binding = 0) uniform sampler2D tex;
                void main() {
                    outColor = texture(tex, gl_FragCoord.xy);
                }
                """;
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ShaderEffect.fromFragmentSource(sampler, "sampler.frag"));
        assertTrue(e.getMessage().contains("sampler.frag"), e.getMessage());
        assertTrue(e.getMessage().contains("sampler"), e.getMessage());
    }

    @Test
    void rejectsAnOversizedPushBlock() {
        // jvre fills only 20 bytes; this block is far larger, so the shader would
        // read past what jvre pushes.
        String big = """
                #version 450
                layout(location = 0) out vec4 outColor;
                layout(push_constant) uniform Push {
                    vec2 uResolution;
                    vec2 uMouse;
                    float uTime;
                    mat4 uExtra;
                } pc;
                void main() {
                    outColor = pc.uExtra * vec4(pc.uResolution, pc.uMouse) + vec4(pc.uTime);
                }
                """;
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ShaderEffect.fromFragmentSource(big, "big.frag"));
        assertTrue(e.getMessage().contains("big.frag"), e.getMessage());
        assertTrue(e.getMessage().contains("push"), e.getMessage());
    }
}
