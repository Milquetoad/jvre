package jvre.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes how a user-defined pipeline reads a vertex buffer: the per-vertex
 * STRIDE plus the ATTRIBUTES (each a shader {@code location}, a {@link
 * AttribFormat}, and a byte offset into the vertex). The L1 escape hatch's vertex
 * half -- the user states their own interleaved layout, jvre translates it to the
 * Vulkan binding/attribute descriptions.
 *
 * <pre>{@code
 * // [x y | r g b], 5 floats/vertex:
 * VertexLayout layout = VertexLayout.builder(5 * Float.BYTES)
 *     .attribute(0, AttribFormat.VEC2, 0)
 *     .attribute(1, AttribFormat.VEC3, 2 * Float.BYTES)
 *     .build();
 * }</pre>
 */
public final class VertexLayout {

    /** One attribute: a shader location reading {@code format} at {@code offset}. */
    static final class Attribute {
        final int location;
        final AttribFormat format;
        final int offset;

        Attribute(int location, AttribFormat format, int offset) {
            this.location = location;
            this.format = format;
            this.offset = offset;
        }
    }

    private final int stride;
    private final List<Attribute> attributes;

    private VertexLayout(int stride, List<Attribute> attributes) {
        this.stride = stride;
        this.attributes = attributes;
    }

    /** Bytes per vertex. */
    public int stride() {
        return stride;
    }

    List<Attribute> attributes() {
        return attributes;
    }

    /** Start a layout whose vertices are {@code strideBytes} apart. */
    public static Builder builder(int strideBytes) {
        return new Builder(strideBytes);
    }

    public static final class Builder {
        private final int stride;
        private final List<Attribute> attrs = new ArrayList<>();

        Builder(int stride) {
            this.stride = stride;
        }

        /** Add an attribute: shader {@code location}, {@code format}, byte {@code offset} into the vertex. */
        public Builder attribute(int location, AttribFormat format, int offsetBytes) {
            attrs.add(new Attribute(location, format, offsetBytes));
            return this;
        }

        public VertexLayout build() {
            if (attrs.isEmpty()) {
                throw new IllegalStateException("VertexLayout needs at least one attribute");
            }
            return new VertexLayout(stride, List.copyOf(attrs));
        }
    }
}
