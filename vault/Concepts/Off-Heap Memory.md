# Off-Heap Memory

Memory allocated **outside** the JVM-managed heap — like what C gets from `malloc`. The garbage collector never scans it, never moves it, and never frees it; **you** manage its lifetime (or it leaks).

## JVM heap (for contrast)
Everything made with `new` lives on the **heap**, managed by the **garbage collector**. To stay tidy, GCs may **physically move** objects (compaction) — so a Java object's memory address is **not stable**.

## Why Vulkan forces us off-heap
Vulkan is a C API, so:
1. **Stable address** — C functions take *pointers*. A GC-moved object would invalidate the pointer mid-call. Off-heap memory stays put.
2. **Exact byte layout** — a C struct is precise bytes at precise offsets (the ABI). Java objects carry headers + JVM layout. So LWJGL writes each field into off-heap memory at the offset C expects — e.g. `appInfo.sType(...)` pokes bytes off-heap, *not* a normal Java field.

Cheaply managing the **short-lived** case: [[MemoryStack]]. The bridge that consumes this memory: [[JNI]] / [[LWJGL]].

#concept #memory
