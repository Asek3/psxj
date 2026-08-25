package nanolive.psxj.emu.devices;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GpuTriangleSpanTest {

    @Test
    void rowSpanMatchesPerPixelEdgeRules() {
        Random random = new Random(0x5053584AL);
        for (int sample = 0; sample < 100_000; sample++) {
            int width = random.nextInt(1, 1_025);
            int value0 = random.nextInt(-1_100_000, 1_100_001);
            int value1 = random.nextInt(-1_100_000, 1_100_001);
            int value2 = random.nextInt(-1_100_000, 1_100_001);
            int step0 = random.nextInt(-2_048, 2_049);
            int step1 = random.nextInt(-2_048, 2_049);
            int step2 = random.nextInt(-2_048, 2_049);

            int expectedFirst = -1;
            int expectedLast = -1;
            for (int x = 0; x < width; x++) {
                if (value0 + step0 * x >= 0
                    && value1 + step1 * x >= 0
                    && value2 + step2 * x >= 0) {
                    if (expectedFirst < 0) expectedFirst = x;
                    expectedLast = x;
                }
            }

            long actual = Gpu.triangleRowSpan(
                width,
                value0, step0,
                value1, step1,
                value2, step2
            );
            if (expectedFirst < 0) {
                assertEquals(-1L, actual);
            } else {
                assertEquals(expectedFirst, (int) (actual >>> 32));
                assertEquals(expectedLast, (int) actual);
            }
        }
    }

    @Test
    void triangleReciprocalDivisionIsExact() {
        Random random = new Random(0x475055L);
        for (int sample = 0; sample < 1_000_000; sample++) {
            int divisor = random.nextInt(1, 1_100_001);
            int numerator = random.nextInt(0, (int) Math.min(
                Integer.MAX_VALUE,
                (long) divisor * 255L + 1L
            ));
            long reciprocal = Gpu.unsignedDivisionReciprocal(divisor);
            assertEquals(numerator / divisor,
                Gpu.dividePositive(numerator, divisor, reciprocal));
        }
    }
}
