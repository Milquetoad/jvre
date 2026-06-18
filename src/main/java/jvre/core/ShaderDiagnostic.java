package jvre.core;

/**
 * One structured message from a shader compile -- a single error or warning, with
 * its line number pulled OUT of shaderc's text log so tooling can act on it
 * (jump-to-line in an editor, an on-screen overlay, a live-reload panel) instead
 * of re-parsing a human string.
 *
 * <p>shaderc (glslang under the hood) emits a log like
 * {@code myshader.frag:4: error: '' : syntax error, unexpected RIGHT_BRACE}.
 * {@link ShaderCompiler} parses each such line into one of these. The original,
 * unmodified text of the line is kept in {@link #raw()} so nothing is ever lost to
 * an imperfect parse -- the structured fields are a convenience layered ON TOP of
 * the ground truth, never a replacement for it.
 *
 * @param name     the shader label shaderc reported the message against (the
 *                 {@code name} passed to {@link ShaderCompiler#compileFragment} etc.)
 * @param line     1-based source line, or {@code -1} if the line could not be parsed
 * @param severity error vs warning (vs {@link Severity#UNKNOWN} for an unparsed line)
 * @param message  the human-readable text AFTER the {@code name:line: severity:} prefix
 * @param raw      the entire original log line, verbatim
 */
public record ShaderDiagnostic(String name, int line, Severity severity,
                               String message, String raw) {

    /** Whether a diagnostic stops compilation (ERROR) or merely advises (WARNING).
     *  UNKNOWN is the honest answer for a log line that didn't fit the expected
     *  {@code name:line: severity:} shape -- kept rather than silently dropped. */
    public enum Severity { ERROR, WARNING, UNKNOWN }

    /** True for {@link Severity#ERROR} -- the ones that actually failed the build. */
    public boolean isError() {
        return severity == Severity.ERROR;
    }

    /** A compact one-line rendering for logs / messages: {@code "line 4: error: ..."}
     *  (or just the raw text when there was no line to report). */
    @Override
    public String toString() {
        String sev = severity.name().toLowerCase();
        return line >= 0 ? "line " + line + ": " + sev + ": " + message
                         : sev + ": " + message;
    }
}
