package duowatchdog.core.ui;

/** Every colour the dashboard and the status bar paint, light and dark; the light ones are the approved mockup's. */
public enum Tone {
    BACKGROUND(0xFFFFFF, 0x2B2D30),
    TEXT(0x1E1F22, 0xDFE1E5),
    /** "of 300 credits used". */
    MUTED(0x4D515C, 0xB4B8BF),
    /** Captions, axis labels, notes. */
    SECONDARY(0x6C707E, 0x8C8F96),
    BORDER(0xEBECF0, 0x393B40),
    TRACK(0xEBECF0, 0x393B40),
    ACCENT(0x3574F0, 0x548AF7),
    /** The bar under the pointer. */
    ACCENT_STRONG(0x2B5FCC, 0x83A9F9),
    AXIS(0xC9CCD6, 0x55585E),
    GRID(0xEBECF0, 0x393B40),
    POPUP(0xFFFFFF, 0x393B40),
    POPUP_BORDER(0xC9CCD6, 0x4E5157),
    OK(0x1F6F35, 0x73C383),
    OK_ICON(0x208A3C, 0x57A863),
    OK_BACKGROUND(0xE8F5EC, 0x253627),
    WARNING(0xC46A00, 0xE3A24A),
    WARNING_TRACK(0xF1D7AE, 0x5E4A2A),
    WARNING_BACKGROUND(0xFFF4E5, 0x3D3222),
    ERROR(0xC9302C, 0xE5605A),
    ERROR_BACKGROUND(0xFDECEC, 0x452B2B),
    /** The status bar chip and its mini meter. */
    CHIP_BACKGROUND(0xE6EDFC, 0x2C3B5C),
    CHIP_TRACK(0xDFE1E5, 0x4A4D54);

    final int light;
    final int dark;

    Tone(int light, int dark) {
        this.light = light;
        this.dark = dark;
    }
}
