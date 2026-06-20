package jvre.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            // NO shaderc-side optimization (level zero). Three reasons specific to
            // jvre, and the perf upside is marginal:
            //   1. The effect CONTRACT GUARD + channel-count reflection read THESE
            //      exact bytes. A performance pass runs dead-code elimination, which
            //      can STRIP a declared-but-unused iChannel sampler or shrink the push
            //      block -- desyncing the descriptor layout jvre builds from what the
            //      user actually wrote. Level zero keeps the declared interface intact.
            //   2. Faster live-reload (the Batch-4 edit -> F5 loop) and inspectable,
            //      source-faithful SPIR-V (closer to what a Shadertoy author expects).
            //   3. The GPU DRIVER re-optimizes the SPIR-V at pipeline creation anyway,
            //      so an optimization here is largely redundant on desktop.
            // (An explicit opt-level lever can be added additively later if a consumer
            // genuinely needs the smaller/faster module -- see the roadmap.)
            shaderc_compile_options_set_optimization_level(options,
                    shaderc_optimization_level_zero);

            result = shaderc_compile_into_spv(compiler, source, kind, name, "main", options);

            if (shaderc_result_get_compilation_status(result)
                    != shaderc_compilation_status_success) {
                // The error log carries the user's line numbers -- the reason
                // jvre does NOT inject a preamble around user source (it would
                // shift every line reference in here). Parse it into structured
                // diagnostics so tooling gets (line, severity, message), not a blob.
                String log = shaderc_result_get_error_message(result);
                throw new ShaderCompileException(name, parseLog(log, name), log);
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

    // shaderc/glslang's usual message shape: "name:line: error: message". The name
    // can itself contain colons (a path), so the line number is the FIRST run of
    // digits flanked by colons -- we anchor on ":<digits>: <severity>:".
    private static final Pattern DIAGNOSTIC = Pattern.compile(
            "^(?<name>.*?):(?<line>\\d+):\\s*(?<sev>error|warning):\\s*(?<msg>.*)$",
            Pattern.CASE_INSENSITIVE);

    // The older glslang shape some toolchains emit: "ERROR: name:line: message".
    private static final Pattern DIAGNOSTIC_PREFIXED = Pattern.compile(
            "^(?<sev>error|warning):\\s*(?<name>.*?):(?<line>\\d+):\\s*(?<msg>.*)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Turn shaderc's free-text error log into structured {@link ShaderDiagnostic}s.
     * Best-effort and LOSSLESS: every line that matches a known shape becomes a
     * typed diagnostic; summary lines ("1 error generated.") are skipped; and if
     * NOTHING parsed, the whole log is returned as a single UNKNOWN diagnostic so a
     * caller is never left with an empty list when a compile actually failed.
     */
    private static List<ShaderDiagnostic> parseLog(String log, String name) {
        List<ShaderDiagnostic> out = new ArrayList<>();
        for (String line : log.split("\\R")) {   // split on any line terminator
            if (line.isBlank()) {
                continue;
            }
            Matcher m = DIAGNOSTIC.matcher(line);
            if (!m.matches()) {
                m = DIAGNOSTIC_PREFIXED.matcher(line);
            }
            if (m.matches()) {
                ShaderDiagnostic.Severity sev = m.group("sev").equalsIgnoreCase("warning")
                        ? ShaderDiagnostic.Severity.WARNING
                        : ShaderDiagnostic.Severity.ERROR;
                int ln = parseLineNumber(m.group("line"));
                out.add(new ShaderDiagnostic(m.group("name").trim(), ln, sev,
                        m.group("msg").trim(), line));
            }
            // A line that matched no pattern is a summary/blank -- intentionally
            // dropped from the structured list; the raw log still carries it.
        }
        if (out.isEmpty()) {
            // Unrecognized format: keep the whole log so the failure is never silent.
            out.add(new ShaderDiagnostic(name, -1, ShaderDiagnostic.Severity.UNKNOWN,
                    log.strip(), log.strip()));
        }
        return out;
    }

    private static int parseLineNumber(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;   // unreachable given the \d+ group, but never throw from a parser
        }
    }
}
