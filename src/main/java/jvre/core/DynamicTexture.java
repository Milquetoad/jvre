package jvre.core;

import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Arrays;

/**
 * A texture whose pixels you REWRITE from the CPU each frame, then bind as a
 * channel -- the dynamic counterpart to the upload-once {@link Texture}. The
 * mechanism behind feeding a shader live CPU-side data: an audio FFT, procedurally
 * generated noise, or the keyboard-state texture the {@code jvre.demo.KeyboardDemo}
 * builds (the Shadertoy keyboard-channel convention, kept app-side).
 *
 * <h2>Why it is not just a Texture</h2>
 * A plain {@link Texture} is filled once at birth; sampling it forever after is
 * safe because nothing writes it again. A texture that changes EVERY frame has a
 * hazard: with two frames in flight, while the GPU still samples frame N-1's copy
 * the CPU wants to write frame N's. So a DynamicTexture keeps ONE image PER
 * frame-in-flight slot (exactly like the per-frame uniform buffers): each frame
 * writes + samples its OWN copy, so there is never a write-while-read across frames.
 * The renderer applies the pending pixels into the right slot at the safe point
 * (after that slot's fence) and records the GPU copy at the top of the frame.
 *
 * <h2>Use</h2>
 * <pre>
 *   DynamicTexture kb = renderer.createDynamicTexture(256, 3);   // e.g. keyboard
 *   ...
 *   while (open) {
 *       kb.update(buildPixels());                 // new pixels for this frame
 *       renderer.setEffectChannel(0, kb);         // (or frame.texture(0, kb) at L1)
 *       renderer.drawFrame();
 *   }
 * </pre>
 * Pixels are R8G8B8A8 (4 bytes/texel, row-major), interpreted as DATA (the format
 * is UNORM, not sRGB). The caller OWNS it -- {@code close()} it before the Renderer.
 */
public final class DynamicTexture {

    private final int width;
    private final int height;
    private final int byteSize;             // width * height * 4
    private final Texture[] copies;         // one image per frame-in-flight slot
    private final Buffer[] staging;         // one persistent host-visible staging buffer per slot
    private final boolean[] dirty;          // per slot: needs its pending pixels copied in
    private byte[] pending;                 // the latest CPU pixels (shared across slots)

    DynamicTexture(Device device, long commandPool, int width, int height, int framesInFlight) {
        this.width = width;
        this.height = height;
        this.byteSize = width * height * 4;
        this.copies = new Texture[framesInFlight];
        this.staging = new Buffer[framesInFlight];
        this.dirty = new boolean[framesInFlight];
        TextureOptions opts = TextureOptions.builder()
                .filter(Filter.NEAREST).wrap(WrapMode.CLAMP).build();   // data -> exact texels
        for (int i = 0; i < framesInFlight; i++) {
            copies[i] = Texture.createUpdatable(device, commandPool, width, height, opts);
            staging[i] = new Buffer(device, byteSize,
                    org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
        }
    }

    /**
     * Set the pixels to show from the next frame on. R8G8B8A8, row-major,
     * {@code width*height*4} bytes. Cheap (a CPU copy + marking each slot dirty);
     * the GPU upload is deferred to the per-frame safe point. Call it whenever the
     * data changes -- typically once per frame.
     */
    public void update(byte[] rgba) {
        if (rgba.length != byteSize) {
            throw new IllegalArgumentException("DynamicTexture is " + width + "x" + height
                    + " (" + byteSize + " bytes); got " + rgba.length);
        }
        pending = rgba.clone();
        Arrays.fill(dirty, true);   // every slot must pick up the new pixels on its next frame
    }

    /** Pixel dimensions of the texture. */
    public int width()  { return width; }
    public int height() { return height; }

    // ---- package-private: the renderer drives these from the frame loop ----

    /** If slot {@code frame} has pending pixels, stage them and record the GPU copy
     *  into {@code cmd} (ordered before this frame's draws). Called at the top of the
     *  frame's command buffer, after the slot's fence -- so the copy is hazard-free. */
    void recordIfDirty(VkCommandBuffer cmd, int frame) {
        if (pending == null || !dirty[frame]) {
            return;
        }
        staging[frame].uploadBytes(pending);
        copies[frame].recordUpdate(cmd, staging[frame]);
        dirty[frame] = false;
    }

    /** The image to bind for slot {@code frame} (this frame's own copy). */
    Texture frameTexture(int frame) {
        return copies[frame];
    }

    /** Destroy every per-frame image + staging buffer. */
    public void close() {
        for (Texture t : copies) {
            if (t != null) t.close();
        }
        for (Buffer b : staging) {
            if (b != null) b.close();
        }
    }
}
