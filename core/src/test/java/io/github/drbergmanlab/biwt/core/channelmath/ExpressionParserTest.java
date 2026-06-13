package io.github.drbergmanlab.biwt.core.channelmath;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionParserTest {

    @Test
    void parsesNumberLiteral() {
        assertEquals(new Expression.NumberLiteral(3.14), ExpressionParser.parse("3.14"));
    }

    @Test
    void parsesIdentifier() {
        assertEquals(new Expression.IdentifierRef("H"), ExpressionParser.parse("H"));
    }

    @Test
    void respectsOperatorPrecedence() {
        // 2 + 3 * 4 → 14 (mul before add)
        Expression e = ExpressionParser.parse("2 + 3 * 4");
        float[] dst = new float[1];
        e.evaluate(new ChannelEnvironment(), dst, 1);
        assertEquals(14.0f, dst[0], 1e-6f);
    }

    @Test
    void powerIsRightAssociative() {
        // 2 ^ 3 ^ 2 = 2 ^ 9 = 512  (not 8 ^ 2 = 64)
        Expression e = ExpressionParser.parse("2 ^ 3 ^ 2");
        float[] dst = new float[1];
        e.evaluate(new ChannelEnvironment(), dst, 1);
        assertEquals(512.0f, dst[0], 1e-6f);
    }

    @Test
    void parenthesesOverridePrecedence() {
        Expression e = ExpressionParser.parse("(2 + 3) * 4");
        float[] dst = new float[1];
        e.evaluate(new ChannelEnvironment(), dst, 1);
        assertEquals(20.0f, dst[0], 1e-6f);
    }

    @Test
    void unaryMinusBindsTighterThanArithmetic() {
        // -2 ^ 2 should parse as -(2^2) = -4 since unary takes a primary,
        // wait — our grammar puts unary inside power. Let's verify: parsePower → parseUnary
        // first (so the - applies before ^). That makes -2 ^ 2 actually evaluate as (-2)^2 = 4.
        // Documenting the chosen behavior.
        Expression e = ExpressionParser.parse("-2 ^ 2");
        float[] dst = new float[1];
        e.evaluate(new ChannelEnvironment(), dst, 1);
        assertEquals(4.0f, dst[0], 1e-6f);
    }

    @Test
    void parsesFunctionCallWithMultipleArgs() {
        Expression e = ExpressionParser.parse("clip(10, 0, 5)");
        float[] dst = new float[1];
        e.evaluate(new ChannelEnvironment(), dst, 1);
        assertEquals(5.0f, dst[0], 1e-6f);
    }

    @Test
    void parsesScientificNotation() {
        Expression e = ExpressionParser.parse("1.5e2");
        float[] dst = new float[1];
        e.evaluate(new ChannelEnvironment(), dst, 1);
        assertEquals(150.0f, dst[0], 1e-6f);
    }

    @Test
    void collectsReferencedIdentifiers() {
        Expression e = ExpressionParser.parse("0.5 * H - 0.3 * E + clip(R, 0, 200)");
        Set<String> ids = e.referencedIdentifiers();
        assertEquals(Set.of("H", "E", "R"), ids);
    }

    @Test
    void rejectsMismatchedParentheses() {
        var ex = assertThrows(ExpressionParseException.class,
                () -> ExpressionParser.parse("(2 + 3"));
        assertTrue(ex.getMessage().contains("')'"), "got: " + ex.getMessage());
    }

    @Test
    void rejectsUnexpectedTrailingTokens() {
        var ex = assertThrows(ExpressionParseException.class,
                () -> ExpressionParser.parse("2 + 3)"));
        assertEquals(6, ex.column());
    }

    @Test
    void rejectsUnknownFunction() {
        var ex = assertThrows(ExpressionParseException.class,
                () -> ExpressionParser.parse("foo(2)"));
        assertTrue(ex.getMessage().contains("Unknown function 'foo'"), "got: " + ex.getMessage());
    }

    @Test
    void rejectsWrongArity() {
        var ex = assertThrows(ExpressionParseException.class,
                () -> ExpressionParser.parse("clip(10, 0)"));
        assertTrue(ex.getMessage().contains("takes 3"), "got: " + ex.getMessage());
    }

    @Test
    void rejectsEmptyExpression() {
        assertThrows(ExpressionParseException.class, () -> ExpressionParser.parse("   "));
    }

    @Test
    void rejectsTrailingOperator() {
        assertThrows(ExpressionParseException.class, () -> ExpressionParser.parse("H +"));
    }

    @Test
    void rejectsBadExponent() {
        assertThrows(ExpressionParseException.class, () -> ExpressionParser.parse("1.5e"));
    }

    @Test
    void parsesBracketedNameWithSpace() {
        Expression e = ExpressionParser.parse("[DAPI nuclei]");
        assertEquals(new Expression.IdentifierRef("DAPI nuclei"), e);
        assertEquals(Set.of("DAPI nuclei"), e.referencedIdentifiers());
    }

    @Test
    void bracketedNamesComposeWithArithmetic() {
        Expression e = ExpressionParser.parse("0.5*[Channel 0] + [Channel 2]");
        assertEquals(Set.of("Channel 0", "Channel 2"), e.referencedIdentifiers());
    }

    @Test
    void bracketedNameTrimsSurroundingWhitespace() {
        Expression e = ExpressionParser.parse("[  Channel 0  ]");
        assertEquals(new Expression.IdentifierRef("Channel 0"), e);
    }

    @Test
    void bracketedNameEvaluatesAgainstRegisteredChannel() {
        Expression e = ExpressionParser.parse("[Channel 0] + 1");
        ChannelEnvironment env = new ChannelEnvironment().register("Channel 0", new float[] {4f, 5f});
        float[] dst = new float[2];
        e.evaluate(env, dst, 2);
        assertArrayEquals(new float[] {5f, 6f}, dst, 1e-6f);
    }

    @Test
    void bracketedNameMayCollideWithBuiltinFunctionName() {
        // A channel literally named "log" is reachable via brackets (the bare form would parse the
        // builtin and demand parentheses).
        Expression e = ExpressionParser.parse("[log]");
        assertEquals(new Expression.IdentifierRef("log"), e);
    }

    @Test
    void rejectsUnterminatedBracket() {
        var ex = assertThrows(ExpressionParseException.class,
                () -> ExpressionParser.parse("[Channel 0"));
        assertTrue(ex.getMessage().contains("Unterminated"), "got: " + ex.getMessage());
    }

    @Test
    void rejectsEmptyBracket() {
        var ex = assertThrows(ExpressionParseException.class,
                () -> ExpressionParser.parse("[]"));
        assertTrue(ex.getMessage().contains("Empty"), "got: " + ex.getMessage());
    }
}
