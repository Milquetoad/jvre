package jvre.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * jvre's first unit tests -- possible because ShaderCompiler is a pure
 * function (GLSL in, SPIR-V out) needing no GPU, no window, no Vulkan device.
 * Also doubles as the proof that the lwjgl-shaderc NATIVES load on this
 * machine before anything is wired against them.
 */
class ShaderCompilerTest {

    private static final String TRIVIAL_FRAG = """
            #version 450
            layout(location = 0) out vec4 outColor;
            void main() {
                outColor = vec4(1.0, 0.0, 1.0, 1.0);
            }
            """;

    @Test
    void compilesValidFragmentShader() {
        byte[] spirv = ShaderCompiler.compileFragment(TRIVIAL_FRAG, "trivial.frag");

        assertTrue(spirv.length > 0, "SPIR-V output should not be empty");
        assertEquals(0, spirv.length % 4, "SPIR-V is a stream of 32-bit words");
        // Every SPIR-V module opens with the magic number 0x07230203
        // (little-endian on disk: 03 02 23 07).
        assertEquals(0x03, spirv[0] & 0xFF);
        assertEquals(0x02, spirv[1] & 0xFF);
        assertEquals(0x23, spirv[2] & 0xFF);
        assertEquals(0x07, spirv[3] & 0xFF);
    }

    @Test
    void forcesNoContractionOnArithmetic() {
        // A shader with multiply-add + a dot product -- exactly the ops a driver
        // would fuse into an FMA, the contraction that breaks procedural noise.
        String arith = """
                #version 450
                layout(location = 0) out vec4 outColor;
                void main() {
                    vec2 p = gl_FragCoord.xy;
                    float n = fract(dot(p, vec2(12.9898, 78.233)) * 43758.5453 + p.x * p.y);
                    outColor = vec4(n, n, n, 1.0);
                }
                """;
        byte[] spirv = ShaderCompiler.compileFragment(arith, "noise.frag");

        // The compile path must have decorated the arithmetic NoContraction.
        int decorations = SpirvNoContraction.count(spirv);
        assertTrue(decorations > 0,
                "arithmetic ops should be decorated NoContraction, found " + decorations);

        // The pass is idempotent: re-running it adds nothing (ids already decorated).
        byte[] again = SpirvNoContraction.decorate(spirv);
        assertArrayEquals(spirv, again, "decorate must be idempotent");
        assertEquals(decorations, SpirvNoContraction.count(again));
    }

    @Test
    void noContractionPassLeavesNonSpirvUntouched() {
        // Conservative: anything that isn't a well-formed module is returned as-is.
        byte[] notSpirv = { 1, 2, 3, 4, 5, 6, 7, 8 };
        assertArrayEquals(notSpirv, SpirvNoContraction.decorate(notSpirv));
        assertEquals(0, SpirvNoContraction.count(notSpirv));
    }

    @Test
    void brokenShaderThrowsWithUsefulMessage() {
        String broken = """
                #version 450
                layout(location = 0) out vec4 outColor;
                void main() {
                    outColor = vec4(1.0  // missing paren + semicolon
                }
                """;

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ShaderCompiler.compileFragment(broken, "broken.frag"));
        // The message must carry shaderc's error log: the file label and a line
        // number -- the user's debugging lifeline (and the reason jvre does not
        // inject preambles that would shift line numbers).
        assertTrue(e.getMessage().contains("broken.frag"),
                "error should name the shader: " + e.getMessage());
    }

    @Test
    void brokenShaderYieldsStructuredDiagnostics() {
        // The syntax error sits on line 5 (the stray '}' that closes a still-open
        // call) -- a real shaderc/glslang report we parse, not a message we invent.
        String broken = """
                #version 450
                layout(location = 0) out vec4 outColor;
                void main() {
                    outColor = vec4(1.0
                }
                """;

        ShaderCompileException e = assertThrows(ShaderCompileException.class,
                () -> ShaderCompiler.compileFragment(broken, "broken.frag"));

        assertEquals("broken.frag", e.shaderName());
        assertFalse(e.diagnostics().isEmpty(), "a failed compile must carry diagnostics");
        assertFalse(e.errors().isEmpty(), "the failure must surface at least one ERROR");

        // The structure is the whole point: a real, parsed line number (not -1) and
        // an ERROR severity -- proof the regex matched this machine's shaderc format.
        ShaderDiagnostic err = e.errors().get(0);
        assertEquals(ShaderDiagnostic.Severity.ERROR, err.severity());
        assertTrue(err.line() > 0, "line should be parsed, was " + err.line()
                + " (raw: " + err.raw() + ")");
        assertFalse(err.message().isBlank(), "message text should survive parsing");
    }

    @Test
    void unparsableLogStillProducesADiagnostic() {
        // Defensive: even if shaderc ever changed its message shape, a failed
        // compile must never hand back an empty diagnostics list.
        String broken = "this is not glsl at all";
        ShaderCompileException e = assertThrows(ShaderCompileException.class,
                () -> ShaderCompiler.compileFragment(broken, "garbage.frag"));
        assertFalse(e.diagnostics().isEmpty());
        assertFalse(e.rawLog().isBlank(), "the raw log is always preserved");
    }

    @Test
    void compilesValidVertexShader() {
        // The fullscreen-triangle trick, as a vertex shader -- the exact shader
        // ShaderEffect's pipeline will use (3 verts from gl_VertexIndex, no
        // vertex buffer). Compiling it here is a preview of beat 2.
        String fullscreen = """
                #version 450
                void main() {
                    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
                }
                """;

        byte[] spirv = ShaderCompiler.compileVertex(fullscreen, "fullscreen.vert");
        assertTrue(spirv.length > 0);
    }
}
