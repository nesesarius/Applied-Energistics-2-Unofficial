package appeng.client.gui.implementations.wirelesskit;

/**
 * 9x9 glyphs of {@code textures/guis/wirelesskit.png}, laid out in this order at y = 48 with a 10 px step. All of them
 * are white and tinted when drawn, except the warning sign.
 */
public enum WirelessKitIcon {

    PENCIL,
    UNLINK,
    TRASH,
    SIZE_SMALL,
    SIZE_NORMAL,
    SIZE_LARGE,
    SIZE_DYNAMIC,
    COLLAPSED,
    EXPANDED,
    WARNING;

    public static final int SIZE = 9;

    public int u() {
        return this.ordinal() * 10;
    }

    public int v() {
        return 48;
    }

    public boolean isTinted() {
        return this != WARNING;
    }
}
