package duowatchdog.core.ui;

import java.awt.Color;
import java.awt.Font;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.BinaryOperator;

/**
 * The colours, base font and scale the components paint with. The plugin pairs each tone as a {@code JBColor}, so
 * the colours follow the IDE theme; tests take the light or dark set as it is.
 */
public final class Theme {

    private final Map<Tone, Color> colors = new EnumMap<>(Tone.class);
    private final Font font;
    private final float scale;

    private Theme(BinaryOperator<Color> pair, Font font, float scale) {
        for (Tone tone : Tone.values()) {
            colors.put(tone, pair.apply(new Color(tone.light), new Color(tone.dark)));
        }
        this.font = font;
        this.scale = scale;
    }

    /** @param pair makes one colour of a tone's light and dark ones, e.g. {@code JBColor::new} */
    public static Theme paired(BinaryOperator<Color> pair, Font font, float scale) {
        return new Theme(pair, font, scale);
    }

    public static Theme light(Font font) {
        return new Theme((light, dark) -> light, font, 1f);
    }

    public static Theme dark(Font font) {
        return new Theme((light, dark) -> dark, font, 1f);
    }

    public Color color(Tone tone) {
        return colors.get(tone);
    }

    /** The mockup's pixels at the IDE's scale. */
    public int px(double size) {
        return Math.round((float) size * scale);
    }

    public Font font(double size, int style) {
        return font.deriveFont(style, (float) size * scale);
    }
}
