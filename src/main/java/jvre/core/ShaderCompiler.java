package jvre.core;

import java.nio.ByteBuffer;

import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * Runtime GLSL -> SPIR-V compilation, via shaderc -- the same compiler the
 * {@code glslc} command-line tool wraps, used here as an in-process library.
 *
 * Why both compile paths exist:
 *   - jvre's OWN shaders (the cube demo, the fullscreen-triangle vertex shader)
 *     are fixed at build time -> the compileShaders Gradle task runs glslc and
 *     ships .spv resources. Zero runtime cost, errors break the build.
 *   - USER shaders (ShaderEffect's whole point) arrive at RUNTIME as source.
 *     Requiring users to run glslc would put the Vulkan SDK on every consumer's
 *     machine -- a toolchain tax on the "easy" altitude. shaderc-as-a-library
 *     removes it: compile errors surface as Java exceptions carrying the
 *     shader's own line numbers, and live-reload becomes possible later.
 *
 * Stateless utility: a compiler + options object are created and released per
 * call. (shaderc compilers are cheap to create and NOT thread-safe to share, so
 * per-call is both the simple and the correct default; pooling is an
 * optimization for a shader-heavy future.)
 */
public final class ShaderCompiler {

    private ShaderCompiler() {}  // static utility; no instances

    /** Compile fragment-shader GLSL source. {@code name} labels error messages. */
    public static byte[] compileFragment(String source, String name) {
        return compile(source, name, shaderc_glsl_fragment_shader);
    }

    /** Compile vertex-shader GLSL source. {@code name} labels error messages. */
    public static byte[] compileVertex(String source, String name) {
        return compile(source, name, shaderc_glsl_vertex_shader);
    }

    private static byte[] compile(String source, String name, int kind) {
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) {
            throw new RuntimeException("Failed to initialize the shaderc compiler");
        }
        long options = shaderc_compile_options_initialize();
        long result = 0;
        try {
            // Target the Vulkan flavor of GLSL semantics and our verified core
            // version (same honesty as VMA's vulkanApiVersion).
            shaderc_compile_options_set_target_env(options,
                    shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_3);
            // Optimize for performance -- what glslc does with -O. User shaders
            // are compiled once at load, not per frame; spend the time.
            shaderc_compile_options_set_optimization_level(options,
                    shaderc_optimization_level_performance);

            result = shaderc_compile_into_spv(compiler, source, kind, name, "main", options);

            if (shaderc_result_get_compilation_status(result)
                    != shaderc_compilation_status_success) {
                // The error log carries the user's line numbers -- the reason
                // jvre does NOT inject a preamble around user source (it would
                // shift every line reference in here).
                throw new RuntimeException("Shader compilation failed for " + name + ":\n"
                        + shaderc_result_get_error_message(result));
            }

            // Copy out of shaderc's native buffer before releasing it.
            ByteBuffer spirv = shaderc_result_get_bytes(result);
            byte[] bytes = new byte[spirv.remaining()];
            spirv.get(bytes);
            return bytes;
        } finally {
            if (result != 0) {
                shaderc_result_release(result);
            }
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }
}
