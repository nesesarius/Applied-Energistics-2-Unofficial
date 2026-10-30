package appeng.client.gui.implementations.wirelesskit;

import static appeng.client.gui.implementations.wirelesskit.WirelessKitTree.CHANNEL_CAPACITY;
import static appeng.client.gui.implementations.wirelesskit.WirelessKitTree.HUB_LINKS;
import static appeng.client.gui.implementations.wirelesskit.WirelessKitTree.coords;
import static appeng.client.gui.implementations.wirelesskit.WirelessKitTree.customName;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;

import appeng.api.AEApi;
import appeng.api.util.AEColor;
import appeng.api.util.DimensionalCoord;
import appeng.api.util.NamedDimensionalCoord;
import appeng.client.gui.implementations.wirelesskit.WirelessKitRow.Button;
import appeng.client.gui.implementations.wirelesskit.WirelessKitRow.Look;
import appeng.client.gui.implementations.wirelesskit.WirelessKitRow.Style;
import appeng.client.gui.implementations.wirelesskit.WirelessKitRow.Toggle;
import appeng.client.gui.implementations.wirelesskit.WirelessKitTree.Hub;
import appeng.client.gui.implementations.wirelesskit.WirelessKitTree.Network;
import appeng.client.gui.implementations.wirelesskit.WirelessKitTree.Pair;
import appeng.core.localization.ButtonToolTips;
import appeng.core.localization.ColorUtils;
import appeng.core.localization.GuiText;
import appeng.core.localization.PlayerMessages;
import appeng.helpers.WirelessToolDataObject;

/**
 * Turns the {@link WirelessKitTree} into the rows of the list: applies folding and the name search, and wires every
 * click to an {@link Actions} call.
 */
public final class WirelessKitRowBuilder {

    /**
     * What rows can ask the GUI to do.
     */
    public interface Actions {

        /** A hub or a free connector becomes the bind target, any other connector is only outlined. */
        void setActive(DimensionalCoord device);

        void bind(List<DimensionalCoord> sources);

        void unbind(DimensionalCoord device);

        /** Unbinds everything from the hub on the second click. */
        void unbindAll(DimensionalCoord hub);

        boolean isUnbindArmed(DimensionalCoord hub);

        /** Opens the rename bar over the row. */
        void rename(WirelessKitRow row, String current, Consumer<String> submit);

        void renameDevice(DimensionalCoord device, String name);

        /** Opens the colour palette for {@link WirelessKitRow#recolorDevices}. */
        void recolor(WirelessKitRow row);

        void removeNetwork(DimensionalCoord anchor);

        /** Folding or ticks changed, rows have to be rebuilt. */
        void viewChanged();
    }

    private static final int MAX_DEPTH = 2;

    private final WirelessKitTree tree;
    private final WirelessKitViewState state;
    private final Actions actions;
    private final String search;
    @Nullable
    private final WirelessToolDataObject bindTarget;
    private final List<WirelessKitRow> rows = new ArrayList<>();

    public WirelessKitRowBuilder(WirelessKitTree tree, WirelessKitViewState state, Actions actions) {
        this.tree = tree;
        this.state = state;
        this.actions = actions;
        this.search = state.search.trim();
        this.bindTarget = tree.getBindTarget();
    }

    public List<WirelessKitRow> build() {
        if (this.tree.getNetwork() != null) this.addNetwork(this.tree.getNetwork());
        this.addFreeBlock();

        if (this.tree.isNetworkElsewhere()) this.addNote(GuiText.WirelessKitNetworkElsewhere.getLocal());
        else if (this.rows.isEmpty()) this.addNote(GuiText.WirelessKitEmpty.getLocal());

        this.computeGuides();
        return this.rows;
    }

    /**
     * The name search of the Interface Terminal ({@code GuiInterfaceTerminal.interfaceSectionMatchesSearchTerm}):
     * case-insensitive, every word has to occur in the name, a term in double quotes is one phrase.
     */
    static boolean matchesName(String name, String search) {
        if (search.isEmpty()) return true;

        final String haystack = name.toLowerCase();
        if (search.length() >= 2 && search.startsWith("\"") && search.endsWith("\"")) {
            return haystack.contains(search.substring(1, search.length() - 1).toLowerCase());
        }
        for (String word : search.toLowerCase().split("\\s+")) {
            if (!haystack.contains(word)) return false;
        }
        return true;
    }

    // ---- the network

    private void addNetwork(Network network) {
        final boolean filtering = !this.search.isEmpty();
        final List<Hub> hubs = network.hubs.stream().filter(h -> matchesName(this.label(h.device), this.search))
                .collect(Collectors.toList());
        final List<Pair> pairs = network.pairs.stream().filter(p -> matchesName(this.label(p.leader), this.search))
                .collect(Collectors.toList());
        final List<WirelessToolDataObject> loose = network.free.stream()
                .filter(c -> matchesName(this.label(c), this.search)).collect(Collectors.toList());

        final WirelessKitRow row = new WirelessKitRow(Style.HEADER, 0, network.anchor);
        // a search always shows what it found
        final boolean expanded = filtering || this.state.isNetworkExpanded(network.anchor, true);
        if (!filtering) this.setToggle(row, expanded, () -> this.state.setNetworkExpanded(network.anchor, !expanded));
        row.onClick = row.onToggle;
        row.icon = AEApi.instance().definitions().parts().cableGlass().stack(AEColor.Transparent, 1);
        row.title = GuiText.WirelessKitNetworkAt.getLocal(coords(network.anchor));
        row.titleColor = ColorUtils.wirelessKitText.getColor();
        row.subtitle = GuiText.WirelessKitNetworkInfo.getLocal(network.hubs.size(), network.getConnectorCount())
                + (network.free.isEmpty() ? "" : " · " + GuiText.WirelessKitNetworkFree.getLocal(network.free.size()));
        row.subtitleColor = ColorUtils.wirelessKitTextDim.getColor();
        row.lower.add(
                Button.icon(
                        WirelessKitIcon.TRASH,
                        () -> this.actions.removeNetwork(network.anchor),
                        tooltip(ButtonToolTips.WirelessKitRemoveNetwork, ButtonToolTips.WirelessKitRemoveNetworkDesc)));

        final List<WirelessToolDataObject> devices = new ArrayList<>();
        for (Hub hub : network.hubs) {
            devices.add(hub.device);
            devices.addAll(hub.connectors);
        }
        for (Pair pair : network.pairs) {
            devices.add(pair.leader);
            if (pair.follower != null) devices.add(pair.follower);
        }
        devices.addAll(network.free);
        row.devices = named(devices);
        row.highlightPrefix = PlayerMessages.WirelessHighlighterNetworkPrefix.getLocal(row.title);
        row.tooltip = lines(
                row.title,
                filtering ? null : hint(GuiText.WirelessKitHintFoldLeft),
                hint(GuiText.WirelessKitHintHighlight));
        this.rows.add(row);

        if (!expanded) return;
        for (Hub hub : hubs) this.addHub(hub);
        for (Pair pair : pairs) this.addPair(pair);
        for (WirelessToolDataObject connector : loose) this.addNetworkFree(connector);
        if (filtering && hubs.isEmpty() && pairs.isEmpty() && loose.isEmpty()) {
            this.addNote(GuiText.WirelessKitNothingFound.getLocal());
        }
    }

    private void addHub(Hub hub) {
        final WirelessToolDataObject device = hub.device;
        final boolean isTarget = this.tree.isBindTarget(device);
        final boolean expanded = this.state.isGroupExpanded(device.cord, isTarget);

        final WirelessKitRow row = new WirelessKitRow(Style.DEVICE, 1, device.cord);
        if (!hub.connectors.isEmpty()) {
            this.setToggle(row, expanded, () -> this.state.setGroupExpanded(device.cord, !expanded));
        }
        row.onRightClick = row.onToggle;
        row.onClick = this.activate(device);
        row.icon = deviceIcon(device);
        row.title = this.label(device);
        row.titleColor = isTarget ? ColorUtils.wirelessKitTextTarget.getColor() : ColorUtils.wirelessKitText.getColor();
        row.subtitle = GuiText.WirelessKitLinks.getLocal(HUB_LINKS - device.slots, HUB_LINKS);
        row.subtitleColor = device.slots == 0 ? ColorUtils.wirelessKitTextBad.getColor()
                : ColorUtils.wirelessKitTextDim.getColor();
        row.badge = isTarget ? GuiText.WirelessKitTargetBadge.getLocal() : null;
        row.meter = device.channels;
        row.active = this.tree.isActive(device);

        row.lower.add(this.renameButton(row, device, ButtonToolTips.WirelessKitRenameGroup));
        final List<DimensionalCoord> group = new ArrayList<>();
        group.add(device.cord);
        hub.connectors.forEach(c -> group.add(c.cord));
        this.addColorButton(row, device, group, ButtonToolTips.WirelessKitRecolorGroup);

        final boolean armed = this.actions.isUnbindArmed(device.cord);
        row.lower.add(
                Button.icon(
                        WirelessKitIcon.UNLINK,
                        () -> this.actions.unbindAll(device.cord),
                        armed ? tooltip(ButtonToolTips.WirelessKitUnbindConfirm)
                                : tooltip(ButtonToolTips.WirelessKitUnbindAll))
                        .enabled(device.isConnected).look(armed ? Look.DANGER : Look.NORMAL));

        final List<WirelessToolDataObject> devices = new ArrayList<>();
        devices.add(device);
        devices.addAll(hub.connectors);
        row.devices = named(devices);
        row.highlightPrefix = PlayerMessages.WirelessHighlighterPrefix.getLocal();
        row.tooltip = lines(
                row.title,
                GuiText.WirelessKitPosition.getLocal(coords(device.cord)),
                GuiText.WirelessKitLinks.getLocal(HUB_LINKS - device.slots, HUB_LINKS),
                GuiText.WirelessKitChannelsOf.getLocal(device.channels, CHANNEL_CAPACITY),
                isTarget ? null : hint(GuiText.WirelessKitHintTarget),
                hub.connectors.isEmpty() ? null : hint(GuiText.WirelessKitHintFoldRight),
                hint(GuiText.WirelessKitHintHighlight));
        this.rows.add(row);

        if (!expanded) return;
        for (WirelessToolDataObject connector : hub.connectors) this.addChild(device, connector);
    }

    /**
     * A pair reads like a hub with one connector: the leading end, and the other one under it.
     */
    private void addPair(Pair pair) {
        final WirelessToolDataObject leader = pair.leader;
        final boolean expanded = this.state.isGroupExpanded(leader.cord, true);

        final WirelessKitRow row = new WirelessKitRow(Style.DEVICE, 1, leader.cord);
        if (pair.follower != null) {
            this.setToggle(row, expanded, () -> this.state.setGroupExpanded(leader.cord, !expanded));
        }
        row.onRightClick = row.onToggle;
        row.onClick = this.activate(leader);
        row.icon = deviceIcon(leader);
        row.title = this.label(leader);
        row.titleColor = ColorUtils.wirelessKitText.getColor();
        row.subtitle = GuiText.WirelessKitPairInfo.getLocal(leader.channels) + " · "
                + distance(leader.cord, pair.followerCoord);
        row.subtitleColor = ColorUtils.wirelessKitTextDim.getColor();
        row.active = this.tree.isActive(leader);

        // a rename goes over the link to the other end as well
        row.lower.add(this.renameButton(row, leader, ButtonToolTips.WirelessKitRenamePair));
        this.addColorButton(
                row,
                leader,
                Arrays.asList(leader.cord, pair.followerCoord),
                ButtonToolTips.WirelessKitRecolorPair);
        row.lower.add(this.unbindButton(leader));

        final List<NamedDimensionalCoord> devices = new ArrayList<>();
        devices.add(new NamedDimensionalCoord(leader.cord, leader.customName));
        devices.add(new NamedDimensionalCoord(pair.followerCoord, leader.customName));
        row.devices = devices;
        row.highlightPrefix = PlayerMessages.WirelessHighlighterPrefix.getLocal();
        row.tooltip = lines(
                row.title,
                GuiText.WirelessKitPosition.getLocal(coords(leader.cord)),
                GuiText.WirelessKitChannelsOf.getLocal(leader.channels, CHANNEL_CAPACITY),
                row.active ? null : hint(GuiText.WirelessKitHintPick),
                pair.follower == null ? null : hint(GuiText.WirelessKitHintFoldRight),
                hint(GuiText.WirelessKitHintHighlight));
        this.rows.add(row);

        if (expanded && pair.follower != null) this.addChild(leader, pair.follower);
    }

    /**
     * A connector under a hub or the other end of a pair. A link carries the name over, so it has no rename of its own:
     * that would rename the whole group.
     */
    private void addChild(WirelessToolDataObject parent, WirelessToolDataObject device) {
        final WirelessKitRow row = new WirelessKitRow(Style.DEVICE, 2, device.cord);
        final String name = customName(device);
        row.title = name != null && !name.equals(parent.customName) ? name : coords(device.cord);
        row.titleColor = ColorUtils.wirelessKitText.getColor();
        row.subtitle = GuiText.WirelessKitChannels.getLocal(device.channels) + " · "
                + distance(device.cord, parent.cord);
        row.subtitleColor = ColorUtils.wirelessKitTextDim.getColor();
        row.icon = deviceIcon(device);
        row.active = this.tree.isActive(device);
        row.onClick = this.activate(device);

        this.addColorButton(row, device, Collections.singletonList(device.cord), ButtonToolTips.WirelessKitRecolor);
        row.lower.add(this.unbindButton(device));

        row.devices = named(Arrays.asList(device, parent));
        row.highlightPrefix = PlayerMessages.WirelessHighlighterPrefix.getLocal();
        row.tooltip = lines(
                coords(device.cord),
                GuiText.WirelessKitChannelsOf.getLocal(device.channels, CHANNEL_CAPACITY),
                row.active ? null : hint(GuiText.WirelessKitHintPick),
                hint(GuiText.WirelessKitHintHighlight));
        this.rows.add(row);
    }

    /**
     * A free connector wired into the network: it can be the target for another one, or be bound to the target itself.
     */
    private void addNetworkFree(WirelessToolDataObject device) {
        final WirelessKitRow row = this.freeConnectorRow(device);
        row.subtitle = this.freeSubtitle(device, GuiText.WirelessKitNetworkFreeInfo.getLocal(device.channels));
        if (!this.tree.isBindTarget(device)) {
            this.addBindButton(row, GuiText.WirelessKitBind.getLocal(), Collections.singletonList(device));
        }
        this.rows.add(row);
    }

    // ---- free connectors from other grids

    private void addFreeBlock() {
        final List<WirelessToolDataObject> free = this.tree.getFree();
        if (free.isEmpty()) return;

        // a pair is exactly two connectors, so ticks only make sense for a hub target
        final boolean massBind = this.bindTarget == null || this.bindTarget.isHub;
        final List<WirelessToolDataObject> selected = free.stream().filter(c -> this.state.isChecked(c.cord))
                .collect(Collectors.toList());

        final WirelessKitRow header = new WirelessKitRow(Style.HEADER, 0, null);
        if (massBind) {
            final boolean all = selected.size() == free.size();
            header.checked = all;
            header.onCheck = () -> {
                free.forEach(c -> this.state.setChecked(c.cord, !all));
                this.actions.viewChanged();
            };
        }
        header.icon = deviceIcon(null);
        header.title = GuiText.WirelessKitFreeTitle.getLocal(free.size());
        header.titleColor = ColorUtils.wirelessKitText.getColor();
        header.subtitle = this.bindTarget != null ? GuiText.WirelessKitTargetIs.getLocal(this.label(this.bindTarget))
                : GuiText.WirelessKitNoTarget.getLocal();
        header.subtitleColor = this.bindTarget != null ? ColorUtils.wirelessKitTextTarget.getColor()
                : ColorUtils.wirelessKitTextDim.getColor();
        header.devices = named(free);
        header.highlightPrefix = PlayerMessages.WirelessHighlighterPrefix.getLocal();
        header.tooltip = lines(
                header.title,
                GuiText.WirelessKitFreeHint.getLocal(),
                massBind ? hint(GuiText.WirelessKitHintSelectAll) : null,
                hint(GuiText.WirelessKitHintHighlight));
        if (massBind) this.addBindButton(header, GuiText.WirelessKitBindSelected.getLocal(selected.size()), selected);
        this.rows.add(header);

        for (WirelessToolDataObject connector : free) this.addFree(connector, massBind);
    }

    private void addFree(WirelessToolDataObject device, boolean massBind) {
        final WirelessKitRow row = this.freeConnectorRow(device);
        if (massBind) {
            final boolean checked = this.state.isChecked(device.cord);
            row.checked = checked;
            row.onCheck = () -> {
                this.state.setChecked(device.cord, !checked);
                this.actions.viewChanged();
            };
        }

        row.subtitle = this.freeSubtitle(device, GuiText.WirelessKitChannelsEstimate.getLocal(device.channels));
        if (!this.tree.isBindTarget(device)) {
            this.addBindButton(row, GuiText.WirelessKitBind.getLocal(), Collections.singletonList(device));
        }
        this.rows.add(row);
    }

    /**
     * Channels of a free connector, then the distance to the bind target and the power the link would draw.
     */
    private String freeSubtitle(WirelessToolDataObject device, String channels) {
        if (this.bindTarget == null || this.tree.isBindTarget(device)) return channels;

        final double meters = WirelessKitTree.distance(device.cord, this.bindTarget.cord);
        return channels + " · "
                + GuiText.WirelessKitDistance.getLocal(Math.round(meters))
                + " "
                + GuiText.WirelessKitPower.getLocal(formatPower(WirelessKitTree.linkPower(meters)));
    }

    private WirelessKitRow freeConnectorRow(WirelessToolDataObject device) {
        final boolean isTarget = this.tree.isBindTarget(device);

        final WirelessKitRow row = new WirelessKitRow(Style.DEVICE, 1, device.cord);
        row.icon = deviceIcon(device);
        row.title = this.label(device);
        row.titleColor = isTarget ? ColorUtils.wirelessKitTextTarget.getColor() : ColorUtils.wirelessKitText.getColor();
        row.subtitleColor = ColorUtils.wirelessKitTextDim.getColor();
        row.badge = isTarget ? GuiText.WirelessKitTargetBadge.getLocal() : null;
        row.active = this.tree.isActive(device);
        row.onClick = this.activate(device);

        row.lower.add(this.renameButton(row, device));
        this.addColorButton(row, device, Collections.singletonList(device.cord), ButtonToolTips.WirelessKitRecolor);

        row.devices = named(Collections.singletonList(device));
        row.highlightPrefix = PlayerMessages.WirelessHighlighterPrefix.getLocal();
        row.tooltip = lines(
                row.title,
                GuiText.WirelessKitPosition.getLocal(coords(device.cord)),
                GuiText.WirelessKitChannelsOf.getLocal(device.channels, CHANNEL_CAPACITY),
                isTarget ? null : hint(GuiText.WirelessKitHintTarget),
                hint(GuiText.WirelessKitHintHighlight));
        return row;
    }

    /**
     * Bind button on the upper line, with a warning sign in front of it when the forecast overflows the channels.
     */
    private void addBindButton(WirelessKitRow row, String label, List<WirelessToolDataObject> sources) {
        final WirelessToolDataObject target = this.bindTarget;
        final List<String> tooltip;
        final boolean enabled;
        if (target == null) {
            tooltip = tooltip(ButtonToolTips.WirelessKitNeedTarget);
            enabled = false;
        } else if (target.isHub && target.slots == 0) {
            tooltip = tooltip(ButtonToolTips.WirelessKitHubFull);
            enabled = false;
        } else if (sources.isEmpty()) {
            tooltip = tooltip(ButtonToolTips.WirelessKitNothingSelected);
            enabled = false;
        } else {
            tooltip = lines(ButtonToolTips.WirelessKitBindTo.getLocal(this.label(target)));
            enabled = true;
            if (target.isHub && sources.size() > target.slots) {
                tooltip.add(ButtonToolTips.WirelessKitNotEnoughLinks.getLocal(target.slots));
            }

            int forecast = target.channels;
            for (WirelessToolDataObject source : sources) forecast += source.channels;
            if (forecast > CHANNEL_CAPACITY) {
                row.upper.add(
                        Button.indicator(
                                WirelessKitIcon.WARNING,
                                lines(
                                        ButtonToolTips.WirelessKitForecast.getLocal(forecast, CHANNEL_CAPACITY),
                                        ButtonToolTips.WirelessKitForecastDesc.getLocal())));
            }
        }

        final List<DimensionalCoord> coords = sources.stream().map(d -> d.cord).collect(Collectors.toList());
        row.upper.add(Button.text(label, () -> this.actions.bind(coords), tooltip).enabled(enabled));
    }

    // ---- buttons shared by several rows

    private Button renameButton(WirelessKitRow row, WirelessToolDataObject device, ButtonToolTips... description) {
        final String name = customName(device);
        return Button.icon(
                WirelessKitIcon.PENCIL,
                () -> this.actions.rename(
                        row,
                        name == null ? "" : name,
                        newName -> this.actions.renameDevice(device.cord, newName)),
                tooltip(ButtonToolTips.WirelessKitRename, description));
    }

    private Button unbindButton(WirelessToolDataObject device) {
        return Button.icon(
                WirelessKitIcon.UNLINK,
                () -> this.actions.unbind(device.cord),
                tooltip(ButtonToolTips.WirelessKitUnbind, ButtonToolTips.WirelessKitUnbindToFree));
    }

    /**
     * Adds the colour button and records on the row what it recolours: the palette reads it from the current row, so a
     * refresh while it is open recolours the current group.
     */
    private void addColorButton(WirelessKitRow row, WirelessToolDataObject device, List<DimensionalCoord> devices,
            ButtonToolTips description) {
        row.recolorDevices = devices;
        row.lower.add(
                Button.swatch(
                        device.color.mediumVariant,
                        () -> this.actions.recolor(row),
                        lines(ButtonToolTips.WirelessKitColor.getLocal(device.color), description.getLocal())));
    }

    /** Left-click makes a device the active one. */
    @Nullable
    private Runnable activate(WirelessToolDataObject device) {
        return this.tree.isActive(device) ? null : () -> this.actions.setActive(device.cord);
    }

    private void setToggle(WirelessKitRow row, boolean expanded, Runnable toggle) {
        row.toggle = expanded ? Toggle.EXPANDED : Toggle.COLLAPSED;
        row.onToggle = () -> {
            toggle.run();
            this.actions.viewChanged();
        };
    }

    private void addNote(String text) {
        final WirelessKitRow row = new WirelessKitRow(Style.NOTE, 0, null);
        row.title = text;
        row.titleColor = ColorUtils.wirelessKitTextDim.getColor();
        this.rows.add(row);
    }

    /**
     * Fills in which tree guide lines pass through each row, walking from the bottom up.
     */
    private void computeGuides() {
        // below[d]: a row of depth d follows before anything shallower does
        final boolean[] below = new boolean[MAX_DEPTH + 1];
        for (int i = this.rows.size() - 1; i >= 0; i--) {
            final WirelessKitRow row = this.rows.get(i);
            row.parent = i + 1 < this.rows.size() && this.rows.get(i + 1).depth > row.depth;
            row.lastChild = !below[row.depth];
            for (int d = 1; d < row.depth; d++) {
                if (below[d]) row.openGuides |= 1 << d;
            }
            below[row.depth] = true;
            for (int d = row.depth + 1; d <= MAX_DEPTH; d++) below[d] = false;
        }
    }

    // ---- helpers

    private String label(WirelessToolDataObject device) {
        final String name = customName(device);
        if (name != null) return name;
        return device.isHub ? GuiText.WirelessKitHubAt.getLocal(coords(device.cord)) : coords(device.cord);
    }

    private static String distance(DimensionalCoord a, DimensionalCoord b) {
        return GuiText.WirelessKitDistance.getLocal(Math.round(WirelessKitTree.distance(a, b)));
    }

    private static String formatPower(double power) {
        return power >= 10 ? String.valueOf(Math.round(power)) : String.format(Locale.ROOT, "%.1f", power);
    }

    private static ItemStack deviceIcon(@Nullable WirelessToolDataObject device) {
        final ItemStack stack = WirelessKitTree.blockOf(device != null && device.isHub).maybeStack(1).orNull();
        // the item damage carries the colour, 0 is fluix
        if (stack != null && device != null && device.color != AEColor.Transparent) {
            stack.setItemDamage(device.color.ordinal() + 1);
        }
        return stack;
    }

    private static List<NamedDimensionalCoord> named(List<WirelessToolDataObject> devices) {
        return devices.stream().map(d -> new NamedDimensionalCoord(d.cord, d.customName)).collect(Collectors.toList());
    }

    /** Tooltips show every line after the first in grey. */
    private static List<String> tooltip(ButtonToolTips title, ButtonToolTips... description) {
        final List<String> lines = lines(title.getLocal());
        for (ButtonToolTips line : description) lines.add(line.getLocal());
        return lines;
    }

    private static String hint(GuiText hint) {
        return EnumChatFormatting.DARK_GRAY + hint.getLocal();
    }

    /** Null lines are skipped. */
    private static List<String> lines(String... lines) {
        final List<String> list = new ArrayList<>(lines.length);
        for (String line : lines) if (line != null) list.add(line);
        return list;
    }
}
