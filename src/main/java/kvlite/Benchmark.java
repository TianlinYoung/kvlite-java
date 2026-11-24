// Launch the fixed workload against temporary stores.
package kvlite;

import java.io.IOException;

public final class Benchmark {
    public static void main(String[] args) throws IOException {
        BenchmarkWorkload.run(System.out, 1_000, 5_000, 20_000);
    }
}
