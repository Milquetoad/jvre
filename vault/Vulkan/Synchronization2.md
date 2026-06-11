# Synchronization2

**Synchronization2** (`VK_KHR_synchronization2`, **core since Vulkan 1.3**) is the redesigned API for [[Pipeline Barriers|barriers]] and queue submission. Identical *concepts* to 1.0 sync -- stages, accesses, layout transitions, semaphores -- with the ergonomics fixed. jvre migrated on 2026-06-11; it's the natural companion to [[Dynamic Rendering]] (both are the "modern 1.3 defaults").

## What 1.0 sync got wrong

- **Stage masks were per-CALL**: `vkCmdPipelineBarrier(cmd, srcStage, dstStage, ...)` applied ONE src/dst stage pair to *every* barrier in the call -- batching unrelated barriers meant over-synchronizing to the union.
- **Submission used parallel arrays**: `VkSubmitInfo.pWaitSemaphores[i]` paired with `pWaitDstStageMask[i]` *by index* -- easy to misalign, and counts had to be set by hand.
- **Pseudo-stages**: "wait at `BOTTOM_OF_PIPE` with access 0" was the idiom for "nothing in this buffer waits" -- correct but cryptic.
- **32-bit flags were running out of bits** for new stages/accesses.

## What sync2 does instead

| 1.0 sync | synchronization2 |
|---|---|
| `vkCmdPipelineBarrier(cmd, srcStage, dstStage, ..., barriers)` | `vkCmdPipelineBarrier2(cmd, VkDependencyInfo)` -- one bundle struct pointing at the barrier arrays |
| `VkImageMemoryBarrier` (stages live on the call) | `VkImageMemoryBarrier2` -- **each barrier carries its own** `srcStageMask`/`srcAccessMask`/`dstStageMask`/`dstAccessMask` |
| `vkQueueSubmit` + `VkSubmitInfo` parallel arrays | `vkQueueSubmit2` + `VkSubmitInfo2` -- each semaphore is a `VkSemaphoreSubmitInfo` **carrying its own stage mask**; command buffers are `VkCommandBufferSubmitInfo`; counts inferred |
| `TOP_OF_PIPE` / `BOTTOM_OF_PIPE` | deprecated in favor of the honest **`VK_PIPELINE_STAGE_2_NONE`** / `ALL_COMMANDS` |
| 32-bit `VkPipelineStageFlags` / `VkAccessFlags` | 64-bit `VkPipelineStageFlags2` / `VkAccessFlags2` (`long` in Java, same constant names with `_2_`) |

`vkQueuePresentKHR` has **no sync2 variant** -- presentation isn't a pipeline operation; it's ordered by the `renderFinished` semaphore as before.

## In jvre

- **Enabled** in `Device` together with `dynamicRendering`, via the aggregate **`VkPhysicalDeviceVulkan13Features`** struct (each core release ships one struct holding all its promoted feature toggles -- chain it once into `VkDeviceCreateInfo.pNext` instead of one struct per feature). Support is *verified during device selection* (`checkApiAndFeatureSupport`), not assumed.
- **Barriers** (`Main.createCommandBuffers`): one reusable `VkImageMemoryBarrier2` + one `VkDependencyInfo` that points at it; the struct is retargeted (image/layouts/stages) between the two `vkCmdPipelineBarrier2` calls. Barrier 2's old `dst = BOTTOM_OF_PIPE` became `dstStageMask = NONE` -- nothing *inside* the command buffer waits on the present transition; the semaphore orders presentation.
- **Submit** (`Main.drawFrame`): `vkQueueSubmit2`; the wait on `imageAvailable` blocks only the `COLOR_ATTACHMENT_OUTPUT_2` stage (same as before, but the mask now sits ON the semaphore info), and `renderFinished` signals at `ALL_COMMANDS` (everything, including the final layout transition, must be done before present).

Verified on the RTX 4090: validation-clean **with synchronization validation enabled** (see [[Validation Layer and Debug Messenger]]).

## See also
- [[Pipeline Barriers]] -- the concepts; this note is just their modern spelling.
- [[Synchronization and the Render Loop]] -- semaphores/fences and the per-frame flow.

#vulkan #sync #concept
