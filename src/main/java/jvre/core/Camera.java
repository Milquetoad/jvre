package jvre.core;

import org.joml.Matrix4f;

/**
 * A 3D camera that computes view + projection matrices ON DEMAND -- the L1 3D
 * helper (Phase 2c). Its real value is centralizing the **Vulkan-correct
 * projection** so users don't rederive the two gotchas every time: clip-space
 * depth is [0,1] (not GL's [-1,1]) and clip/NDC Y points DOWN (not up). Both are
 * solved once here -- the same setup the cube uses.
 *
 * It computes nothing until asked (no hidden per-frame state), consistent with
 * the engine's no-modes ethos. Matrices are exposed BOTH as JOML {@link Matrix4f}
 * (ergonomic for JOML users) AND as a {@code float[16]} (column-major, for those
 * who don't want a JOML dependency) -- the user picks.
 */
public final class Camera {

    private final Matrix4f view = new Matrix4f();
    private final Matrix4f proj = new Matrix4f();
    private final Matrix4f viewProj = new Matrix4f();

    /**
     * Set a perspective projection. {@code fovDegrees} is the vertical field of
     * view; {@code aspect} = width/height; {@code near}/{@code far} the clip
     * planes. Configured for Vulkan (zZeroToOne depth + Y-flip).
     */
    public Camera perspective(float fovDegrees, float aspect, float near, float far) {
        proj.identity().perspective((float) Math.toRadians(fovDegrees), aspect, near, far, true);
        proj.m11(proj.m11() * -1.0f);   // Vulkan clip-space Y points down
        return this;
    }

    /** Point the camera: eye position, look-at target, up direction. */
    public Camera lookAt(float eyeX, float eyeY, float eyeZ,
                         float targetX, float targetY, float targetZ,
                         float upX, float upY, float upZ) {
        view.identity().lookAt(eyeX, eyeY, eyeZ, targetX, targetY, targetZ, upX, upY, upZ);
        return this;
    }

    /** The combined view-projection (projection * view), as a JOML matrix.
     *  Returns an internal instance, valid until the next call. */
    public Matrix4f viewProjection() {
        return proj.mul(view, viewProj);
    }

    /** The view-projection written into {@code dest} (JOML), to avoid aliasing the
     *  internal instance. */
    public Matrix4f viewProjection(Matrix4f dest) {
        return proj.mul(view, dest);
    }

    /** The view-projection as 16 column-major floats (no JOML needed downstream). */
    public float[] viewProjection(float[] dest16) {
        viewProjection().get(dest16);
        return dest16;
    }
}
