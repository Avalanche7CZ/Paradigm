package eu.avalanche7.paradigm.platform.Interfaces;

public interface ITextStyle {
    ITextStyle withColor(String hexColor);
    ITextStyle withFormatting(IFormatting formatting);
    ITextStyle withClickEvent(IClickEvent clickEvent);
    ITextStyle withHoverEvent(IHoverEvent hoverEvent);
    ITextStyle withParent(ITextStyle parent);
    ITextStyle copy();
    Object getNativeStyle();

    enum IFormatting {
        BLACK, DARK_BLUE, DARK_GREEN, DARK_AQUA, DARK_RED, DARK_PURPLE,
        GOLD, GRAY, DARK_GRAY, BLUE, GREEN, AQUA, RED, LIGHT_PURPLE,
        YELLOW, WHITE, BOLD, ITALIC, UNDERLINE, STRIKETHROUGH, OBFUSCATED, RESET;

        public boolean isColor() {
            return this.ordinal() < BOLD.ordinal();
        }
    }

    interface IClickEvent {
        enum Action { OPEN_URL, RUN_COMMAND, SUGGEST_COMMAND, COPY_TO_CLIPBOARD }
        Action getAction();
        String getValue();
    }

    interface IHoverEvent {
        enum Action { SHOW_TEXT }
        Action getAction();
        IComponent getValue();
    }
}

