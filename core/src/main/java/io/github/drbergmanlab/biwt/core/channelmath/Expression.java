package io.github.drbergmanlab.biwt.core.channelmath;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiled channel-math expression tree. Evaluation is performed across a tile's worth of pixels
 * at a time: {@link #evaluate(ChannelEnvironment, float[], int)} fills the first {@code n}
 * elements of {@code dst} with the per-pixel result, reading any referenced channels from
 * {@code env}.
 *
 * <p>The tree is built by {@link ExpressionParser#parse(String)}. AST node types are nested
 * records so the sealed hierarchy stays in one file and the GUI can pattern-match if needed.
 */
public sealed interface Expression
        permits Expression.NumberLiteral,
                Expression.IdentifierRef,
                Expression.UnaryOp,
                Expression.BinaryOp,
                Expression.FunctionCall {

    /**
     * Fill {@code dst[0..n)} with this expression's per-pixel value, reading any referenced
     * channels from {@code env}.
     *
     * @throws java.util.NoSuchElementException if an identifier references a channel not present
     *         in {@code env}.
     */
    void evaluate(ChannelEnvironment env, float[] dst, int n);

    /** Set of all identifier names this expression references (deduplicated, preserves order). */
    default Set<String> referencedIdentifiers() {
        Set<String> ids = new LinkedHashSet<>();
        collectIdentifiers(ids);
        return ids;
    }

    void collectIdentifiers(Set<String> out);

    // ---------------- AST nodes ----------------

    record NumberLiteral(double value) implements Expression {
        @Override
        public void evaluate(ChannelEnvironment env, float[] dst, int n) {
            float v = (float) value;
            for (int i = 0; i < n; i++) dst[i] = v;
        }
        @Override
        public void collectIdentifiers(Set<String> out) { /* no-op */ }
    }

    record IdentifierRef(String name) implements Expression {
        @Override
        public void evaluate(ChannelEnvironment env, float[] dst, int n) {
            float[] src = env.get(name);
            System.arraycopy(src, 0, dst, 0, n);
        }
        @Override
        public void collectIdentifiers(Set<String> out) {
            out.add(name);
        }
    }

    enum UnaryKind { NEG, POS }

    record UnaryOp(UnaryKind op, Expression operand) implements Expression {
        @Override
        public void evaluate(ChannelEnvironment env, float[] dst, int n) {
            operand.evaluate(env, dst, n);
            if (op == UnaryKind.NEG) {
                for (int i = 0; i < n; i++) dst[i] = -dst[i];
            }
        }
        @Override
        public void collectIdentifiers(Set<String> out) {
            operand.collectIdentifiers(out);
        }
    }

    enum BinaryKind { ADD, SUB, MUL, DIV, POW }

    record BinaryOp(BinaryKind op, Expression lhs, Expression rhs) implements Expression {
        @Override
        public void evaluate(ChannelEnvironment env, float[] dst, int n) {
            lhs.evaluate(env, dst, n);
            float[] rhsBuf = new float[n];
            rhs.evaluate(env, rhsBuf, n);
            switch (op) {
                case ADD -> { for (int i = 0; i < n; i++) dst[i] += rhsBuf[i]; }
                case SUB -> { for (int i = 0; i < n; i++) dst[i] -= rhsBuf[i]; }
                case MUL -> { for (int i = 0; i < n; i++) dst[i] *= rhsBuf[i]; }
                case DIV -> { for (int i = 0; i < n; i++) dst[i] /= rhsBuf[i]; }
                case POW -> { for (int i = 0; i < n; i++) dst[i] = (float) Math.pow(dst[i], rhsBuf[i]); }
            }
        }
        @Override
        public void collectIdentifiers(Set<String> out) {
            lhs.collectIdentifiers(out);
            rhs.collectIdentifiers(out);
        }
    }

    /** Built-in functions with fixed arity. */
    enum BuiltinFunction {
        LOG(1), LOG10(1), EXP(1), SQRT(1), ABS(1),
        MIN(2), MAX(2),
        CLIP(3);

        private final int arity;

        BuiltinFunction(int arity) { this.arity = arity; }

        public int arity() { return arity; }

        /** Look up by lowercase name; returns null if no match. */
        public static BuiltinFunction fromName(String lowerName) {
            return switch (lowerName) {
                case "log" -> LOG;
                case "log10" -> LOG10;
                case "exp" -> EXP;
                case "sqrt" -> SQRT;
                case "abs" -> ABS;
                case "min" -> MIN;
                case "max" -> MAX;
                case "clip" -> CLIP;
                default -> null;
            };
        }
    }

    record FunctionCall(BuiltinFunction fn, List<Expression> args) implements Expression {
        public FunctionCall {
            args = List.copyOf(args);
            if (args.size() != fn.arity()) {
                throw new IllegalArgumentException(
                        fn.name().toLowerCase() + "() takes " + fn.arity() + " argument(s), got " + args.size());
            }
        }
        @Override
        public void evaluate(ChannelEnvironment env, float[] dst, int n) {
            args.get(0).evaluate(env, dst, n);
            switch (fn) {
                case LOG -> { for (int i = 0; i < n; i++) dst[i] = (float) Math.log(dst[i]); }
                case LOG10 -> { for (int i = 0; i < n; i++) dst[i] = (float) Math.log10(dst[i]); }
                case EXP -> { for (int i = 0; i < n; i++) dst[i] = (float) Math.exp(dst[i]); }
                case SQRT -> { for (int i = 0; i < n; i++) dst[i] = (float) Math.sqrt(dst[i]); }
                case ABS -> { for (int i = 0; i < n; i++) dst[i] = Math.abs(dst[i]); }
                case MIN -> {
                    float[] b = new float[n];
                    args.get(1).evaluate(env, b, n);
                    for (int i = 0; i < n; i++) dst[i] = Math.min(dst[i], b[i]);
                }
                case MAX -> {
                    float[] b = new float[n];
                    args.get(1).evaluate(env, b, n);
                    for (int i = 0; i < n; i++) dst[i] = Math.max(dst[i], b[i]);
                }
                case CLIP -> {
                    float[] lo = new float[n];
                    float[] hi = new float[n];
                    args.get(1).evaluate(env, lo, n);
                    args.get(2).evaluate(env, hi, n);
                    for (int i = 0; i < n; i++) {
                        float x = dst[i];
                        if (x < lo[i]) x = lo[i];
                        else if (x > hi[i]) x = hi[i];
                        dst[i] = x;
                    }
                }
            }
        }
        @Override
        public void collectIdentifiers(Set<String> out) {
            for (Expression a : args) a.collectIdentifiers(out);
        }
    }
}
