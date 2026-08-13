import dev.starsector.preflight.core.PreparedAudio;
import dev.starsector.preflight.core.PreparedAudioIO;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Fresh-process real-corpus benchmark shared by the legacy and direct trusted audio readers. */
final class PreparedAudioDirectReadBenchmark {
    private static final long CORPUS_LIMIT = 300_000_000L;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        List<Path> files;
        try (var walk = Files.walk(root)) {
            files = walk.filter(path -> path.toString().endsWith(".spau"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        if (args.length > 1 && args[1].equals("verify")) {
            long bytes = 0;
            for (Path file : files) {
                PreparedAudio checked = PreparedAudioIO.read(file);
                PreparedAudio direct = PreparedAudioIO.readTrusted(file);
                if (!checked.equals(direct)) {
                    throw new AssertionError("Readers differ for " + file);
                }
                bytes += direct.pcmByteCount();
            }
            System.out.printf("PASS files=%d pcmBytes=%d%n", files.size(), bytes);
            return;
        }
        List<Path> corpus = new ArrayList<>();
        long blobBytes = 0;
        for (Path file : files) {
            long size = Files.size(file);
            if (!corpus.isEmpty() && blobBytes + size > CORPUS_LIMIT) break;
            corpus.add(file);
            blobBytes += size;
        }

        Method directCopy = null;
        try {
            directCopy = PreparedAudio.class.getMethod("copyPcmTo", ByteBuffer.class);
        } catch (NoSuchMethodException ignored) {
            // The comparison JAR predates direct-copy support.
        }
        long started = System.nanoTime();
        long pcmBytes = 0;
        long checksum = 1;
        for (Path file : corpus) {
            PreparedAudio audio = PreparedAudioIO.readTrusted(file);
            ByteBuffer buffer = ByteBuffer.allocateDirect(audio.pcmByteCount());
            if (directCopy == null) {
                buffer.put(audio.pcmBytes());
            } else {
                directCopy.invoke(audio, buffer);
            }
            buffer.flip();
            pcmBytes += buffer.remaining();
            checksum = checksum * 31 + buffer.remaining();
            if (buffer.hasRemaining()) {
                checksum = checksum * 31 + buffer.get(0);
                checksum = checksum * 31 + buffer.get(buffer.limit() - 1);
            }
        }
        long elapsed = System.nanoTime() - started;
        System.out.printf("mode=%s files=%d blobBytes=%d pcmBytes=%d elapsedMs=%.3f checksum=%d%n",
                directCopy == null ? "legacy" : "direct", corpus.size(), blobBytes, pcmBytes,
                elapsed / 1e6, checksum);
    }
}
