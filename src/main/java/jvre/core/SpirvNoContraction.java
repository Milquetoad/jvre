package jvre.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A tiny SPIR-V binary pass that FORBIDS floating-point contraction: it adds the
 * {@code NoContraction} decoration to every arithmetic instruction, so a driver may
 * not fuse a multiply + add into a single fused-multiply-add (FMA).
 *
 * <h2>Why jvre forces this on user shaders</h2>
 * Contraction changes intermediate rounding (an FMA keeps full precision on the
 * product where {@code a*b} then {@code +c} would round twice). It's invisible most
 * of the time, but procedural NOISE -- the Shadertoy staple, built on {@code fract()}
 * / {@code floor()} of large dot products and polynomials -- depends on EXACT,
 * per-operation IEEE results. Contraction there produces visible stepping / banding /
 * garbage, worst at large input magnitudes. A user hit exactly this.
 *
 * <p>GLSL's only lever is the per-variable {@code precise} qualifier, and shaderc
 * exposes no compile option for it. Rather than make users sprinkle {@code precise}
 * everywhere (and since jvre deliberately does not inject a preamble that would shift
 * their line numbers), jvre decorates the COMPILED module itself -- the SPIR-V
 * equivalent of marking everything precise.
 *
 * <h2>How</h2>
 * SPIR-V is a flat stream of 32-bit words: a 5-word header then instructions, each
 * led by a word packing {@code (wordCount << 16) | opcode}. {@code NoContraction}
 * decorations live in the ANNOTATIONS section (after debug names, before the type
 * declarations) and may FORWARD-REFERENCE result ids defined later inside functions
 * -- which is exactly how glslang lowers {@code precise}, so it is valid SPIR-V. The
 * pass: (1) walk every instruction, collecting the result id of each contractable
 * arithmetic op and the ids already carrying NoContraction; (2) splice one
 * {@code OpDecorate <id> NoContraction} per undecorated result id in at the end of
 * the annotations section. No new ids are created, so the id bound is unchanged.
 */
final class SpirvNoContraction {

    private static final int MAGIC = 0x07230203;
    private static final int OP_DECORATE = 71;
    private static final int DECORATION_NO_CONTRACTION = 42;

    /** Arithmetic ops a driver may contract (the FMA-fusable set). Their result is
     *  the 2nd operand word (word0 = len|opcode, word1 = result type, word2 = id). */
    private static final Set<Integer> CONTRACTABLE = Set.of(
            127,  // OpFNegate
            129,  // OpFAdd
            131,  // OpFSub
            133,  // OpFMul
            142,  // OpVectorTimesScalar
            143,  // OpMatrixTimesScalar
            144,  // OpVectorTimesMatrix
            145,  // OpMatrixTimesVector
            146,  // OpMatrixTimesMatrix
            148); // OpDot

    /** Opcodes that legally precede the type-declaration section (header bits, debug
     *  names, annotations). The first instruction NOT in this set begins the types
     *  section -- our insertion point (the end of annotations). */
    private static final Set<Integer> PRE_TYPE = Set.of(
            2, 3, 4, 5, 6, 7, 8,          // SourceContinued, Source, SourceExtension, Name, MemberName, String, Line
            10, 11, 14, 15, 16, 17,       // Extension, ExtInstImport, MemoryModel, EntryPoint, ExecutionMode, Capability
            71, 72, 73, 74, 75,           // Decorate, MemberDecorate, DecorationGroup, GroupDecorate, GroupMemberDecorate
            317, 330, 331, 332,           // NoLine, ModuleProcessed, ExecutionModeId, DecorateId
            5632, 5633);                  // DecorateString(GOOGLE), MemberDecorateString(GOOGLE)

    private SpirvNoContraction() {}

    /**
     * Return {@code spirv} with NoContraction added to every contractable arithmetic
     * instruction. Idempotent (ids already decorated are skipped) and conservative:
     * anything that does not look like a well-formed SPIR-V module is returned
     * unchanged rather than risk corrupting it (a bad compile would already have
     * thrown upstream).
     */
    static byte[] decorate(byte[] spirv) {
        if (spirv.length < 20 || spirv.length % 4 != 0) {
            return spirv;   // too small to be a module / not word-aligned
        }
        // SPIR-V carries its endianness in the magic word; honor it both ways.
        ByteBuffer bb = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.getInt(0) != MAGIC) {
            bb.order(ByteOrder.BIG_ENDIAN);
            if (bb.getInt(0) != MAGIC) {
                return spirv;   // not SPIR-V
            }
        }
        ByteOrder order = bb.order();
        int n = spirv.length / 4;
        int[] words = new int[n];
        for (int i = 0; i < n; i++) {
            words[i] = bb.getInt(i * 4);
        }

        Set<Integer> toDecorate = new LinkedHashSet<>();   // arithmetic result ids (insertion order)
        Set<Integer> alreadyDone = new HashSet<>();        // ids already NoContraction
        int typesStart = -1;

        int i = 5;   // skip the 5-word header
        while (i < n) {
            int wordCount = (words[i] >>> 16) & 0xFFFF;
            int opcode = words[i] & 0xFFFF;
            if (wordCount == 0) {
                return spirv;   // malformed instruction stream; don't touch it
            }
            if (typesStart < 0 && !PRE_TYPE.contains(opcode)) {
                typesStart = i;   // first non-prefix op == end of the annotations section
            }
            if (opcode == OP_DECORATE && wordCount >= 3 && words[i + 2] == DECORATION_NO_CONTRACTION) {
                alreadyDone.add(words[i + 1]);
            } else if (CONTRACTABLE.contains(opcode) && wordCount >= 3) {
                toDecorate.add(words[i + 2]);   // word2 = result id
            }
            i += wordCount;
        }
        if (typesStart < 0) {
            typesStart = n;   // degenerate module with no types/functions; nothing to do anyway
        }
        toDecorate.removeAll(alreadyDone);
        if (toDecorate.isEmpty()) {
            return spirv;   // nothing contractable, or all already decorated
        }

        // Splice the new decorations in at the end of the annotations section
        // (just before the type declarations). Each is 3 words; no new ids.
        int[] out = new int[n + toDecorate.size() * 3];
        System.arraycopy(words, 0, out, 0, typesStart);
        int w = typesStart;
        for (int id : toDecorate) {
            out[w++] = (3 << 16) | OP_DECORATE;
            out[w++] = id;
            out[w++] = DECORATION_NO_CONTRACTION;
        }
        System.arraycopy(words, typesStart, out, w, n - typesStart);

        ByteBuffer outBuf = ByteBuffer.allocate(out.length * 4).order(order);
        for (int word : out) {
            outBuf.putInt(word);
        }
        return outBuf.array();
    }

    /** Count the {@code OpDecorate <id> NoContraction} instructions in a module --
     *  for verification (tests). Returns 0 for a non-module. */
    static int count(byte[] spirv) {
        if (spirv.length < 20 || spirv.length % 4 != 0) {
            return 0;
        }
        ByteBuffer bb = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.getInt(0) != MAGIC) {
            bb.order(ByteOrder.BIG_ENDIAN);
            if (bb.getInt(0) != MAGIC) {
                return 0;
            }
        }
        int n = spirv.length / 4;
        int count = 0;
        int i = 5;
        while (i < n) {
            int wordCount = (bb.getInt(i * 4) >>> 16) & 0xFFFF;
            int opcode = bb.getInt(i * 4) & 0xFFFF;
            if (wordCount == 0) {
                break;
            }
            if (opcode == OP_DECORATE && wordCount >= 3
                    && bb.getInt((i + 2) * 4) == DECORATION_NO_CONTRACTION) {
                count++;
            }
            i += wordCount;
        }
        return count;
    }
}
