package jvre.core;

import java.util.List;

/**
 * Thrown by {@link ShaderCompiler} when GLSL fails to compile to SPIR-V. Carries
 * the compile log BOTH ways: as the raw text shaderc produced ({@link #rawLog()})
 * and as parsed {@link ShaderDiagnostic} records ({@link #diagnostics()}) so a
 * caller -- a live-reload overlay, an editor integration, a build tool -- can react
 * structurally (which line, error or warning) without re-parsing a string.
 *
 * <p>It is a {@link RuntimeException}: shader-compile failure is a programming error
 * in the shader source, not a recoverable I/O condition, so callers are not forced
 * to declare it. Catching it is for tooling that wants the diagnostics; code that
 * just wants the shader to work can let it propagate. The {@link #getMessage()}
 * names the shader and pretty-prints the diagnostics, so an uncaught one still
 * crashes with a useful, line-numbered report.
 */
public class ShaderCompileException extends RuntimeException {

    private final String shaderName;
    private final transient List<ShaderDiagnostic> diagnostics;
    private final String rawLog;

    ShaderCompileException(String shaderName, List<ShaderDiagnostic> diagnostics, String rawLog) {
        super(buildMessage(shaderName, diagnostics, rawLog));
        this.shaderName = shaderName;
        this.diagnostics = List.copyOf(diagnostics);
        this.rawLog = rawLog;
    }

    /** The shader label that failed (the {@code name} passed to the compile call). */
    public String shaderName() {
        return shaderName;
    }

    /** The parsed messages, in the order shaderc reported them. May contain
     *  warnings alongside the errors; never empty (an unparsable log yields a
     *  single {@link ShaderDiagnostic.Severity#UNKNOWN} entry holding the text). */
    public List<ShaderDiagnostic> diagnostics() {
        return diagnostics;
    }

    /** The errors only -- the subset that actually failed the compile. */
    public List<ShaderDiagnostic> errors() {
        return diagnostics.stream().filter(ShaderDiagnostic::isError).toList();
    }

    /** shaderc's complete, unmodified error log -- the ground truth the parsed
     *  {@link #diagnostics()} are derived from. */
    public String rawLog() {
        return rawLog;
    }

    /** Pretty-print: the shader name, then one indented line per diagnostic. Falls
     *  back to the raw log if nothing parsed (so information is never lost). */
    private static String buildMessage(String shaderName, List<ShaderDiagnostic> diagnostics, String rawLog) {
        StringBuilder sb = new StringBuilder("Shader compilation failed for ")
                .append(shaderName).append(":");
        if (diagnostics.isEmpty()) {
            return sb.append('\n').append(rawLog).toString();
        }
        for (ShaderDiagnostic d : diagnostics) {
            sb.append("\n  ").append(d);
        }
        return sb.toString();
    }
}
