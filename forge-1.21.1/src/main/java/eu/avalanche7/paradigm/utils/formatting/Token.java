package eu.avalanche7.paradigm.utils.formatting;

public class Token {
    public enum TokenType {
        TEXT,
        TAG_OPEN,
        TAG_CLOSE,
        TAG_SELF_CLOSE,
        ESCAPE,
        EOF
    }

    private final TokenType type;
    private final String value;
    private final int position;

    public Token(TokenType type, String value, int position) {
        this.type = type;
        this.value = value;
        this.position = position;
    }

    public TokenType getType() {
        return type;
    }

    public String getValue() {
        return value;
    }

    public int getPosition() {
        return position;
    }

    @Override
    public String toString() {
        return "Token(" + type + ", '" + value + "', " + position + ")";
    }
}

