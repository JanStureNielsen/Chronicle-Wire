package net.openhft.chronicle.wire;

import net.openhft.chronicle.bytes.BytesIn;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

enum YamlToken {
    COMMENT,
    TAG,
    DIRECTIVE,
    DIRECTIVES_END,
    DOCUMENT_END,
    MAPPING_START,
    MAPPING_KEY,
    MAPPING_END,
    SEQUENCE_START,
    SEQUENCE_END,
    SEQUENCE_ENTRY,
    INTEGER,
    DECIMAL,
    TEXT,
    ANCHOR,
    ALIAS,
    RESERVED,
    NONE
}

enum YamlType {
    DOC_START,
    DOC_END,
    B_SEQUENCE_COMPACT_START,
    B_SEQUENCE_START,
    B_MAP_COMPACT_KEY,
    B_MAP_COMPACT_VALUE,
    B_MAP_KEY,
    B_MAP_VALUE,
    B_LITERAL_START,
    B_LITERAL_END,
    B_FOLD_START,
    B_FOLD_END,
    DOUBLEQUOTE_START,
    DOUBLEQUOTE_END,
    SINGLEQUOTE_START,
    SINGLEQUOTE_END,
    CAST_TYPE,
    SCALAR,
    INDENT,
    DEDENT,
    F_SEQUENCE_START,
    F_SEQUENCE_END,
    F_MAP_START,
    F_MAP_END,
    F_MAP_KEY,
    F_SEP,

}

public class YamlTokeniser {
    private static final int INIT_SIZE = 10;
    private static final Set<YamlToken> HEADER = EnumSet.of(YamlToken.COMMENT, YamlToken.DIRECTIVE, YamlToken.DIRECTIVES_END, YamlToken.NONE);
    private final BytesIn in;
    private final List<YamlToken> pushed = new ArrayList<>();
    private int lastContext = 0;
    private YamlToken[] contextArray = new YamlToken[INIT_SIZE];
    private int[] contextIndent = new int[INIT_SIZE];
    @NotNull
    private YamlToken last = YamlToken.NONE;
    private long lineStart = 0;
    private long blockStart = 0; // inclusive
    private long blockEnd = 0; // inclusive
    private int flowDepth = Integer.MAX_VALUE;

    public YamlTokeniser(BytesIn in) {
        this.in = in;
        contextArray[0] = YamlToken.NONE;
    }

    public YamlToken context() {
        return contextArray[lastContext];
    }

    public YamlToken current() {
        if (last == YamlToken.NONE)
            next();
        return last;
    }

    @NotNull
    public YamlToken next() {
        if (!pushed.isEmpty()) {
            YamlToken next = popPushed();
            return last = next;
        }
        YamlToken next = next0();
        return this.last = next;
    }

    private YamlToken next0() {
        consumeWhitespace();
        blockStart = blockEnd = in.readPosition();
        int indent = Math.toIntExact(in.readPosition() - lineStart);
        int ch = in.readUnsignedByte();
        switch (ch) {
            case -1:
                if (context() == YamlToken.NONE)
                    return YamlToken.NONE;
                pushed.add(YamlToken.DOCUMENT_END);
                popAll();
                context(YamlToken.NONE);
                return popPushed();
            case '#':
                readComment();
                return YamlToken.COMMENT;
            case '"':
                readQuoted('"');
                if (isFieldEnd())
                    return indent(YamlToken.MAPPING_START, YamlToken.MAPPING_KEY, YamlToken.TEXT, indent * 2 + 1);
                return YamlToken.TEXT;
            case '\'':
                readQuoted('\'');
                if (isFieldEnd())
                    return indent(YamlToken.MAPPING_START, YamlToken.MAPPING_KEY, YamlToken.TEXT, indent * 2 + 1);
                return YamlToken.TEXT;
            case '?':
                return YamlToken.MAPPING_KEY;

            case '-': {
                int next = in.peekUnsignedByte();
                if (next <= ' ') {
                    return indent(YamlToken.SEQUENCE_START, YamlToken.SEQUENCE_ENTRY, YamlToken.NONE, indent * 2 + 2);
                }
                if (next == '-') {
                    if (in.peekUnsignedByte(in.readPosition() + 1) == '-' &&
                            in.peekUnsignedByte(in.readPosition() + 2) <= ' ') {
                        pushed.add(YamlToken.DIRECTIVES_END);
                        popAll();
                        return popPushed();
                    }
                }
                in.readSkip(-1);
                if (next >= '0' && next <= '9') {
                    return readNumber(indent);
                }
                return readText(indent);
            }
            case '.': {
                int next = in.peekUnsignedByte();
                if (next == '.') {
                    if (in.peekUnsignedByte(in.readPosition() + 1) == '.' &&
                            in.peekUnsignedByte(in.readPosition() + 2) <= ' ') {
                        pushed.add(YamlToken.DOCUMENT_END);
                        popAll();
                        context(YamlToken.NONE);
                        return popPushed();
                    }
                }
                in.readSkip(-1);
                if (next >= '0' && next <= '9') {
                    return readNumber(indent);
                }
                return readText(indent);
            }
            case '&':
                return YamlToken.ANCHOR;
            case '*':
                return YamlToken.ALIAS;
            case '|':
                readLiteral();
                return YamlToken.TEXT;
            case '>':
                readFolded();
                return YamlToken.TEXT;
            case '%':
                readDirective();
                return YamlToken.DIRECTIVE;
            case '@':
            case '`':
                readReserved();
                return YamlToken.RESERVED;
            case '+':
                return readNumber(indent);
            case '!':
                return YamlToken.TAG;
            case '{':
                return flow(YamlToken.MAPPING_START, indent);
            case '}':
                return flowPop(YamlToken.MAPPING_START, '}');
            case '[':
                return flow(YamlToken.SEQUENCE_START, indent);
            case ']':
                return flowPop(YamlToken.SEQUENCE_START, ']');
            case ',':
                // CHECK in a LIST or MAPPING.
                return next0();

            // other symbols
            case '$':
            case '(':
            case ')':
            case '/':
            case ':':
            case ';':
            case '<':
            case '=':
            case '\\':
            case '^':
            case '_':
            case '~':
        }
        in.readSkip(-1);
        if (ch >= '0' && ch <= '9')
            return readNumber(indent);
        return readText(indent);
    }

    private YamlToken flowPop(YamlToken start, char end) {
        int pos = pushed.size();
        while (context() != start) {
            if (lastContext == 0)
                throw new IllegalArgumentException("Unexpected ']'");
            contextPop();
        }
        contextPop();
        reversePushed(pos);
        return popPushed();
    }

    private YamlToken flow(YamlToken token, int indent) {
        pushed.add(token);
        if (context() == YamlToken.SEQUENCE_START)
            pushed.add(YamlToken.SEQUENCE_ENTRY);
        contextPush(token, indent);
        if (flowDepth == Integer.MAX_VALUE)
            flowDepth = lastContext;
        return popPushed();
    }

    private void readReserved() {
        throw new UnsupportedOperationException();
    }

    private void readDirective() {
        throw new UnsupportedOperationException();
    }

    private void readFolded() {
        throw new UnsupportedOperationException();
    }

    private void readLiteral() {
        throw new UnsupportedOperationException();
    }

    private YamlToken readText(int indent) {
        readWords();
        if (isFieldEnd())
            return indent(YamlToken.MAPPING_START, YamlToken.MAPPING_KEY, YamlToken.TEXT, indent * 2 + 1);
        return YamlToken.TEXT;
    }

    @NotNull
    private YamlToken readNumber(int indent) {
        blockStart = in.readPosition() - 1;
        YamlToken token = YamlToken.INTEGER;
        int ch;
        LOOP:
        while (true) {
            ch = in.readUnsignedByte();
            switch (ch) {
                case '_':
                case '-':
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    continue;
                case '.':
                case 'e':
                case 'E':
                    token = YamlToken.DECIMAL;
                    continue;
                default:
                    break LOOP;
            }
        }
        if (ch >= 0)
            in.readSkip(-1);
        blockEnd = in.readPosition();

        if (isFieldEnd())
            return indent(YamlToken.MAPPING_START, YamlToken.MAPPING_KEY, token, indent * 2 + 1);
        return token;
    }

    private void readWords() {
        blockStart = in.readPosition();
        while (in.readRemaining() > 0) {
            int ch = in.readUnsignedByte();
            switch (ch) {
                case ':':
                    if (in.peekUnsignedByte() > ' ')
                        continue;
                    // is a field.
                    endOfWords();
                    return;
                case ',':
                    if (!isInFlow())
                        continue;
                    endOfWords();
                    return;
                case '[':
                case ']':
                case '{':
                case '}':
                case '#':
                case '\n':
                case '\r':
                    endOfWords();
                    return;
            }
        }
        blockEnd = in.readPosition();
    }

    private void endOfWords() {
        blockEnd = in.readPosition() - 1;
        in.readSkip(-1);
    }

    private YamlToken indent(@NotNull YamlToken indented, @NotNull YamlToken key, @NotNull YamlToken push, int indent) {
        if (push != YamlToken.NONE)
            this.pushed.add(push);
        if (isInFlow()) {
            return key;
        }
        int pos = this.pushed.size();
        while (indent < contextIndent()) {
            contextPop();
        }
        if (indent != contextIndent())
            this.pushed.add(indented);
        this.pushed.add(key);
        reversePushed(pos);
        if (indent > contextIndent())
            contextPush(indented, indent);
        return popPushed();
    }

    private boolean isInFlow() {
        return lastContext >= flowDepth;
    }

    private void reversePushed(int pos) {
        for (int i = pos, j = pushed.size() - 1; i < j; i++, j--)
            pushed.set(i, pushed.set(j, pushed.get(i)));
    }

    private void popAll() {
        int pos = pushed.size();
        while (lastContext > 1) {
            contextPop();
        }
        reversePushed(pos);
    }

    private void contextPop() {
        YamlToken context = context();
        lastContext--;
        if (flowDepth == lastContext)
            flowDepth = Integer.MAX_VALUE;
        switch (context) {
            case MAPPING_START:
                pushed.add(YamlToken.MAPPING_END);
                break;
            case SEQUENCE_START:
                pushed.add(YamlToken.SEQUENCE_END);
                break;
            case DIRECTIVES_END:
                pushed.add(YamlToken.DOCUMENT_END);
                break;
            case NONE:
                break;
            default:
                throw new IllegalStateException("context: " + context);
        }
    }

    private YamlToken popPushed() {
        return pushed.isEmpty() ? YamlToken.NONE : pushed.remove(pushed.size() - 1);
    }

    private void contextPush(YamlToken context, int indent) {
        if (context() == YamlToken.NONE && context != YamlToken.DIRECTIVES_END) {
            contextPush0(YamlToken.DIRECTIVES_END, 0);
            contextPush0(context, indent);
            push(YamlToken.DIRECTIVES_END);
            return;
        }
        contextPush0(context, indent);
    }

    private void contextPush0(YamlToken indented, int indent) {
        lastContext++;
        contextArray[lastContext] = indented;
        contextIndent[lastContext] = indent;
    }

    private int contextIndent() {
        return contextIndent[lastContext];
    }

    private void readQuoted(char stop) {
        in.readSkip(1);
        blockStart = in.readPosition();
        while (in.readRemaining() > 0) {
            int ch = in.readUnsignedByte();
            if (ch == stop) {
                blockEnd = in.readPosition() - 1;
                return;
            }
            if (ch < ' ') {
                throw new IllegalStateException("Unterminated quotes " + in.subBytes(blockStart - 1, in.readPosition()));
            }
        }
    }

    private boolean isFieldEnd() {
        consumeSpaces();
        if (in.peekUnsignedByte() == ':' &&
                in.peekUnsignedByte(in.readPosition() + 1) <= ' ') {
            in.readSkip(Math.min(2, in.readRemaining()));
            return true;
        }
        return false;
    }

    private void readComment() {
        in.readSkip(1);
        consumeSpaces();
        blockStart = blockEnd = in.readPosition();
        while (true) {
            int ch = in.peekUnsignedByte();
            if (ch < 0 || ch == '\n' || ch == '\r')
                return;
            if (ch > ' ')
                blockEnd = in.readPosition();
        }
    }

    private void consumeSpaces() {
        while (true) {
            int ch = in.peekUnsignedByte();
            if (ch == ' ' || ch == '\t') {
                in.readSkip(1);
            } else {
                return;
            }
        }
    }

    private void consumeWhitespace() {
        while (true) {
            int ch = in.peekUnsignedByte();
            if (ch >= 0 && ch <= ' ') {
                in.readSkip(1);
                if (ch == '\n' || ch == '\r')
                    lineStart = in.readPosition();
            } else {
                return;
            }
        }
    }

    public long lineStart() {
        return lineStart;
    }

    public long blockStart() {
        return blockStart;
    }

    public long blockEnd() {
        return blockEnd;
    }

    // for testing.
    public String text() {
        if (blockStart == blockEnd
                || last == YamlToken.SEQUENCE_START
                || last == YamlToken.SEQUENCE_END
                || last == YamlToken.MAPPING_START
                || last == YamlToken.MAPPING_KEY
                || last == YamlToken.MAPPING_END
                || last == YamlToken.DIRECTIVES_END)
            return "";
        StringBuilder sb = Wires.acquireStringBuilder();
        long pos = in.readPosition();
        in.readPosition(blockStart);
        in.parseUtf8(sb, Math.toIntExact(blockEnd - blockStart));
        in.readPosition(pos);
        return sb.toString();
    }

    public void push(YamlToken token) {
        pushed.add(token);
    }

    // set the context
    public void context(YamlToken token) {
        contextArray[lastContext] = token;
    }

    private class YamlContext {
        YamlToken token;
        int indent;
    }
}