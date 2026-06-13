package jvre.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A user-authored fullscreen fragment shader -- the "Shadertoy altitude" from
 * the API Vision, and the FIRST user-facing creative-tier class to exist as
 * real code. The user touches: (1) their fragment shader source, (2) nothing
 * else -- jvre supplies the fullscreen triangle, the pipeline, and fills the
 * built-in uniforms every frame.
 *
 * The v1 uniform contract is Shadertoy's own answer (it has no arbitrary
 * uniforms either -- just iTime/iResolution/iMouse). An effect shader declares
 * this push-constant block and jvre keeps it filled, no set() calls needed:
 *
 * <pre>
 *   layout(location = 0) out vec4 outColor;
 *   layout(push_constant) uniform Push {
 *       vec2 uResolution;   // framebuffer size in pixels
 *       vec2 uMouse;        // cursor position in pixels, top-left origin
 *       float uTime;        // seconds since the renderer started
 *   } pc;
 * </pre>
 *
 * The user writes a COMPLETE, valid shader including that block -- jvre does
 * NOT inject a preamble around the source (injection would shift every line
 * number in compile errors; transparency beats four saved lines for an
 * audience of Java programmers). Arbitrary {@code set(name, value)} uniforms
 * are the post-v1 step (needs SPIR-V reflection).
 *
 * Compilation happens HERE, at creation, via {@link ShaderCompiler} -- bad
 * GLSL fails fast with the shader's own line numbers, before any Vulkan
 * object exists. The Renderer builds the actual pipeline when the effect is
 * installed (it knows the swapchain formats; this class knows none of that).
 */
public final class ShaderEffect {

    private final String name;
    private final byte[] fragmentSpirv;

    private ShaderEffect(String name, byte[] fragmentSpirv) {
        this.name = name;
        this.fragmentSpirv = fragmentSpirv;
    }

    /**
     * Load + compile a fragment shader from a classpath resource (e.g.
     * {@code "/demo/ripple.frag"}). The resource is plain GLSL TEXT, compiled
     * right now, in-process -- no Vulkan SDK, no build-time shader task.
     */
    public static ShaderEffect fromFragment(String resourcePath) {
        String source;
        try (InputStream in = ShaderEffect.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RuntimeException("Effect shader not found on the classpath: "
                        + resourcePath);
            }
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read effect shader " + resourcePath, e);
        }
        return fromFragmentSource(source, resourcePath);
    }

    /** Compile a fragment shader from an in-memory GLSL string. */
    public static ShaderEffect fromFragmentSource(String glslSource, String name) {
        return new ShaderEffect(name, ShaderCompiler.compileFragment(glslSource, name));
    }

    /** The compiled SPIR-V -- consumed by the Renderer's effect pipeline. */
    byte[] fragmentSpirv() {
        return fragmentSpirv;
    }

    /** A human-readable label (the resource path / caller-given name). */
    public String name() {
        return name;
    }
}
