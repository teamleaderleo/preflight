package dev.starsector.preflight.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Executes one desktop helper with bounded output, time and interruption cleanup. */
final class SystemDesktopCommandExecutor implements DesktopCommandExecutor {
    private static final int OUTPUT_LIMIT = 64 * 1024;

    @Override
    public Result run(List<String> command, Duration timeout) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        BoundedOutput output = new BoundedOutput(process.getInputStream(), OUTPUT_LIMIT);
        Thread reader = new Thread(output, "Preflight-Desktop-Command-Output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                throw new IOException(
                        "Desktop command exceeded " + timeout.toSeconds() + " seconds");
            }
            reader.join(2_000L);
            return new Result(process.exitValue(), output.text());
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private static final class BoundedOutput implements Runnable {
        private final InputStream input;
        private final int limit;
        private final ByteArrayOutputStream retained;
        private IOException problem;
        private boolean truncated;

        private BoundedOutput(InputStream input, int limit) {
            this.input = input;
            this.limit = limit;
            this.retained = new ByteArrayOutputStream(limit);
        }

        @Override
        public void run() {
            byte[] bytes = new byte[4_096];
            try (input) {
                int count;
                while ((count = input.read(bytes)) >= 0) {
                    int accepted = Math.min(count, limit - retained.size());
                    if (accepted > 0) retained.write(bytes, 0, accepted);
                    if (accepted < count) truncated = true;
                }
            } catch (IOException error) {
                problem = error;
            }
        }

        private String text() throws IOException {
            if (problem != null) throw problem;
            String value = retained.toString(StandardCharsets.UTF_8);
            return truncated ? value + "\n[output truncated]" : value;
        }
    }
}
