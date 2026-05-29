package io.github.drbergmanlab.biwt.core.channelmath;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionEvaluationTest {

    private static final float EPS = 1e-5f;

    @Test
    void linearCombinationOfTwoChannels() {
        // 0.5*H - 0.3*E
        ChannelEnvironment env = new ChannelEnvironment()
                .register("H", new float[] {1.0f,  2.0f, 4.0f})
                .register("E", new float[] {0.0f, 10.0f, 1.0f});
        Expression e = ExpressionParser.parse("0.5*H - 0.3*E");
        float[] dst = new float[3];
        e.evaluate(env, dst, 3);

        assertArrayEquals(new float[] {
                0.5f * 1.0f - 0.3f * 0.0f,    // 0.5
                0.5f * 2.0f - 0.3f * 10.0f,   // -2.0
                0.5f * 4.0f - 0.3f * 1.0f     // 1.7
        }, dst, EPS);
    }

    @Test
    void clipBoundsApplyElementwise() {
        ChannelEnvironment env = new ChannelEnvironment()
                .register("X", new float[] {-5.0f, 0.5f, 10.0f, 200.0f});
        Expression e = ExpressionParser.parse("clip(X, 0, 100)");
        float[] dst = new float[4];
        e.evaluate(env, dst, 4);
        assertArrayEquals(new float[] {0.0f, 0.5f, 10.0f, 100.0f}, dst, EPS);
    }

    @Test
    void log10HandlesEpsilonGuardManually() {
        // The expression itself doesn't protect against log10(0); the user does. Verify the
        // common idiom log10((X + 1) / 256) works.
        ChannelEnvironment env = new ChannelEnvironment()
                .register("X", new float[] {0.0f, 255.0f});
        Expression e = ExpressionParser.parse("-log10((X + 1) / 256)");
        float[] dst = new float[2];
        e.evaluate(env, dst, 2);
        assertEquals(-Math.log10(1.0 / 256), dst[0], 1e-4);
        assertEquals(-Math.log10(256.0 / 256), dst[1], 1e-4);
    }

    @Test
    void caseInsensitiveIdentifierLookup() {
        ChannelEnvironment env = new ChannelEnvironment()
                .register("Hematoxylin", new float[] {7.0f});
        Expression e = ExpressionParser.parse("hematoxylin * 2");
        float[] dst = new float[1];
        e.evaluate(env, dst, 1);
        assertEquals(14.0f, dst[0], EPS);
    }

    @Test
    void unknownIdentifierAtEvaluationThrows() {
        Expression e = ExpressionParser.parse("X + 1");
        ChannelEnvironment env = new ChannelEnvironment();
        float[] dst = new float[1];
        assertThrows(NoSuchElementException.class, () -> e.evaluate(env, dst, 1));
    }

    @Test
    void minMaxAreElementwise() {
        ChannelEnvironment env = new ChannelEnvironment()
                .register("A", new float[] {1, 5, 9})
                .register("B", new float[] {4, 2, 9});
        Expression eMin = ExpressionParser.parse("min(A, B)");
        Expression eMax = ExpressionParser.parse("max(A, B)");
        float[] mn = new float[3], mx = new float[3];
        eMin.evaluate(env, mn, 3);
        eMax.evaluate(env, mx, 3);
        assertArrayEquals(new float[] {1, 2, 9}, mn, EPS);
        assertArrayEquals(new float[] {4, 5, 9}, mx, EPS);
    }
}
