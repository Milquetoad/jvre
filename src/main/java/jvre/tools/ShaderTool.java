package jvre.tools;

import jvre.core.ShaderCompiler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Build-time shader compiler: GLSL -> SPIR-V via jvre's own {@link ShaderCompiler}
 * (the bundled shaderc library), NOT the external {@code glslc} executable. This
 * is what makes the build SELF-CONTAINED -- it compiles on any machine, on CI, and
 * on JitPack without the Vulkan SDK installed. (glslc IS shaderc under the hood,
 * so the emitted SPIR-V is equivalent; we just stop shelling out to the SDK.)
 *
 * Invoked by the {@code compileShaders} Gradle task, never at runtime -- it is
 * excluded from the published jar. Args: {@code <srcDir> <outDir>}; each
 * {@code *.vert}/{@code *.frag} becomes {@code <name>.spv} in outDir.
 */
public final class ShaderTool {

    private ShaderTool() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: ShaderTool <srcDir> <outDir>");
            System.exit(2);
        }
        Path srcDir = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        Files.createDirectories(outDir);

        int[] count = {0};
        try (Stream<Path> files = Files.list(srcDir)) {
            files.filter(ShaderTool::isShader).sorted().forEach(src -> {
                compileOne(src, outDir);
                count[0]++;
            });
        }
        System.out.println("shaderc: compiled " + count[0] + " shader(s) -> " + outDir);
    }

    /** Only vertex + fragment stages -- the ones ShaderCompiler exposes. A
     *  {@code .comp} would need a compute path on ShaderCompiler first. */
    private static boolean isShader(Path p) {
        String name = p.getFileName().toString();
        return name.endsWith(".vert") || name.endsWith(".frag");
    }

    private static void compileOne(Path src, Path outDir) {
        String name = src.getFileName().toString();
        try {
            String source = Files.readString(src);
            byte[] spirv = name.endsWith(".vert")
                    ? ShaderCompiler.compileVertex(source, name)
                    : ShaderCompiler.compileFragment(source, name);
            Path dst = outDir.resolve(name + ".spv");
            Files.write(dst, spirv);
            System.out.println("  " + name + " -> " + dst.getFileName() + " (" + spirv.length + " bytes)");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading/writing shader " + name, e);
        }
    }
}
