package appeng.client.gui.implementations.wirelesskit;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import appeng.api.util.DimensionalCoord;
import appeng.core.localization.ButtonToolTips;

/**
 * Client-only state of the Super mode list that outlives the GUI until the game is closed: what is folded, the search
 * text, ticked free connectors and the window size.
 */
public final class WirelessKitViewState {

    public enum WindowSize {

        SMALL(6, WirelessKitIcon.SIZE_SMALL, ButtonToolTips.WirelessKitSizeSmall),
        NORMAL(9, WirelessKitIcon.SIZE_NORMAL, ButtonToolTips.WirelessKitSizeNormal),
        LARGE(12, WirelessKitIcon.SIZE_LARGE, ButtonToolTips.WirelessKitSizeLarge),
        DYNAMIC(Integer.MAX_VALUE, WirelessKitIcon.SIZE_DYNAMIC, ButtonToolTips.WirelessKitSizeDynamic);

        public final int rows;
        public final WirelessKitIcon icon;
        public final ButtonToolTips tooltip;

        WindowSize(int rows, WirelessKitIcon icon, ButtonToolTips tooltip) {
            this.rows = rows;
            this.icon = icon;
            this.tooltip = tooltip;
        }

        public WindowSize next(boolean backwards) {
            final WindowSize[] values = values();
            return values[(this.ordinal() + (backwards ? values.length - 1 : 1)) % values.length];
        }
    }

    public static final WirelessKitViewState INSTANCE = new WirelessKitViewState();

    // explicit choices only; a missing entry means "use the default"
    private final Map<DimensionalCoord, Boolean> networkExpanded = new HashMap<>();
    /** Hubs and leading ends of pairs. */
    private final Map<DimensionalCoord, Boolean> groupExpanded = new HashMap<>();
    private final Set<DimensionalCoord> checked = new LinkedHashSet<>();

    public String search = "";
    public WindowSize size = WindowSize.DYNAMIC;

    private WirelessKitViewState() {}

    public boolean isNetworkExpanded(DimensionalCoord anchor, boolean byDefault) {
        return this.networkExpanded.getOrDefault(anchor, byDefault);
    }

    public void setNetworkExpanded(DimensionalCoord anchor, boolean expanded) {
        this.networkExpanded.put(anchor, expanded);
    }

    public boolean isGroupExpanded(DimensionalCoord head, boolean byDefault) {
        return this.groupExpanded.getOrDefault(head, byDefault);
    }

    public void setGroupExpanded(DimensionalCoord head, boolean expanded) {
        this.groupExpanded.put(head, expanded);
    }

    /**
     * Keeps a hub that is open by default open when it stops being the bind target, unless it was folded by hand.
     */
    public void keepHubExpanded(DimensionalCoord hub) {
        this.groupExpanded.putIfAbsent(hub, true);
    }

    /**
     * Drops explicit choices, so a new bind target unfolds itself and its network again.
     */
    public void resetExpansion(DimensionalCoord anchor, DimensionalCoord hub) {
        this.networkExpanded.remove(anchor);
        this.groupExpanded.remove(hub);
    }

    public boolean isChecked(DimensionalCoord connector) {
        return this.checked.contains(connector);
    }

    public void setChecked(DimensionalCoord connector, boolean checked) {
        if (checked) this.checked.add(connector);
        else this.checked.remove(connector);
    }

    /**
     * Forget ticks of connectors that are no longer free.
     */
    public void retainChecked(Set<DimensionalCoord> free) {
        this.checked.retainAll(free);
    }
}
