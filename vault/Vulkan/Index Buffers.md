# Index Buffers

jvre's quad (2026-06-12): geometry described as **unique vertices + indices into them**, drawn with `vkCmdDrawIndexed`. The spinning triangle became a spinning four-color square.

![[spinning-quad.png]]

## Why

A quad is two triangles = 6 vertices, but only **4 are unique** -- the diagonal pair is shared. Without indexing you'd duplicate those vertices (and the duplication explodes with mesh size: in a typical mesh every vertex is shared by ~6 triangles, so indexing roughly **halves** vertex memory and bandwidth, and lets the GPU's post-transform cache skip re-running the vertex shader for a recently-seen index).

```
vertices: 0 top-left(R)  1 top-right(G)  2 bottom-right(B)  3 bottom-left(W)
indices:  0,1,2   2,3,0      <- two triangles sharing the 0-2 diagonal
```

## The mechanics

- The index data is just another [[Vertex Buffers and GPU Memory|Buffer]] -- `VK_BUFFER_USAGE_INDEX_BUFFER_BIT`, staged to DEVICE_LOCAL like the vertices. `Buffer` grew `uploadShorts` + a `short[]` overload of `deviceLocal`.
- **Index width is declared at bind time**: `vkCmdBindIndexBuffer(cmd, buffer, offset, VK_INDEX_TYPE_UINT16)` -- UINT16 addresses up to 65k vertices; UINT32 beyond that. One index buffer per draw (unlike the vertex-buffer *array* bind).
- `vkCmdDrawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance)` -- `vertexOffset` is added to every index, which is how many meshes can share one big vertex buffer.

## Two things the quad showed

- **The diagonal "seam" is an illusion.** Along a shared edge, barycentric interpolation depends only on the edge's two endpoint colors, so both triangles agree *exactly* -- what the eye picks out is the abrupt change in gradient *direction* across the edge (a Mach-band effect), not a color mismatch.
- **The best-practices layer filed the VMA ticket**: with four small allocations at startup (2 staging + 2 device-local) it now warns that tiny buffers "should be sub-allocated from larger memory blocks" (threshold 1 MiB). Known, documented in `Buffer`'s javadoc -- it's the standing cue for the VMA milestone, not a bug.

## See also
- [[Vertex Buffers and GPU Memory]] -- the storage these indices point into.
- [[Graphics Pipeline]] -- unchanged by indexing; input assembly consumes indexed or raw vertices the same way.

#vulkan #buffers #concept
