# Off-Heap Memory

Memory allocated **outside** the JVM-managed heap — like what C gets from `malloc`. The garbage collector never scans it, never moves it, and never frees it; **you** manage its lifetime (or it leaks).

## JVM heap (for contrast)
Everything made with `new` lives on the **heap**, managed by the **garbage collector**. To stay tidy, GCs may **physically move** objects (compaction) — so a Java object's memory address is **not stable**.

## Why Vulkan forces us off-heap
Vulkan is a C API, so:
1. **Stable address** — C functions take *pointers*. A GC-moved object would invalidate the pointer mid-call. Off-heap memory stays put.
2. **Exact byte layout** — a C struct is precise bytes at precise offsets (the ABI). Java objects carry headers + JVM layout. So LWJGL writes each field into off-heap memory at the offset C expects — e.g. `appInfo.sType(...)` pokes bytes off-heap, *not* a normal Java field.

Cheaply managing the **short-lived** case: [[MemoryStack]]. The bridge that consumes this memory: [[JNI]] / [[LWJGL]].

## Gotcha: a `ByteBuffer`'s POSITION can change which address gets freed
A native pointer in LWJGL is a `ByteBuffer` wrapping an off-heap address. But a Java buffer also has a **position** (where the next relative `get`/`put` lands), and some LWJGL calls compute the C pointer as **base + position**, not the base:
- `memAddress(buffer)` -> base **+ position** (position-sensitive)
- `memAddress0(buffer)` -> the **base** address only

So a free's behavior depends on *which* it uses. `MemoryUtil.memFree` uses `memAddress0` (base) -> position-safe. But `STBImage.stbi_image_free(buffer)` uses `memAddress` (base + position). **Bug hit 2026-06-16:** after `decoded.get(pixels)` advanced the decoded image buffer's position to the end, `stbi_image_free(decoded)` freed `base + length` instead of `base` -> native **heap corruption** (`0xC0000374 STATUS_HEAP_CORRUPTION`, a hard JVM crash, no Java exception). Fix: `decoded.rewind()` (reset position to 0) before freeing. Lesson: before handing a relative-read buffer back to a native free/use that takes `memAddress`, **reset its position** (`rewind`/`clear`/`position(0)`); relative `get`/`put` mutate position as a side effect.

#concept #memory
