package appeng.client.gui.implementations.wirelesskit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import appeng.api.util.DimensionalCoord;
import appeng.api.util.NamedDimensionalCoord;

/**
 * One line of the Super mode list: what it shows and what its clicks do. Rows are built by
 * {@link WirelessKitRowBuilder} and laid out and drawn by the GUI. A row has two text lines; buttons on the right sit
 * on the upper or the lower line.
 */
public final class WirelessKitRow {

    public enum Style {
        /** Network and free block headers. */
        HEADER,
        DEVICE,
        /** Grey hint text. */
        NOTE
    }

    public enum Toggle {
        NONE,
        COLLAPSED,
        EXPANDED
    }

    public enum Look {
        NORMAL,
        DANGER
    }

    public static final class Button {

        @Nullable
        public final WirelessKitIcon icon;
        @Nullable
        public final String label;
        /** Null for an indicator that only shows a tooltip. */
        @Nullable
        public final Runnable action;
        public final List<String> tooltip;
        public boolean enabled = true;
        public Look look = Look.NORMAL;
        /** RGB of a colour swatch drawn instead of an icon. */
        @Nullable
        public Integer swatch;

        private Button(@Nullable WirelessKitIcon icon, @Nullable String label, @Nullable Runnable action,
                List<String> tooltip) {
            this.icon = icon;
            this.label = label;
            this.action = action;
            this.tooltip = tooltip;
        }

        public static Button icon(WirelessKitIcon icon, Runnable action, List<String> tooltip) {
            return new Button(icon, null, action, tooltip);
        }

        public static Button text(String label, Runnable action, List<String> tooltip) {
            return new Button(null, label, action, tooltip);
        }

        public static Button indicator(WirelessKitIcon icon, List<String> tooltip) {
            return new Button(icon, null, null, tooltip);
        }

        public static Button swatch(int rgb, Runnable action, List<String> tooltip) {
            final Button button = new Button(null, null, action, tooltip);
            button.swatch = rgb;
            return button;
        }

        public Button enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Button look(Look look) {
            this.look = look;
            return this;
        }
    }

    public final Style style;
    public final int depth;
    /** The device or network anchor the row stands for. */
    @Nullable
    public final DimensionalCoord key;

    public Toggle toggle = Toggle.NONE;
    @Nullable
    public Runnable onToggle;
    /** Null when the row has no checkbox. */
    @Nullable
    public Boolean checked;
    @Nullable
    public Runnable onCheck;

    @Nullable
    public ItemStack icon;
    public String title = "";
    public int titleColor;
    public String subtitle = "";
    public int subtitleColor;
    /** Short mark after the subtitle, in the target colour. */
    @Nullable
    public String badge;

    /** Channel bar on the upper line, used out of {@link WirelessKitTree#CHANNEL_CAPACITY}; -1 hides it. */
    public int meter = -1;

    public final List<Button> upper = new ArrayList<>();
    public final List<Button> lower = new ArrayList<>();

    /** Tinted as the device outlined in the world. */
    public boolean active;

    @Nullable
    public Runnable onClick;
    @Nullable
    public Runnable onRightClick;

    /** What the colour button of the row recolours; the palette reads it when a colour is picked. */
    public List<DimensionalCoord> recolorDevices = Collections.emptyList();

    /** Outlined in the world on hover, highlighted with chat messages on Shift+click. */
    public List<NamedDimensionalCoord> devices = Collections.emptyList();
    public String highlightPrefix = "";
    public List<String> tooltip = Collections.emptyList();

    // tree guides, filled in by the builder once all rows are known
    boolean lastChild;
    boolean parent;
    /** Bit {@code d} is set when the guide line of depth {@code d} goes on below this row. */
    int openGuides;

    WirelessKitRow(Style style, int depth, @Nullable DimensionalCoord key) {
        this.style = style;
        this.depth = depth;
        this.key = key;
    }

    public boolean isLastChild() {
        return this.lastChild;
    }

    /** The next row is a child of this one. */
    public boolean isParent() {
        return this.parent;
    }

    public boolean hasOpenGuide(int depth) {
        return (this.openGuides & 1 << depth) != 0;
    }

    public boolean isSameRow(WirelessKitRow other) {
        return this.style == other.style && this.depth == other.depth
                && (this.key == null ? other.key == null : this.key.equals(other.key));
    }
}
