package io.github.drbergmanlab.biwt.core.channelmath;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for channel-math expressions. Grammar:
 *
 * <pre>
 * expr     := term  (('+' | '-') term)*
 * term     := power (('*' | '/') power)*
 * power    := unary ('^' unary)?           // right-associative
 * unary    := ('-' | '+')? primary
 * primary  := NUMBER
 *           | IDENT ( '(' args ')' )?      // identifier or function call
 *           | '(' expr ')'
 * args     := expr (',' expr)*
 * </pre>
 *
 * <p>Built-in function names ({@link Expression.BuiltinFunction}) take precedence over channel
 * identifiers — using {@code log} or {@code clip} as a substrate name in the image will produce
 * a parse error when the expression that names them is parsed.
 *
 * <p>Whitespace is permitted anywhere except inside numeric literals. Identifier names follow
 * Java's identifier rules (letter or underscore, then letter/digit/underscore).
 *
 * <p>Errors throw {@link ExpressionParseException} with a 1-based column.
 */
public final class ExpressionParser {

    /** Parse {@code source} into an AST. Public entry point. */
    public static Expression parse(String source) {
        ExpressionParser parser = new ExpressionParser(source);
        Expression result = parser.parseExpr();
        parser.expect(TokenKind.EOF, "Unexpected token after expression");
        return result;
    }

    // ---------------- internal state ----------------

    private final String src;
    private int pos;

    private ExpressionParser(String src) {
        this.src = src;
        this.pos = 0;
    }

    // ---------------- grammar productions ----------------

    private Expression parseExpr() {
        Expression left = parseTerm();
        while (true) {
            skipWhitespace();
            if (peek('+')) {
                pos++;
                left = new Expression.BinaryOp(Expression.BinaryKind.ADD, left, parseTerm());
            } else if (peek('-')) {
                pos++;
                left = new Expression.BinaryOp(Expression.BinaryKind.SUB, left, parseTerm());
            } else {
                return left;
            }
        }
    }

    private Expression parseTerm() {
        Expression left = parsePower();
        while (true) {
            skipWhitespace();
            if (peek('*')) {
                pos++;
                left = new Expression.BinaryOp(Expression.BinaryKind.MUL, left, parsePower());
            } else if (peek('/')) {
                pos++;
                left = new Expression.BinaryOp(Expression.BinaryKind.DIV, left, parsePower());
            } else {
                return left;
            }
        }
    }

    private Expression parsePower() {
        Expression base = parseUnary();
        skipWhitespace();
        if (peek('^')) {
            pos++;
            // Right-associative: a^b^c parses as a^(b^c).
            Expression exp = parsePower();
            return new Expression.BinaryOp(Expression.BinaryKind.POW, base, exp);
        }
        return base;
    }

    private Expression parseUnary() {
        skipWhitespace();
        if (peek('-')) {
            pos++;
            return new Expression.UnaryOp(Expression.UnaryKind.NEG, parseUnary());
        }
        if (peek('+')) {
            pos++;
            return new Expression.UnaryOp(Expression.UnaryKind.POS, parseUnary());
        }
        return parsePrimary();
    }

    private Expression parsePrimary() {
        skipWhitespace();
        if (atEnd()) {
            throw new ExpressionParseException("Expected number, identifier, or '('", pos + 1);
        }
        char c = src.charAt(pos);
        if (c == '(') {
            pos++;
            Expression inner = parseExpr();
            skipWhitespace();
            if (!peek(')')) {
                throw new ExpressionParseException("Expected ')'", pos + 1);
            }
            pos++;
            return inner;
        }
        if (isDigit(c) || c == '.') {
            return parseNumber();
        }
        if (isIdentStart(c)) {
            return parseIdentifierOrCall();
        }
        throw new ExpressionParseException("Unexpected character '" + c + "'", pos + 1);
    }

    private Expression parseNumber() {
        int start = pos;
        boolean sawDot = false;
        while (!atEnd()) {
            char c = src.charAt(pos);
            if (isDigit(c)) {
                pos++;
            } else if (c == '.' && !sawDot) {
                sawDot = true;
                pos++;
            } else {
                break;
            }
        }
        // Optional exponent: e[+-]?digits.
        if (!atEnd() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            pos++;
            if (!atEnd() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                pos++;
            }
            int expStart = pos;
            while (!atEnd() && isDigit(src.charAt(pos))) pos++;
            if (pos == expStart) {
                throw new ExpressionParseException("Expected digits after exponent", pos + 1);
            }
        }
        String text = src.substring(start, pos);
        try {
            return new Expression.NumberLiteral(Double.parseDouble(text));
        } catch (NumberFormatException nfe) {
            throw new ExpressionParseException("Invalid number '" + text + "'", start + 1);
        }
    }

    private Expression parseIdentifierOrCall() {
        int start = pos;
        while (!atEnd() && isIdentPart(src.charAt(pos))) pos++;
        String name = src.substring(start, pos);
        skipWhitespace();
        if (peek('(')) {
            // Function call. Identifier must match a built-in; otherwise it's an error
            // (we don't support user-defined functions in v1).
            Expression.BuiltinFunction fn = Expression.BuiltinFunction.fromName(
                    name.toLowerCase(java.util.Locale.ROOT));
            if (fn == null) {
                throw new ExpressionParseException(
                        "Unknown function '" + name + "'", start + 1);
            }
            pos++; // consume '('
            List<Expression> args = new ArrayList<>();
            skipWhitespace();
            if (!peek(')')) {
                args.add(parseExpr());
                while (true) {
                    skipWhitespace();
                    if (peek(',')) {
                        pos++;
                        args.add(parseExpr());
                    } else {
                        break;
                    }
                }
            }
            skipWhitespace();
            if (!peek(')')) {
                throw new ExpressionParseException("Expected ')' to close function call", pos + 1);
            }
            pos++;
            if (args.size() != fn.arity()) {
                throw new ExpressionParseException(
                        name + "() takes " + fn.arity() + " argument(s), got " + args.size(), start + 1);
            }
            return new Expression.FunctionCall(fn, args);
        }
        return new Expression.IdentifierRef(name);
    }

    // ---------------- token-style helpers ----------------

    /** Used only for the trailing EOF check. */
    private enum TokenKind { EOF }

    private void expect(TokenKind k, String message) {
        skipWhitespace();
        if (k == TokenKind.EOF && !atEnd()) {
            throw new ExpressionParseException(message, pos + 1);
        }
    }

    private boolean atEnd() {
        return pos >= src.length();
    }

    private boolean peek(char c) {
        return !atEnd() && src.charAt(pos) == c;
    }

    private void skipWhitespace() {
        while (!atEnd() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
