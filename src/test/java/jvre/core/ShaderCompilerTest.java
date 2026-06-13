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
