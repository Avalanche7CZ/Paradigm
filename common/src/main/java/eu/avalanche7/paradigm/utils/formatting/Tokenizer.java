package eu.avalanche7.paradigm.utils.formatting;

import java.util.ArrayList;
import java.util.List;

public class Tokenizer {
    private final String input;
    private int position = 0;

    public Tokenizer(String input) {
        this.input = input == null ? "" : input;
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();

        while (position < input.length()) {
            char current = input.charAt(position);

            if (current == '\\' && position + 1 < input.length()) {
                position++;
                char nextChar = input.charAt(position);
                tokens.add(new Token(Token.TokenType.ESCAPE, String.valueOf(nextChar), position - 1));
                position++;
            } else if (current == '<') {
                if (position + 1 < input.length() && input.charAt(position + 1) == '/') {
                    if (position + 2 < input.length() && input.charAt(position + 2) == '>') {
                        tokens.add(new Token(Token.TokenType.TAG_CLOSE, "</>", position));
                        position += 3;
                    } else {
                        int closePos = findMatchingBracket(position);
                        if (closePos != -1) {
                            String tagContent = input.substring(position + 2, closePos);
                            tokens.add(new Token(Token.TokenType.TAG_CLOSE, "</" + tagContent + ">", position));
                            position = closePos + 1;
                        } else {
                            tokens.add(new Token(Token.TokenType.TEXT, "<", position));
                            position++;
                        }
                    }
                } else {
                    int closePos = findMatchingBracket(position);
                    if (closePos != -1) {
                        String tagContent = input.substring(position + 1, closePos);
                        if (tagContent.endsWith("/")) {
                            String tagName = tagContent.substring(0, tagContent.length() - 1).trim();
                            tokens.add(new Token(Token.TokenType.TAG_SELF_CLOSE, tagName, position));
                        } else {
                            tokens.add(new Token(Token.TokenType.TAG_OPEN, tagContent, position));
                        }
                        position = closePos + 1;
                    } else {
                        tokens.add(new Token(Token.TokenType.TEXT, "<", position));
                        position++;
                    }
                }
            } else {
                int textEnd = findTextEnd(position);
                String textContent = input.substring(position, textEnd);
                if (!textContent.isEmpty()) {
                    tokens.add(new Token(Token.TokenType.TEXT, textContent, position));
                }
                position = textEnd;
            }
        }

        tokens.add(new Token(Token.TokenType.EOF, "", position));
        return tokens;
    }

    private int findTextEnd(int startPos) {
        for (int scan = startPos; scan < input.length(); scan++) {
            char current = input.charAt(scan);
            if (current == '<') {
                return scan;
            }
            if (current == '\\' && scan + 1 < input.length()) {
                char next = input.charAt(scan + 1);
                if (next == '<' || next == '\\') {
                    return scan;
                }
            }
        }
        return input.length();
    }

    private int findMatchingBracket(int startPos) {
        if (startPos >= input.length() || input.charAt(startPos) != '<') {
            return -1;
        }

        int pos = startPos + 1;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        int depth = 0;

        while (pos < input.length()) {
            char c = input.charAt(pos);

            if (c == '\\' && pos + 1 < input.length()) {
                pos += 2;
                continue;
            }

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (!inSingleQuote && !inDoubleQuote) {
                if (c == '<') {
                    depth++;
                } else if (c == '>') {
                    if (depth == 0) {
                        return pos;
                    }
                    depth--;
                }
            }

            pos++;
        }

        return -1;
    }
}
