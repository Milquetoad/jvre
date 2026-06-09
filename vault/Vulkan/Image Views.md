# Image Views

A **`VkImageView`** is a "lens" onto a **`VkImage`**: it describes *how to interpret* an image so the GPU can use it. Key Vulkan rule — **you almost never use a `VkImage` directly.** An image is just memory + metadata (size, format, layout); a view says which slice and in what shape. The [[Roadmap - Clear to Color|render pass and framebuffer]] reference **views, not images**, which is the whole reason this step exists.

## What a view specifies (`VkImageViewCreateInfo`)
- **image** — the `VkImage` being wrapped (here, one of the [[Swapchain|swapchain]] images).
- **viewType** — `2D` for us (also 1D / 3D / cubemap / array).
- **format** — the swapchain format we stashed at swapchain creation.
- **components** — channel swizzle (e.g. route R into all channels for grayscale). We use **identity** (R->R, G->G, B->B, A->A): no remap.
- **subresourceRange** — *which part* of the image:
  - **aspectMask** = `COLOR` (vs depth / stencil),
  - **baseMipLevel / levelCount** = 0 / 1 (swapchain images have no mipmaps),
  - **baseArrayLayer / layerCount** = 0 / 1 (one layer; >1 would be stereoscopic / array images).

## One per swapchain image
We create exactly **one view per swapchain image**, all identical except the image each wraps — so it's a simple loop. WE own these (`vkCreateImageView` / `vkDestroyImageView`), unlike the images themselves which the swapchain owns.

## Cleanup order
Views reference the swapchain's images, so destroy **all image views before the swapchain** (which is before the device, before the instance objects). Child before parent.

First run on craptop: `Created 3 image views` (matching the 3 swapchain images), clean. Next: the **render pass** — which describes the attachments (these views) and, with `loadOp = CLEAR`, is literally **what clears the screen to a color**.

#vulkan #image-views #presentation
