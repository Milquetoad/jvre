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
        assertEquals(0, effect.channelCount(), "a no-input effect has 0 channels");
    }

    @Test
    void acceptsEffectInputChannels() {
        // sampler2D at set 0, binding 0..3 = iChannel0..3 -- the allowed inputs.
        // The shader uses iChannel0 and iChannel2 (a gap at 1); channelCount is
        // maxBinding + 1 = 3, so the renderer default-binds the unused slot.
        String channels = """
                #version 450
                layout(location = 0) out vec4 outColor;
                layout(set = 0, binding = 0) uniform sampler2D iChannel0;
                layout(set = 0, binding = 2) uniform sampler2D iChannel2;
                void main() {
                    vec2 uv = gl_FragCoord.xy * 0.001;
                    outColor = texture(iChannel0, uv) + texture(iChannel2, uv);
                }
                """;
        ShaderEffect e = ShaderEffect.fromFragmentSource(channels, "channels.frag");
        assertEquals(3, e.channelCount(), "maxBinding(2) + 1");
    }

    @Test
    void rejectsASamplerOutOfChannelRange() {
        // A sampler beyond iChannel3 (binding 4) is not a valid input channel.
        String tooHigh = """
                #version 450
                layout(location = 0) out vec4 outColor;
                layout(set = 0, binding = 4) uniform sampler2D iChannel4;
                void main() { outColor = texture(iChannel4, gl_FragCoord.xy); }
                """;
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ShaderEffect.fromFragmentSource(tooHigh, "toohigh.frag"));
        assertTrue(e.getMessage().contains("iChannel"), e.getMessage());
    }

    @Test
    void rejectsAUniformBuffer() {
        // A UBO is still forbidden -- the effect pipeline has no UBO binding (its
        // scalar inputs ride the push block). Only sampler channels are allowed.
        String ubo = """
                #version 450
                layout(location = 0) out vec4 outColor;
                layout(set = 0, binding = 0) uniform U { vec4 color; } u;
                void main() { outColor = u.color; }
                """;
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ShaderEffect.fromFragmentSource(ubo, "ubo.frag"));
        assertTrue(e.getMessage().contains("uniform buffer"), e.getMessage());
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
