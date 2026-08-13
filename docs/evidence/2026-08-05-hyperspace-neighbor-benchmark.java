import dev.starsector.preflight.agent.CampaignEntityMaintenanceRuntime;
import java.util.Locale;

/** Compares vanilla's repeated bounds loop with Preflight's equivalent loop on Starsector's JVM. */
final class HyperspaceNeighborBenchmark {
    private static final int WARMUP = 20_000_000;
    private static final int ITERATIONS = 100_000_000;
    private static final int MASK = 511;
    private static volatile int sink;

    public static void main(String[] args) {
        int[][] cells = new int[MASK + 1][MASK + 1];
        for (int x = 0; x < cells.length; x++) {
            for (int y = 0; y < cells[x].length; y++) {
                cells[x][y] = ((x * 31 + y * 17) & 7) == 0 ? 1 : 0;
            }
        }
        runVanilla(cells, WARMUP);
        runPreflight(cells, WARMUP);
        report("vanilla", () -> runVanilla(cells, ITERATIONS));
        report("preflight", () -> runPreflight(cells, ITERATIONS));
        System.out.println("sink=" + sink);
    }

    private static void runVanilla(int[][] cells, int count) {
        int total = 0;
        for (int call = 0; call < count; call++) {
            int x = call & MASK;
            int y = (call * 17) & MASK;
            total += vanilla(cells, x, y);
        }
        sink = total;
    }

    private static void runPreflight(int[][] cells, int count) {
        int total = 0;
        for (int call = 0; call < count; call++) {
            int x = call & MASK;
            int y = (call * 17) & MASK;
            total += CampaignEntityMaintenanceRuntime.hyperspaceLiveNeighborCount(cells, x, y);
        }
        sink = total;
    }

    private static int vanilla(int[][] cells, int x, int y) {
        int count = 0;
        for (int column = Math.max(0, x - 1);
                column <= Math.min(x + 1, cells.length - 1); column++) {
            for (int row = Math.max(0, y - 1);
                    row <= Math.min(y + 1, cells[0].length - 1); row++) {
                if ((column != x || row != y) && cells[column][row] == 1) count++;
            }
        }
        return count;
    }

    private static void report(String label, Runnable work) {
        long started = System.nanoTime();
        work.run();
        long elapsed = System.nanoTime() - started;
        System.out.printf(Locale.ROOT, "%s %.3f ns/op%n", label,
                (double) elapsed / ITERATIONS);
    }
}
