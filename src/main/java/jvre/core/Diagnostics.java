package jvre.core;

import org.lwjgl.Version;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * jvre's diagnostics log -- the answer to "a Ring 2 fault happened on someone
 * else's machine; what do we have to go on?"
 *
 * A Ring 2 fault (a hand-rolled-Vulkan bug: a sync hazard, a wrong format, a
 * lost device) is environment-dependent BY DEFINITION -- it didn't happen on the
 * author's GPU but did on theirs. So the single most valuable thing in a bug
 * report is not a per-frame trace; it is the ENVIRONMENT FINGERPRINT: which GPU,
 * which driver, which queue layout (the never-tested CONCURRENT branch!), which
 * formats. This class makes that fingerprint land in a file the user can send.
 *
 * Two design decisions worth stating outright:
 *
 *  1. STATIC SINGLETON -- deliberately, and the one place it earns its keep.
 *     {@link Vk#check} is static and called from everywhere; the validation
 *     debug callback is static. Logging is the textbook CROSS-CUTTING concern
 *     reachable from static contexts, so a global sink is the standard (every
 *     logging framework is effectively one). Threading an instance through every
 *     call site would be pure noise.
 *
 *  2. We TEE System.out/System.err into the log rather than rerouting call
 *     sites. Installing a tee once (here) captures every existing
 *     System.out.println in jvre -- the GPU line, the swapchain line, validation
 *     messages -- with zero changes elsewhere. The original console streams are
 *     preserved and still receive everything; the file is just a second
 *     destination. (Decorator pattern over OutputStream -- see {@link Tee}.)
 *
 * The cardinal rule: eager + flushed. The fingerprint is written at startup and
 * flushed immediately, because it is most valuable exactly when the process dies
 * hardest -- a driver SIGSEGV three seconds later must still find it on disk.
 * autoflush=true on the PrintStreams flushes after every println, so the last
 * line before a hard crash survives.
 *
 * Diagnostics must NEVER take down the app it is diagnosing: every failure in
 * here degrades to "no log file," never an exception.
 */
public final class Diagnostics {

    // Where bug reports go -- printed on a fault so the user can one-click file
    // one (frictionless MANUAL send; true auto-upload is a consent/infra
    // decision deliberately deferred).
    private static final String ISSUE_URL = "https://github.com/Milquetoad/jvre/issues/new";

    private static Path logPath;
    private static OutputStream fileOut;   // the one shared file both tees write into
    private static boolean started;

    private Diagnostics() {}  // static utility; no instances

    /**
     * Open the per-OS log file and start capturing. Call ONCE, as early as
     * possible (before any Vulkan object exists) so the header is on disk before
     * anything can crash. Idempotent and failure-tolerant.
     */
    public static synchronized void init(String appName) {
        if (started) {
            return;
        }
        started = true;

        try {
            Path dir = logDirectory();
            Files.createDirectories(dir);
            logPath = dir.resolve("jvre.log");
            rollPrevious(logPath);   // keep the prior run as jvre.log.1
            fileOut = new FileOutputStream(logPath.toFile(), false);

            // Tee both std streams into the one file. autoflush=true => flush on
            // every println (the crash-durability guarantee).
            System.setOut(new PrintStream(new Tee(System.out, fileOut), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new Tee(System.err, fileOut), true, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            // No log is a degraded mode, not a fatal one. Say so on the console
            // (still the real one here) and carry on without a file.
            System.err.println("[diagnostics] could not open a log file: " + e
                    + " -- continuing without one.");
            fileOut = null;
            logPath = null;
            return;
        }

        writeHeader(appName);

        // Belt-and-suspenders flush on a GRACEFUL exit. (Per-line autoflush
        // already covers hard crashes, which may bypass shutdown hooks entirely.)
        Runtime.getRuntime().addShutdownHook(new Thread(Diagnostics::close, "jvre-diag-flush"));
    }

    /** The log file's path (null if the file could not be opened). */
    public static Path logPath() {
        return logPath;
    }

    /**
     * Report a fatal error to the user in actionable terms: the stack trace
     * (captured into the log via System.err), then WHERE the log is and HOW to
     * send it. This is the whole point -- turning "it crashed" into "here is the
     * file to attach."
     */
    public static void reportFault(Throwable t) {
        System.err.println();
        System.err.println("[jvre] FATAL: " + t);
        t.printStackTrace();   // -> System.err -> captured in the log too
        if (logPath != null) {
            System.err.println();
            System.err.println("A diagnostics log was written to:");
            System.err.println("    " + logPath);
            System.err.println("Please attach it to a report (if the JVM crashed hard, also");
            System.err.println("attach any hs_err_pid*.log it left in the working directory):");
            System.err.println("    " + ISSUE_URL);
        }
    }

    /** Flush and close the file. Idempotent; safe from the shutdown hook. */
    public static synchronized void close() {
        if (fileOut == null) {
            return;
        }
        try {
            System.out.flush();
            System.err.flush();
            fileOut.flush();
            fileOut.close();
        } catch (IOException ignored) {
            // Closing the diagnostics file must never throw on the way out.
        }
        fileOut = null;
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * The per-OS app-data directory for jvre's logs. Each platform has a blessed
     * spot; we honor it rather than dropping a file in the working directory.
     *   - Windows: %LOCALAPPDATA%\jvre        (machine-local, not roaming)
     *   - macOS:   ~/Library/Logs/jvre        (Apple's log location)
     *   - Linux:   $XDG_STATE_HOME/jvre, else ~/.local/state/jvre
     *              (the XDG spec files LOGS under "state", not "data" or "cache")
     */
    private static Path logDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home", ".");

        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            Path base = (local != null && !local.isBlank())
                    ? Path.of(local) : Path.of(home, "AppData", "Local");
            return base.resolve("jvre");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Path.of(home, "Library", "Logs", "jvre");
        }
        String xdg = System.getenv("XDG_STATE_HOME");
        Path base = (xdg != null && !xdg.isBlank())
                ? Path.of(xdg) : Path.of(home, ".local", "state");
        return base.resolve("jvre");
    }

    /**
     * Keep the immediately-previous run as jvre.log.1, so a crash log isn't
     * clobbered the moment the user relaunches before sending it.
     */
    private static void rollPrevious(Path log) throws IOException {
        if (Files.exists(log)) {
            Files.move(log, log.resolveSibling("jvre.log.1"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The static environment block: everything knowable WITHOUT Vulkan (OS, JVM,
     * LWJGL). The dynamic half -- GPU, driver, queue layout, formats -- is added
     * by Instance/Device/Swapchain as those objects come up (their existing
     * prints, captured by the tee).
     */
    private static void writeHeader(String appName) {
        String ts = ZonedDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
        line("================ jvre diagnostics ================");
        line("started   : " + ts);
        line("app       : " + appName);
        line("log file  : " + logPath);
        line("os        : " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        line("jvm       : " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");
        line("lwjgl     : " + Version.getVersion());
        line("--------------------------------------------------");
        line("If jvre misbehaves, attach this file to a bug report:");
        line("    " + ISSUE_URL);
        line("==================================================");
    }

    /** Write one line through the tee (-> console AND file). */
    private static void line(String s) {
        System.out.println(s);
    }

    /**
     * A two-destination OutputStream: everything written goes to the original
     * console stream AND to the shared log file. The classic decorator -- the
     * PrintStream wrapping it can't tell it's writing to two places.
     *
     * Both the System.out tee and the System.err tee share ONE file stream, so
     * writes are synchronized on it to keep a line from the validation callback
     * (which may run on a different thread) from interleaving mid-line with a
     * main-thread line.
     */
    private static final class Tee extends OutputStream {
        private final OutputStream console;   // the original System.out / System.err
        private final OutputStream file;      // the shared log file

        Tee(OutputStream console, OutputStream file) {
            this.console = console;
            this.file = file;
        }

        @Override
        public void write(int b) throws IOException {
            console.write(b);
            synchronized (file) {
                file.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            console.write(b, off, len);
            synchronized (file) {
                file.write(b, off, len);
            }
        }

        @Override
        public void flush() throws IOException {
            console.flush();
            synchronized (file) {
                file.flush();
            }
        }

        @Override
        public void close() throws IOException {
            // Flush only -- never close the real console stream, and the file is
            // owned/closed by Diagnostics.close().
            flush();
        }
    }
}
