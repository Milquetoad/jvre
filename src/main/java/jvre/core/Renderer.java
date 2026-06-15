package jvre.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * The coordinator of the recreatable "device context": OWNS the Device and the
 * Swapchain (it constructs them), plus everything needed to get a frame onto
 * the screen -- command pool/buffers, sync objects, and the per-frame draw.
 *
 * The ownership is the point: Instance + Surface live ABOVE this class and stay
 * put for the life of the app, while everything GPU-dependent hangs off the
 * Renderer. Switching GPUs (future) = tear down the Renderer, build a new one.
 * Resizing = the Renderer rebuilds its own swapchain-dependent slice.
 *
 * Infrastructure tier: an L2 user never touches this directly; the L1 facade
 * will construct one behind the scenes.
 */
public class Renderer {

    // Vulkan's "wait forever" timeout sentinel (UINT64_MAX) for fences/acquire.
    private static final long NO_TIMEOUT = 0xFFFFFFFFFFFFFFFFL;

    // Kept for swapchain recreation: rebuilding negotiates against the live
    // surface and the window's current framebuffer size.
    private final Surface surface;
    private final Window window;

    private static final String SHAPE2D_VERT = "/shaders/shape2d.vert.spv";
    private static final String SHAPE2D_FRAG = "/shaders/shape2d.frag.spv";

    private final Device device;
    private Swapchain swapchain;

    // The optional fullscreen ShaderEffect: when installed, recordCommandBuffer
    // draws IT instead of the cube demo -- the first content seam in the
    // renderer (the cube machinery stays warm underneath). The effect's
    // pipeline lives HERE, not in ShaderEffect: pipelines bake swapchain
    // formats + the sample count, which only the renderer knows.
    private ShaderEffect effect;
    private Pipeline effectPipeline;

    // jvre's own fullscreen-triangle vertex shader (build-time compiled, like
    // all internal shaders) -- shared by every effect.
    private static final String FULLSCREEN_VERT = "/shaders/fullscreen.vert.spv";

    // The optional L2 Renderer2D (the "just draw" altitude). Like the effect it
    // is a CONTENT SEAM: when the user has drawn shapes this frame,
    // recordCommandBuffer draws THEM. Its pipeline + per-frame vertex arenas live
    // HERE -- pipelines bake swapchain formats, and the arenas are fence-guarded
    // per-frame slots exactly like the UBOs. Created lazily on renderer2D().
    private Renderer2D renderer2D;
    private Pipeline shapePipeline;
    private Buffer[] shapeArenas;   // [frame in flight], host-visible, grown on overflow
    // Initial per-frame arena (~2730 shape vertices at 6 floats each); grows if exceeded.
    private static final long INITIAL_ARENA_BYTES = 64 * 1024;

    // The shape pipeline's texture descriptors. A frame can draw several textures
    // (one DRAW RUN each -- see Renderer2D), and a single set can't be rebound
    // between draws in one command buffer, so we need one set PER RUN. The textbook
    // pattern: a TRANSIENT per-frame pool, RESET and re-allocated each frame (one
    // set per run), fence-guarded like every other per-frame resource. One pool
    // per frame in flight; it grows if a frame ever needs more runs than it holds.
    private long[] shapeDescriptorPools;   // [frame in flight]
    private int[] shapePoolCapacity;       // current maxSets per pool (for growth)
    private long[] currentRunSets;         // this frame's per-run sets (transient, rebuilt each frame)
    private static final int INITIAL_SHAPE_POOL_SETS = 16;
    // A 1x1 opaque white texture: the always-valid default bound when no image is
    // drawn (the shader's sampler is referenced statically, so SOMETHING must be
    // bound even when no shape samples it). White also = the neutral tint.
    private Texture defaultWhiteTexture;
    // The built-in font (DejaVu Sans), baked to an SDF atlas on first text use.
    private Font defaultFont;

    // The L1 custom-geometry content seam (the escape hatch). When set, it draws
    // each frame alongside any L2 shapes; the cube demo is the fallback when
    // neither is active. User-owned pipelines/buffers -- jvre just invokes this.
    private SceneRenderer sceneRenderer;

    // The clear color (RGBA in [0,1]); becomes real API once there is more to
    // render than a clear. The swapchain is an sRGB format, so these linear
    // values are sRGB-encoded on write.
    private final float clearR;
    private final float clearG;
    private final float clearB;
    // Present-mode preference (creation-time): true = vsync/FIFO, false = uncapped.
    // Stored so swapchain recreation (resize) keeps the same choice.
    private final boolean vsync;
    // Requested MSAA sample count (creation-time, clamped to device max in the
    // Swapchain). Stored to keep the choice across swapchain recreation.
    private final int msaaRequested;

    // How many frames the CPU may be preparing AHEAD of the GPU. With 1, CPU and
    // GPU take turns idling (CPU waits for the frame to finish before recording
    // the next). With 2, the CPU records frame N+1 while the GPU draws frame N --
    // the standard sweet spot. Higher adds input latency for little throughput.
    private static final int MAX_FRAMES_IN_FLIGHT = 2;

    // Command pool: allocator for command buffers, tied to one queue family
    // (graphics). Each frame in flight owns ONE buffer, re-recorded every frame
    // (the modern model -- a real scene changes every frame, and the target
    // image isn't known until acquire). Destroying the pool frees the buffers.
    private long commandPool = VK_NULL_HANDLE;
    private VkCommandBuffer[] commandBuffers;   // [frame in flight], NOT [image]

    // Synchronization, one set per frame in flight: the semaphore orders
    // GPU<->GPU steps; the fence lets the CPU know that frame's GPU work is done
    // so its command buffer + semaphore are safe to reuse.
    private long[] imageAvailableSemaphores;    // [frame] image ready to draw into
    private long[] inFlightFences;              // [frame] GPU finished this frame
    // ONE renderFinished semaphore PER swapchain image -- indexed by ACQUIRED
    // IMAGE, not by frame: a per-frame one races with presentation
    // (validation VUID-...-00067).
    private long[] renderFinishedSemaphores;    // [image] render done, safe to present

    // Which frame-in-flight slot we're on (cycles 0..MAX_FRAMES_IN_FLIGHT-1).
    private int currentFrame = 0;

    // Animation clock: the time push constant + the public time()/dt() count
    // seconds from here. lastFrameNanos + deltaSeconds drive dt() (the previous
    // frame's wall-clock duration, for frame-rate-independent animation).
    private final long startNanos = System.nanoTime();
    private long lastFrameNanos = startNanos;
    private float deltaSeconds = 0f;

    public Renderer(Instance instance, Surface surface, Window window, RendererOptions options) {
        this.surface = surface;
        this.window = window;
        this.clearR = options.clearR;
        this.clearG = options.clearG;
        this.clearB = options.clearB;
        this.vsync = options.vsync;
        this.msaaRequested = options.msaa;

        // The device context, top to bottom. The pipeline needs the swapchain's
        // image format (dynamic rendering's one remaining coupling).
        this.device = new Device(instance, surface);
        this.swapchain = new Swapchain(device, surface, window, vsync, msaaRequested);
        // The command pool: one-shot transfer command buffers (texture/buffer
        // uploads from createImage / createVertexBuffer / font) record from it, as
        // do the per-frame command buffers. No built-in geometry anymore -- the
        // renderer ships no cube; users bring their own via createPipeline.
        createCommandPool();
        createCommandBuffers();
        createSyncObjects();
    }

    // ------------------------------------------------------------------
    // ShaderEffect (the Shadertoy altitude)
    // ------------------------------------------------------------------

    /**
     * Show a fullscreen {@link ShaderEffect} instead of the cube demo. The
     * user's fragment shader was already compiled (at ShaderEffect creation,
     * via runtime shaderc); here it gets a pipeline against the current
     * swapchain. Its built-in uniforms (uResolution/uMouse/uTime) are filled
     * automatically every frame -- no further calls needed.
     */
    public void setEffect(ShaderEffect effect) {
        this.effect = effect;
        buildEffectPipeline();
    }

    private void buildEffectPipeline() {
        if (effectPipeline != null) {
            effectPipeline.close();
        }
        effectPipeline = Pipeline.fullscreenEffect(device,
                swapchain.imageFormat(), swapchain.depthFormat(), swapchain.sampleCount(),
                Pipeline.readResource(FULLSCREEN_VERT), effect.fragmentSpirv(),
                "fullscreen + " + effect.name());
    }

    // ------------------------------------------------------------------
    // Renderer2D (the L2 "just draw" altitude)
    // ------------------------------------------------------------------

    /**
     * The L2 immediate-mode 2D surface, created on first use. The user calls
     * begin() / fillRect() / ... / end() each frame; drawFrame then uploads the
     * accumulated vertices into this frame's arena and draws them -- a content
     * seam alongside the cube and the effect.
     */
    public Renderer2D renderer2D() {
        if (renderer2D == null) {
            renderer2D = new Renderer2D(this);
            buildShapePipeline();
            shapeArenas = new Buffer[MAX_FRAMES_IN_FLIGHT];
            for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
                shapeArenas[i] = new Buffer(device, INITIAL_ARENA_BYTES,
                        VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, true);
            }
            // The 1x1 white default + the per-frame texture descriptor sets the
            // image/text shapes sample through.
            defaultWhiteTexture = Texture.create(device, commandPool,
                    new byte[] { (byte) 255, (byte) 255, (byte) 255, (byte) 255 }, 1, 1);
            createShapeDescriptors();
        }
        return renderer2D;
    }

    /**
     * Create a drawable image from raw RGBA pixels (R8G8B8A8, row-major,
     * top-to-bottom) for {@link Renderer2D#image}. The L1 image entry point:
     * decoding image FILES (PNG/JPG, via stb_image) is a later convenience that
     * will funnel here too. The caller OWNS the returned Texture and must
     * {@code close()} it before the Renderer (it frees VMA memory the device owns).
     */
    public Texture createImage(byte[] rgbaPixels, int width, int height) {
        return Texture.create(device, commandPool, rgbaPixels, width, height);
    }

    /**
     * The built-in default font (DejaVu Sans), baked to an SDF atlas on first use.
     * What {@link Renderer2D#text} draws with. Renderer-owned (lives as long as
     * the device); loading custom fonts is a later convenience that will funnel
     * through the same {@link Font#load}.
     */
    public Font font() {
        if (defaultFont == null) {
            defaultFont = Font.load(device, commandPool, "/fonts/DejaVuSans.ttf", 48f);
        }
        return defaultFont;
    }

    /**
     * Build a USER-DEFINED pipeline (the L1 escape hatch) from a {@link
     * PipelineSpec}. jvre injects the swapchain color/depth formats + sample count
     * it owns, so the user never passes Vulkan formats. The caller OWNS the
     * returned Pipeline and must {@code close()} it before the Renderer. (Note: it
     * bakes the current swapchain format; a format change on resize -- rare --
     * would require rebuilding it. A rebuild hook is a later refinement.)
     */
    public Pipeline createPipeline(PipelineSpec spec) {
        return Pipeline.fromSpec(device, swapchain.imageFormat(), swapchain.depthFormat(),
                swapchain.sampleCount(), spec, MAX_FRAMES_IN_FLIGHT);
    }

    /**
     * Create a device-local vertex buffer from interleaved float data, for a
     * custom pipeline's geometry. Caller-owned: {@code close()} it before the
     * Renderer.
     */
    public Buffer createVertexBuffer(float[] vertices) {
        return Buffer.deviceLocal(device, commandPool, vertices, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
    }

    /**
     * Create a device-local UINT16 index buffer for a custom pipeline's indexed
     * geometry (shared vertices fetched once, not duplicated). Caller-owned:
     * {@code close()} it before the Renderer.
     */
    public Buffer createIndexBuffer(short[] indices) {
        return Buffer.deviceLocal(device, commandPool, indices, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
    }

    /**
     * Install the per-frame custom-geometry callback (the L1 scene seam). It runs
     * each frame inside the active render pass, receiving a {@link FrameRenderer}
     * to record bind/draw against. Pass {@code null} to remove it.
     */
    public void setSceneRenderer(SceneRenderer sceneRenderer) {
        this.sceneRenderer = sceneRenderer;
    }

    private void buildShapePipeline() {
        if (shapePipeline != null) {
            shapePipeline.close();
        }
        shapePipeline = Pipeline.shapes2D(device, swapchain.imageFormat(),
                swapchain.depthFormat(), swapchain.sampleCount(), SHAPE2D_VERT, SHAPE2D_FRAG);
    }

    /** True when the L2 surface exists and the user drew at least one shape this frame. */
    private boolean shapeBatchActive() {
        return renderer2D != null && renderer2D.floatCount() > 0;
    }

    /** Current framebuffer size in pixels -- what the L2 surface exposes (g.width()/
     *  g.height()) so the user can compose relative layout as plain arithmetic. */
    int framebufferWidth()  { return swapchain.width(); }
    int framebufferHeight() { return swapchain.height(); }

    /**
     * Grow this frame's arena if the batch outgrew it. Safe to destroy the old
     * buffer: the slot's fence already signaled (waited on at the top of
     * drawFrame), so the GPU is done reading it.
     */
    private void ensureArenaCapacity(int frame, int floatCount) {
        long needed = (long) floatCount * Float.BYTES;
        long have = shapeArenas[frame].size();
        if (needed > have) {
            shapeArenas[frame].close();
            shapeArenas[frame] = new Buffer(device, Math.max(needed, have * 2),
                    VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, true);
        }
    }

    /**
     * Create the per-frame transient descriptor pools the shape runs allocate
     * from. One pool per frame in flight (so resetting one never touches a pool
     * the GPU is still reading from another in-flight frame). Created once;
     * survives a pipeline rebuild (identically-defined layouts are compatible).
     */
    private void createShapeDescriptors() {
        shapeDescriptorPools = new long[MAX_FRAMES_IN_FLIGHT];
        shapePoolCapacity = new int[MAX_FRAMES_IN_FLIGHT];
        for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
            shapePoolCapacity[i] = INITIAL_SHAPE_POOL_SETS;
            shapeDescriptorPools[i] = createShapePool(INITIAL_SHAPE_POOL_SETS);
        }
    }

    /** A descriptor pool holding up to {@code maxSets} combined-image-sampler sets. */
    private long createShapePool(int maxSets) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            poolSizes.get(0).descriptorCount(maxSets);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
            poolInfo.pPoolSizes(poolSizes);
            poolInfo.maxSets(maxSets);  // reset-and-reallocate; no FREE_DESCRIPTOR_SET bit needed

            LongBuffer pPool = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkCreateDescriptorPool(device.handle(), poolInfo, null, pPool),
                    "Failed to create a shape descriptor pool");
            return pPool.get(0);
        }
    }

    /**
     * Rebuild this frame's per-run descriptor sets: reset the frame's pool (the
     * slot's fence was waited on in drawFrame, so the GPU is done with last time's
     * sets), then allocate + write one set per run, each pointing at its run's
     * texture (or the white default for a null/flat run). The handles land in
     * {@link #currentRunSets} for recordCommandBuffer to bind per draw. Grows the
     * pool first if this frame has more runs than it currently holds.
     */
    private void prepareShapeDescriptors(int frame) {
        int runs = renderer2D.runCount();
        if (runs > shapePoolCapacity[frame]) {
            int cap = shapePoolCapacity[frame];
            while (cap < runs) {
                cap *= 2;
            }
            vkDestroyDescriptorPool(device.handle(), shapeDescriptorPools[frame], null);
            shapeDescriptorPools[frame] = createShapePool(cap);
            shapePoolCapacity[frame] = cap;
        } else {
            vkResetDescriptorPool(device.handle(), shapeDescriptorPools[frame], 0);
        }

        if (currentRunSets == null || currentRunSets.length < runs) {
            currentRunSets = new long[runs];
        }

        try (MemoryStack stack = stackPush()) {
            // Allocate all the run sets at once (one layout handle per set).
            LongBuffer layouts = stack.mallocLong(runs);
            for (int r = 0; r < runs; r++) {
                layouts.put(shapePipeline.descriptorSetLayout());
            }
            layouts.flip();

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
            allocInfo.descriptorPool(shapeDescriptorPools[frame]);
            allocInfo.pSetLayouts(layouts);

            LongBuffer pSets = stack.mallocLong(runs);
            Vk.check(vkAllocateDescriptorSets(device.handle(), allocInfo, pSets),
                    "Failed to allocate shape run descriptor sets");

            // Point each run's set at its texture (binding 0).
            for (int r = 0; r < runs; r++) {
                long set = pSets.get(r);
                currentRunSets[r] = set;
                Texture tex = renderer2D.runTexture(r);
                if (tex == null) {
                    tex = defaultWhiteTexture;
                }

                VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
                imageInfo.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                imageInfo.imageView(tex.view());
                imageInfo.sampler(tex.sampler());

                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
                writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                writes.get(0).dstSet(set);
                writes.get(0).dstBinding(0);
                writes.get(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                writes.get(0).descriptorCount(1);
                writes.get(0).pImageInfo(imageInfo);

                vkUpdateDescriptorSets(device.handle(), writes, null);
            }
        }
    }

    // ------------------------------------------------------------------
    // (Removed: the built-in cube's uniform buffers, descriptor pool/sets,
    // model-view-projection, and checkerboard texture -- the hardcoded SCENE is
    // retired. 3D geometry now goes through the public custom-pipeline API:
    // createPipeline + a SceneRenderer, with per-pipeline UBO/texture descriptors
    // owned by Pipeline. See the demo cube in Main.)
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Command pool + buffers -- record barrier / clear / barrier (dynamic rendering)
    // ------------------------------------------------------------------

    /** The pool command buffers are allocated from, bound to the graphics family. */
    private void createCommandPool() {
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            poolInfo.queueFamilyIndex(device.graphicsFamily());
            // We re-record each frame's buffer every frame, so buffers must be
            // individually resettable. (The earlier record-once model used no
            // flags; it died with the move to per-frame recording.)
            poolInfo.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            LongBuffer pPool = stack.longs(VK_NULL_HANDLE);
            Vk.check(vkCreateCommandPool(device.handle(), poolInfo, null, pPool),
                    "Failed to create the command pool");
            commandPool = pPool.get(0);
        }
        System.out.println("Command pool created.");
    }

    /**
     * Allocate one primary command buffer per frame in flight. They start EMPTY:
     * recording happens per frame in {@link #recordCommandBuffer}, because a real
     * frame's contents change every frame and the target image isn't known until
     * acquire. (This replaced the earlier learning model of pre-recording one
     * buffer per swapchain IMAGE once and replaying it.)
     */
    private void createCommandBuffers() {
        commandBuffers = new VkCommandBuffer[MAX_FRAMES_IN_FLIGHT];

        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.commandPool(commandPool);
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);  // submittable directly
            allocInfo.commandBufferCount(MAX_FRAMES_IN_FLIGHT);

            PointerBuffer pBuffers = stack.mallocPointer(MAX_FRAMES_IN_FLIGHT);
            Vk.check(vkAllocateCommandBuffers(device.handle(), allocInfo, pBuffers),
                    "Failed to allocate command buffers");
            for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
                commandBuffers[i] = new VkCommandBuffer(pBuffers.get(i), device.handle());
            }
        }
        System.out.println("Allocated " + MAX_FRAMES_IN_FLIGHT + " per-frame command buffers.");
    }

    /**
     * Record one frame's commands into {@code cmd}, targeting the acquired
     * swapchain image: barrier (UNDEFINED -> COLOR_ATTACHMENT_OPTIMAL) ->
     * begin rendering (clear into the image's VIEW) -> end rendering ->
     * barrier (COLOR_ATTACHMENT_OPTIMAL -> PRESENT_SRC_KHR).
     *
     * loadOp=CLEAR (on the rendering attachment) does the actual clearing; with
     * no render pass, the two barriers do by hand what initial/finalLayout used
     * to do invisibly. oldLayout=UNDEFINED means "discard prior contents" --
     * exactly right, since we re-clear the whole image every frame.
     */
    private void recordCommandBuffer(VkCommandBuffer cmd, int imageIndex, float time) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);

            // The clear color: one VkClearValue for the one color attachment.
            VkClearValue.Buffer clearValue = VkClearValue.calloc(1, stack);
            clearValue.get(0).color().float32(stack.floats(clearR, clearG, clearB, 1.0f));

            // The depth clear: 1.0 = the FAR plane, so the first fragment at any
            // pixel always wins the LESS test (nothing is "in front of" a fresh frame).
            VkClearValue depthClear = VkClearValue.calloc(stack);
            depthClear.depthStencil().depth(1.0f).stencil(0);

            // One reusable image-memory barrier struct, in the synchronization2
            // spelling: VkImageMemoryBarrier2 carries its OWN src/dst STAGE masks.
            // (Under 1.0 sync the stages were per-CALL arguments that covered every
            // barrier in the call -- sync2 moves them onto each barrier, where they
            // belong.) Retargeted between the two barriers below. The subresource
            // range = the whole color image (1 mip, 1 layer), and we don't transfer
            // queue-family ownership.
            VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
            barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2);
            barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            barrier.subresourceRange().baseMipLevel(0);
            barrier.subresourceRange().levelCount(1);
            barrier.subresourceRange().baseArrayLayer(0);
            barrier.subresourceRange().layerCount(1);

            // vkCmdPipelineBarrier2 takes ONE bundle struct (VkDependencyInfo)
            // pointing at the barrier arrays, replacing 1.0's seven-argument call.
            // We reuse a single bundle for both barriers: it just POINTS at the
            // barrier struct above, which we mutate between the two calls.
            VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack);
            depInfo.sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO);
            depInfo.pImageMemoryBarriers(barrier);

            // The dynamic-rendering color attachment. TWO paths, by MSAA:
            //   - MSAA ON: the scene renders into the MULTISAMPLED image; the
            //     swapchain image is only the RESOLVE destination (resolveMode
            //     AVERAGE -- averaged at vkCmdEndRendering, no extra pass). storeOp
            //     DONT_CARE on the MSAA image: once resolved its per-sample data is
            //     garbage (same logic as depth).
            //   - MSAA OFF (1 sample): no MSAA target exists; render DIRECTLY into
            //     the swapchain image, no resolve, storeOp STORE (the rendered
            //     result IS what presents).
            boolean msaa = swapchain.sampleCount() != VK_SAMPLE_COUNT_1_BIT;
            VkRenderingAttachmentInfo.Buffer colorAttachment =
                    VkRenderingAttachmentInfo.calloc(1, stack);
            colorAttachment.sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO);
            colorAttachment.imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            if (msaa) {
                colorAttachment.imageView(swapchain.msaaColorView());
                colorAttachment.resolveMode(VK_RESOLVE_MODE_AVERAGE_BIT);
                colorAttachment.resolveImageView(swapchain.imageView(imageIndex));
                colorAttachment.resolveImageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                colorAttachment.storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
            } else {
                colorAttachment.imageView(swapchain.imageView(imageIndex));  // render direct
                colorAttachment.resolveMode(VK_RESOLVE_MODE_NONE);
                colorAttachment.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            }
            colorAttachment.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR);
            colorAttachment.clearValue(clearValue.get(0));

            // The depth attachment: CLEAR on load; DONT_CARE on store -- the depth
            // buffer is never read after the frame or presented, so storing it is
            // wasted bandwidth.
            VkRenderingAttachmentInfo depthAttachment =
                    VkRenderingAttachmentInfo.calloc(stack);
            depthAttachment.sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO);
            depthAttachment.imageView(swapchain.depthView());
            depthAttachment.imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            depthAttachment.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR);
            depthAttachment.storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
            depthAttachment.clearValue(depthClear);

            VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack);
            renderingInfo.sType(VK_STRUCTURE_TYPE_RENDERING_INFO);
            renderingInfo.renderArea().offset().x(0).y(0);           // whole image
            renderingInfo.renderArea().extent().width(swapchain.width()).height(swapchain.height());
            renderingInfo.layerCount(1);
            renderingInfo.pColorAttachments(colorAttachment);  // colorAttachmentCount inferred from the buffer
            renderingInfo.pDepthAttachment(depthAttachment);

            Vk.check(vkBeginCommandBuffer(cmd, beginInfo),
                    "Failed to begin the command buffer");

            // ---- Barrier 1: make the swapchain image resolvable (UNDEFINED -> COLOR) ----
            // It now receives the RESOLVE write (still a color-attachment write at
            // COLOR_ATTACHMENT_OUTPUT, so the masks are unchanged). Gate at
            // COLOR_ATTACHMENT_OUTPUT (the same stage the submit waits on for
            // image acquisition); nothing read before (ACCESS_2_NONE).
            barrier.image(swapchain.image(imageIndex));
            barrier.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            barrier.newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            barrier.srcStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT);
            barrier.srcAccessMask(VK_ACCESS_2_NONE);
            barrier.dstStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT);
            barrier.dstAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
            vkCmdPipelineBarrier2(cmd, depInfo);

            // ---- Barrier 1b: make the MSAA color target renderable (MSAA only) ----
            // Retarget the same struct. Like the depth buffer, this is ONE image
            // SHARED by all frames in flight (not per-frame, not rotating), so
            // srcStage/srcAccess must cover the PREVIOUS frame's color writes --
            // the same cross-frame WAW lesson the depth barrier taught. Skipped
            // when MSAA is off (no MSAA target -- we render into the swapchain
            // image, already transitioned by barrier 1).
            if (msaa) {
                barrier.image(swapchain.msaaColorImage());
                barrier.srcAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);  // prev frame's writes
                vkCmdPipelineBarrier2(cmd, depInfo);
            }

            // ---- Barrier 1b: make the DEPTH image writable (UNDEFINED -> DEPTH) ----
            // Its own barrier: different image, DEPTH aspect, and the depth-test
            // stages (EARLY/LATE_FRAGMENT_TESTS) rather than color output. No
            // "after" transition -- the depth buffer is never presented and we
            // DONT_CARE about storing it, so one transition per frame suffices.
            VkImageMemoryBarrier2.Buffer depthBarrier = VkImageMemoryBarrier2.calloc(1, stack);
            depthBarrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2);
            depthBarrier.image(swapchain.depthImage());
            depthBarrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            depthBarrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            depthBarrier.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            depthBarrier.newLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            // SRC must cover the PREVIOUS frame's depth writes. The depth buffer is
            // a SINGLE image SHARED by all frames in flight (unlike the per-frame
            // UBOs, or the swapchain's rotating color images ordered by acquire +
            // semaphores). So this layout transition -- itself a write -- races the
            // prior frame's depth-attachment writes unless we wait for them. With
            // src = NONE the sync-validation layer (rightly) flagged a WAW hazard;
            // gating on the depth-test stages + DEPTH write makes the transition
            // wait. The first frame has no prior depth write -- harmless.
            depthBarrier.srcStageMask(VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT
                    | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT);
            depthBarrier.srcAccessMask(VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            depthBarrier.dstStageMask(VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT
                    | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT);
            depthBarrier.dstAccessMask(VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                    | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT);
            depthBarrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
            depthBarrier.subresourceRange().baseMipLevel(0);
            depthBarrier.subresourceRange().levelCount(1);
            depthBarrier.subresourceRange().baseArrayLayer(0);
            depthBarrier.subresourceRange().layerCount(1);

            VkDependencyInfo depthDep = VkDependencyInfo.calloc(stack);
            depthDep.sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO);
            depthDep.pImageMemoryBarriers(depthBarrier);
            vkCmdPipelineBarrier2(cmd, depthDep);

            // ---- Render, via dynamic rendering (no render pass / framebuffer) ----
            // loadOp=CLEAR has already filled the image (the orange background)
            // by the time the triangle is drawn over it.
            vkCmdBeginRendering(cmd, renderingInfo);

            // Viewport + scissor are DYNAMIC pipeline state (so resize never
            // rebuilds the pipeline) -- set them every frame, here. They apply
            // to whichever pipeline is bound below.
            // Viewport = NDC -> pixels mapping; scissor = hard pixel clip.
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.x(0.0f).y(0.0f);
            viewport.width(swapchain.width()).height(swapchain.height());
            viewport.minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(cmd, 0, viewport);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().x(0).y(0);
            scissor.extent().width(swapchain.width()).height(swapchain.height());
            vkCmdSetScissor(cmd, 0, scissor);

            // The content seam: a fullscreen ShaderEffect when installed; else the
            // L1 custom-geometry scene and/or the L2 shapes (both may run); else
            // just the clear (no built-in geometry).
            if (effectPipeline != null) {
                // ---- ShaderEffect: 3 vertices, ZERO buffers ----
                // No vertex buffer, no index buffer, no descriptor sets -- the
                // vertex shader fabricates the fullscreen triangle from
                // gl_VertexIndex, and the effect's whole interface is the
                // 20-byte builtin push block, filled here without being asked.
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, effectPipeline.handle());

                DoubleBuffer mx = stack.mallocDouble(1);
                DoubleBuffer my = stack.mallocDouble(1);
                window.cursorPos(mx, my);

                // vec2 uResolution @0, vec2 uMouse @8, float uTime @16.
                vkCmdPushConstants(cmd, effectPipeline.layout(), VK_SHADER_STAGE_FRAGMENT_BIT, 0,
                        stack.floats(swapchain.width(), swapchain.height(),
                                (float) mx.get(0), (float) my.get(0), time));

                // Not indexed, no instances: literally "run the vertex shader
                // three times".
                vkCmdDraw(cmd, 3, 1, 0, 0);
            } else {
                // Content seam (non-effect): the L1 custom-geometry scene first
                // (under any L2 UI), then the L2 shapes on top -- both may run in
                // one frame. The cube demo is the fallback when neither is set.
                if (sceneRenderer != null) {
                    sceneRenderer.render(new FrameRenderer(cmd, currentFrame));
                }
                if (shapeBatchActive()) {
                // ---- L2 Renderer2D shapes ----
                // One vertex buffer (this frame's arena), no index buffer, no
                // descriptors. The 8-byte VERTEX push carries uResolution so the
                // shape vertex shader maps pixels -> NDC.
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, shapePipeline.handle());
                vkCmdBindVertexBuffers(cmd, 0,
                        stack.longs(shapeArenas[currentFrame].handle()), stack.longs(0));
                vkCmdPushConstants(cmd, shapePipeline.layout(), VK_SHADER_STAGE_VERTEX_BIT, 0,
                        stack.floats(swapchain.width(), swapchain.height()));
                // One draw per RUN, in paint order: bind the run's texture (flat/
                // SDF shapes ignore it but it must be validly bound), then draw the
                // run's vertex slice. The arena is one buffer; firstVertex offsets
                // into it. Same-texture runs already merged in Renderer2D.
                int runs = renderer2D.runCount();
                int total = renderer2D.vertexCount();
                for (int r = 0; r < runs; r++) {
                    int first = renderer2D.runFirstVertex(r);
                    int last = (r + 1 < runs) ? renderer2D.runFirstVertex(r + 1) : total;
                    int vcount = last - first;
                    if (vcount <= 0) {
                        continue;
                    }
                    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                            shapePipeline.layout(), 0, stack.longs(currentRunSets[r]), null);
                    vkCmdDraw(cmd, vcount, 1, first, 0);
                }
                }
                // (No built-in fallback geometry: when no effect/scene/shapes are
                // set, the frame is just the clear. Users draw via the scene seam.)
            }

            vkCmdEndRendering(cmd);

            // ---- Barrier 2: make the SWAPCHAIN image presentable (COLOR -> PRESENT_SRC) ----
            // Re-target the image explicitly: barrier 1b left the MSAA image in
            // this struct, and it's the resolved swapchain image that presents.
            // dstStage = NONE: nothing INSIDE this command buffer waits on the
            // transition -- presentation is ordered by the renderFinished
            // semaphore instead. (1.0 sync spelled this "BOTTOM_OF_PIPE"; sync2
            // deprecates TOP/BOTTOM_OF_PIPE in favor of the honest NONE.)
            barrier.image(swapchain.image(imageIndex));
            barrier.oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            barrier.newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            barrier.srcStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT);
            barrier.srcAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
            barrier.dstStageMask(VK_PIPELINE_STAGE_2_NONE);
            barrier.dstAccessMask(VK_ACCESS_2_NONE);
            vkCmdPipelineBarrier2(cmd, depInfo);

            Vk.check(vkEndCommandBuffer(cmd),
                    "Failed to record the command buffer");
        }
    }

    // ------------------------------------------------------------------
    // Synchronization
    // ------------------------------------------------------------------

    /**
     * Create the sync primitives, one set per frame in flight: an imageAvailable
     * SEMAPHORE and a FENCE (GPU->CPU; starts SIGNALED so the first wait doesn't
     * hang). Plus, per swapchain IMAGE, a renderFinished semaphore (see
     * {@link #createRenderFinishedSemaphores}).
     */
    private void createSyncObjects() {
        try (MemoryStack stack = stackPush()) {
            VkSemaphoreCreateInfo semInfo = VkSemaphoreCreateInfo.calloc(stack);
            semInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack);
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);  // start signaled

            imageAvailableSemaphores = new long[MAX_FRAMES_IN_FLIGHT];
            inFlightFences = new long[MAX_FRAMES_IN_FLIGHT];

            LongBuffer p = stack.longs(VK_NULL_HANDLE);
            for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
                Vk.check(vkCreateSemaphore(device.handle(), semInfo, null, p),
                        "Failed to create imageAvailable semaphore " + i);
                imageAvailableSemaphores[i] = p.get(0);

                Vk.check(vkCreateFence(device.handle(), fenceInfo, null, p),
                        "Failed to create in-flight fence " + i);
                inFlightFences[i] = p.get(0);
            }
        }
        createRenderFinishedSemaphores();
        System.out.println("Sync objects created (" + MAX_FRAMES_IN_FLIGHT + " frames in flight).");
    }

    /**
     * One renderFinished semaphore PER swapchain image, indexed by acquired image
     * (reuse-safe: an image isn't re-acquired until its prior present has consumed
     * the semaphore). Split out from the other sync objects because the IMAGE
     * COUNT can change when the swapchain is recreated -- these rebuild with it.
     */
    private void createRenderFinishedSemaphores() {
        try (MemoryStack stack = stackPush()) {
            VkSemaphoreCreateInfo semInfo = VkSemaphoreCreateInfo.calloc(stack);
            semInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            LongBuffer p = stack.longs(VK_NULL_HANDLE);
            renderFinishedSemaphores = new long[swapchain.imageCount()];
            for (int i = 0; i < renderFinishedSemaphores.length; i++) {
                Vk.check(vkCreateSemaphore(device.handle(), semInfo, null, p),
                        "Failed to create renderFinished semaphore " + i);
                renderFinishedSemaphores[i] = p.get(0);
            }
        }
    }

    // ------------------------------------------------------------------
    // Per-frame draw
    // ------------------------------------------------------------------

    /**
     * Render one frame in slot {@code currentFrame}: wait that slot's fence (it
     * guards the slot's command buffer + semaphore from the frame that used them
     * MAX_FRAMES_IN_FLIGHT ago), acquire an image, re-record and submit the
     * slot's command buffer, present, advance to the next slot.
     * Acquire/present results drive swapchain recreation on resize.
     */
    public void drawFrame() {
        // Wall-clock delta since the last drawFrame call -- exposed as dt() so the
        // NEXT frame's drawShapes (which runs before this) reads the previous
        // frame's duration. Measured here, once per call, before any early-return.
        long now = System.nanoTime();
        deltaSeconds = (now - lastFrameNanos) * 1e-9f;
        lastFrameNanos = now;

        try (MemoryStack stack = stackPush()) {
            // 1. Block until THIS SLOT's previous frame finished. (With an
            //    infinite timeout the only non-SUCCESS results are real errors
            //    like DEVICE_LOST -- so check, don't ignore.)
            Vk.check(vkWaitForFences(device.handle(), stack.longs(inFlightFences[currentFrame]),
                            true, NO_TIMEOUT),
                    "Failed waiting for the in-flight fence");

            // 2. Acquire the next swapchain image (GPU signals imageAvailable when
            //    it's genuinely ready to be drawn into). OUT_OF_DATE = the surface
            //    changed and this swapchain can no longer present to it AT ALL:
            //    rebuild and skip the frame. SUBOPTIMAL = it still works, just not
            //    perfectly -- render this frame, deal with it after present.
            IntBuffer pImageIndex = stack.ints(0);
            int acquired = vkAcquireNextImageKHR(device.handle(), swapchain.handle(), NO_TIMEOUT,
                    imageAvailableSemaphores[currentFrame], VK_NULL_HANDLE, pImageIndex);
            if (acquired == VK_ERROR_OUT_OF_DATE_KHR) {
                // No submit happens, so we also do NOT advance currentFrame --
                // this slot's semaphore went unused and its fence stays signaled;
                // the retry next frame reuses both safely.
                recreateSwapchain();
                return;
            }
            if (acquired != VK_SUCCESS && acquired != VK_SUBOPTIMAL_KHR) {
                throw new RuntimeException(
                        "Failed to acquire a swapchain image (VkResult " + acquired + ")");
            }
            int imageIndex = pImageIndex.get(0);

            // Reset the fence ONLY now that we know a submit will follow. The
            // classic deadlock: reset BEFORE acquire, then early-return on
            // OUT_OF_DATE -- the fence is left unsignaled, no submit ever signals
            // it, and the next drawFrame waits on it forever.
            vkResetFences(device.handle(), stack.longs(inFlightFences[currentFrame]));

            // This frame's animation clock, used by BOTH data tiers below.
            float time = (System.nanoTime() - startNanos) * 1e-9f;

            // Per-frame upload, keyed to which content seam is live (all
            // fence-guarded: the wait above means the GPU finished reading this
            // slot's resources, so rewriting them is safe).
            if (effectPipeline != null) {
                // The effect's whole input is the push block -- nothing to upload.
            } else if (shapeBatchActive()) {
                // L2 shapes: upload this frame's accumulated vertices into the
                // slot's arena (growing it first if the batch outgrew it).
                int floats = renderer2D.floatCount();
                ensureArenaCapacity(currentFrame, floats);
                shapeArenas[currentFrame].uploadFloats(renderer2D.vertexData(), floats);
                // Build this frame's per-run texture descriptor sets (one per draw
                // run -- the run that flush-on-texture-switch produced).
                prepareShapeDescriptors(currentFrame);
            }
            // A custom SceneRenderer writes its own UBO during recording
            // (frame.uniform), not here -- nothing to upload for it.

            // Re-record this slot's command buffer for the image we just got
            // (safe for the same fence reason).
            VkCommandBuffer cmd = commandBuffers[currentFrame];
            Vk.check(vkResetCommandBuffer(cmd, 0), "Failed to reset the command buffer");
            recordCommandBuffer(cmd, imageIndex, time);

            // 3. Submit that image's command buffer, via synchronization2's
            //    vkQueueSubmit2. Every participant gets its own little info struct,
            //    and each SEMAPHORE carries a PER-SEMAPHORE stage mask -- 1.0's
            //    fragile parallel arrays (pWaitSemaphores + pWaitDstStageMask,
            //    matched by index) are gone, and the counts are inferred.

            // Wait for imageAvailable, but only block the COLOR_ATTACHMENT_OUTPUT
            // stage: work in earlier stages may start before the image is acquired.
            VkSemaphoreSubmitInfo.Buffer waitInfo = VkSemaphoreSubmitInfo.calloc(1, stack);
            waitInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO);
            waitInfo.semaphore(imageAvailableSemaphores[currentFrame]);
            waitInfo.stageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT);

            VkCommandBufferSubmitInfo.Buffer cmdInfo = VkCommandBufferSubmitInfo.calloc(1, stack);
            cmdInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO);
            cmdInfo.commandBuffer(cmd);

            // Signal renderFinished only once EVERYTHING in the submit completed
            // (ALL_COMMANDS) -- presentation must not start before the final
            // layout transition (barrier 2) has executed.
            VkSemaphoreSubmitInfo.Buffer signalInfo = VkSemaphoreSubmitInfo.calloc(1, stack);
            signalInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO);
            signalInfo.semaphore(renderFinishedSemaphores[imageIndex]);
            signalInfo.stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);

            VkSubmitInfo2.Buffer submitInfo = VkSubmitInfo2.calloc(1, stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO_2);
            submitInfo.pWaitSemaphoreInfos(waitInfo);
            submitInfo.pCommandBufferInfos(cmdInfo);
            submitInfo.pSignalSemaphoreInfos(signalInfo);

            Vk.check(vkQueueSubmit2(device.graphicsQueue(), submitInfo, inFlightFences[currentFrame]),
                    "Failed to submit the draw command buffer");

            // 4. Present: hand the finished image back to the swapchain to display,
            //    once renderFinished is signaled.
            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack);
            presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);
            presentInfo.pWaitSemaphores(stack.longs(renderFinishedSemaphores[imageIndex]));
            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(stack.longs(swapchain.handle()));
            presentInfo.pImageIndices(pImageIndex);

            int presented = vkQueuePresentKHR(device.presentQueue(), presentInfo);

            // 5. React to "the surface changed". At present-time BOTH out-of-date
            //    and suboptimal trigger a rebuild (the frame already went out; now
            //    is the cheap moment to fix the mismatch). The window's resize
            //    flag is checked too because some drivers keep presenting happily
            //    while the window stretches -- without the flag we'd render
            //    forever at the old size.
            boolean resized = window.consumeFramebufferResized();
            if (presented == VK_ERROR_OUT_OF_DATE_KHR
                    || presented == VK_SUBOPTIMAL_KHR
                    || resized) {
                recreateSwapchain();
            } else {
                Vk.check(presented, "Failed to present the swapchain image");
            }

            // 6. Move to the next frame-in-flight slot (a submit DID happen on
            //    this one, so its fence will signal and free it for reuse).
            currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;
        }
    }

    // ------------------------------------------------------------------
    // Swapchain recreation (resize)
    // ------------------------------------------------------------------

    /**
     * Rebuild everything that depends on the swapchain, against the surface's
     * CURRENT state. The swapchain itself renegotiates extent/format from the
     * live surface; the pre-recorded command buffers bake in image handles,
     * views and the extent, so they must be re-recorded; the per-IMAGE
     * renderFinished semaphores rebuild because the image count can change.
     *
     * Strategy: brute-force-but-correct -- wait for the GPU to go fully idle,
     * destroy, recreate. (The no-stall version hands oldSwapchain to the new
     * chain and retires the old one asynchronously; that seam stays documented
     * in Swapchain for later.)
     */
    private void recreateSwapchain() {
        // A minimized window has a 0x0 framebuffer, and a 0x0 swapchain is
        // illegal -- sleep on events until we're restored to a real size.
        try (MemoryStack stack = stackPush()) {
            IntBuffer w = stack.ints(0);
            IntBuffer h = stack.ints(0);
            window.framebufferSize(w, h);
            while (w.get(0) == 0 || h.get(0) == 0) {
                window.waitEvents();
                window.framebufferSize(w, h);
            }
        }

        waitIdle();

        // Tear down the swapchain-dependent slice, in reverse order...
        destroyRenderFinishedSemaphores();
        int oldFormat = swapchain.imageFormat();
        swapchain.close();

        // ...and rebuild it. Only the per-IMAGE pieces depend on the swapchain
        // now: since command buffers are re-recorded every frame, they pick up
        // the new images/extent automatically -- nothing to rebuild there. The
        // pool and per-frame sync objects survive untouched.
        swapchain = new Swapchain(device, surface, window, vsync, msaaRequested);
        createRenderFinishedSemaphores();

        // Pipelines bake the attachment FORMAT (not the extent -- viewport is
        // dynamic), and the rebuilt swapchain renegotiated it. Same format (the
        // overwhelmingly common case): keep them. Changed (e.g. window dragged to a
        // monitor with different surface support): rebake the engine-owned ones.
        // (User-created custom pipelines also baked the format; a rebuild hook for
        // those is the catalogued later refinement -- see createPipeline.)
        if (swapchain.imageFormat() != oldFormat) {
            if (effect != null) {
                buildEffectPipeline();
            }
            if (renderer2D != null) {
                buildShapePipeline();
            }
        }

        // Drop any resize flag raised by the burst of events we just handled --
        // otherwise one drag rebuilds the swapchain several times at the same
        // size. (If a REAL resize sneaks in right here, the driver's OUT_OF_DATE
        // on the next acquire/present catches it.)
        window.consumeFramebufferResized();
        System.out.println("Swapchain recreated.");
    }

    private void destroyRenderFinishedSemaphores() {
        for (long s : renderFinishedSemaphores) {
            vkDestroySemaphore(device.handle(), s, null);
        }
    }

    /** Seconds elapsed since the renderer was created -- a live animation clock
     *  for L2 (read it any time; it is always current). */
    public float time() {
        return (System.nanoTime() - startNanos) * 1e-9f;
    }

    /** Duration of the previous frame in seconds. Multiply per-frame movement by
     *  this for frame-rate-independent animation (distance = speed * dt()). */
    public float dt() {
        return deltaSeconds;
    }

    /** Block until the GPU is fully idle -- call before tearing anything down. */
    public void waitIdle() {
        vkDeviceWaitIdle(device.handle());
    }

    // ------------------------------------------------------------------
    // Cleanup -- reverse order of creation.
    // ------------------------------------------------------------------
    public void close() {
        // Sync primitives (per-frame sets + the per-image semaphores).
        if (imageAvailableSemaphores != null) {
            for (long s : imageAvailableSemaphores) {
                vkDestroySemaphore(device.handle(), s, null);
            }
        }
        if (renderFinishedSemaphores != null) {
            destroyRenderFinishedSemaphores();
        }
        if (inFlightFences != null) {
            for (long f : inFlightFences) {
                vkDestroyFence(device.handle(), f, null);
            }
        }
        // Destroying the command pool also frees the command buffers from it.
        if (commandPool != VK_NULL_HANDLE) {
            vkDestroyCommandPool(device.handle(), commandPool, null);
        }
        if (effectPipeline != null) {
            effectPipeline.close();
        }
        if (shapePipeline != null) {
            shapePipeline.close();
        }
        if (shapeArenas != null) {
            for (Buffer arena : shapeArenas) {
                if (arena != null) {
                    arena.close();
                }
            }
        }
        // The shape texture descriptors (each pool frees its sets) + the default.
        if (shapeDescriptorPools != null) {
            for (long pool : shapeDescriptorPools) {
                if (pool != VK_NULL_HANDLE) {
                    vkDestroyDescriptorPool(device.handle(), pool, null);
                }
            }
        }
        if (defaultWhiteTexture != null) {
            defaultWhiteTexture.close();
        }
        if (defaultFont != null) {
            defaultFont.close();
        }
        // Swapchain.close() destroys our image views, then the swapchain (which
        // frees the images it owns). The swapchain was created FROM the device,
        // so it goes before the device.
        swapchain.close();
        device.close();
    }
}
