import dev.starsector.preflight.agent.RulesRegexCacheRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Bundled-JVM replay of SpecStore's exact two smart-quote replacement operations. */
final class SpecStoreQuoteNormalizationBenchmark {
    private static final String DOUBLE_REGEX = "[\\u201c\\u201d]+";
    private static final String SINGLE_REGEX = "[\\u2018\\u2019\\ufffd]+";

    public static void main(String[] args) {
        List<String> corpus = corpus();
        for (int index = 0; index < 5; index++) {
            run(corpus, false, 20);
            run(corpus, true, 20);
        }
        for (int round = 1; round <= 7; round++) {
            Result legacy = run(corpus, false, 100);
            Result direct = run(corpus, true, 100);
            if (legacy.checksum != direct.checksum) {
                throw new AssertionError("Output mismatch");
            }
            System.out.printf("round=%d strings=%d legacyMs=%.3f directMs=%.3f speedup=%.2fx checksum=%d%n",
                    round, corpus.size() * 100, legacy.nanos / 1e6, direct.nanos / 1e6,
                    (double) legacy.nanos / direct.nanos, direct.checksum);
        }
    }

    private static Result run(List<String> corpus, boolean direct, int repetitions) {
        long checksum = 1;
        long started = System.nanoTime();
        for (int repetition = 0; repetition < repetitions; repetition++) {
            for (String input : corpus) {
                String output = direct
                        ? RulesRegexCacheRuntime.replaceAll(
                                RulesRegexCacheRuntime.replaceAll(
                                        input, DOUBLE_REGEX, "\""),
                                SINGLE_REGEX, "'")
                        : input.replaceAll(DOUBLE_REGEX, "\"")
                                .replaceAll(SINGLE_REGEX, "'");
                checksum = checksum * 31 + output.hashCode();
            }
        }
        return new Result(System.nanoTime() - started, checksum);
    }

    private static List<String> corpus() {
        Random random = new Random(0x51a7eL);
        String ordinary = "abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789,.-_";
        String quotes = "\u201c\u201d\u2018\u2019\ufffd";
        List<String> values = new ArrayList<>();
        for (int sample = 0; sample < 10_000; sample++) {
            int length = 8 + random.nextInt(120);
            StringBuilder value = new StringBuilder(length);
            for (int index = 0; index < length; index++) {
                // Most real cells contain no smart quote; retain enough quote runs to exercise
                // replacement, collapsing, and interleaving on every pass.
                String alphabet = random.nextInt(40) == 0 ? quotes : ordinary;
                value.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            values.add(value.toString());
        }
        return List.copyOf(values);
    }

    private record Result(long nanos, long checksum) {
    }
}
