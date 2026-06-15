package jvre.core;

/**
 * A user's per-frame draw callback for custom geometry -- the L1 escape hatch's
 * content seam (a sibling of the L2 {@code Renderer2D} surface and the {@code
 * ShaderEffect} path). Register one with {@link Renderer#setSceneRenderer}; jvre
 * calls it each frame inside the active render pass, handing a {@link
 * FrameRenderer} to record bind/draw against. jvre owns the frame (swapchain,
 * sync, viewport); you own what's drawn.
 */
@FunctionalInterface
public interface SceneRenderer {
    void render(FrameRenderer frame);
}
