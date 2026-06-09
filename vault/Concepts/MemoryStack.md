# MemoryStack

LWJGL's tool for cheap, automatic, leak-free allocation of **short-lived** C structs in [[Off-Heap Memory]]. Mirrors how C allocates local variables on the call stack.

## What it actually is
A **real** block of off-heap memory (not virtual/simulated), pre-allocated per-thread (~64 KB) once up front. What's "virtual" is only the *discipline* layered on top: it's handed out as a **stack (LIFO)**.

## Mechanism
- A single pointer marks "how far used."
- `stack.malloc/calloc...` just **bumps the pointer** forward and returns that slice — no OS `malloc`, basically free.
- Resetting the pointer reclaims **everything at once** — no per-object free.
- Scope is marked with **try-with-resources**:

```java
try (MemoryStack stack = MemoryStack.stackPush()) {     // remember pointer position
    VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack);
    appInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO);
    // ... pass appInfo's address to vkCreateInstance ...
}   // close() resets the pointer → all of it reclaimed at once
```

Analogy: the `try` block is a "function scope," `stackPush()` is entry, the closing `}` is the "return" that wipes the locals.

## ⚠️ Real-world gotcha: the 64 KB ceiling (we hit this)
The per-thread auto stack defaults to **64 KB** — and that's a hard limit. Constructing `new VkInstance(...)` makes LWJGL enumerate **every extension on every GPU** *on this stack*. This machine's two GPUs (RTX 4090 + AMD) expose enough extensions (~260 bytes each, hundreds total) to overflow 64 KB →
```
java.lang.OutOfMemoryError: Out of stack space.
    at org.lwjgl.vulkan.VkInstance.getAvailableDeviceExtensions(...)
```
**Fix:** raise the size *before any stack is used*, at the top of `main`:
```java
Configuration.STACK_SIZE.set(512);   // KB; default 64
```
Lesson: the stack is for *short-lived, small* allocations. Big enumerations need a bigger ceiling (or a different strategy). See [[Vulkan Instance]].

## Stack vs heap — a recurring design question
Use the stack **only** for data that does *not* outlive the `try` block. Long-lived handles (the `VkInstance`, persistent buffers) use explicit `malloc`/`memAlloc` + explicit free. Same "stack or heap?" question a C programmer asks.

#concept #memory
