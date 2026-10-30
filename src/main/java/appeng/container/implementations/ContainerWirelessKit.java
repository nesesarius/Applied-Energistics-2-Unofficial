package appeng.container.implementations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ICrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.PlayerSource;
import appeng.api.util.DimensionalCoord;
import appeng.container.AEBaseContainer;
import appeng.core.AELog;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.PacketWirelessToolData;
import appeng.helpers.WireLessToolHelper;
import appeng.helpers.WirelessKitCommand;
import appeng.helpers.WirelessKitCommand.PinType;
import appeng.helpers.WirelessKitCommand.SubCommand;
import appeng.helpers.WirelessToolDataObject;
import appeng.items.contents.WirelessKitObject;
import appeng.tile.networking.TileWirelessBase;
import appeng.tile.networking.TileWirelessConnector;
import appeng.tile.networking.TileWirelessHub;
import appeng.util.Platform;

public class ContainerWirelessKit extends AEBaseContainer {

    private static final int REFRESH_TICKS = 10;

    private final WirelessKitObject toolInv;
    private final ArrayList<WirelessToolDataObject> data = new ArrayList<>();
    private int ticksToRefresh = REFRESH_TICKS;
    @Nullable
    private NBTTagCompound sentNbt;
    private Set<WirelessToolDataObject> sentData = Collections.emptySet();

    public ContainerWirelessKit(final InventoryPlayer ip, final WirelessKitObject te) {
        super(ip, te);
        this.toolInv = te;

        bindPlayerInventory(ip, -1000, -1000);
    }

    @Override
    public void detectAndSendChanges() {
        final ItemStack currentItem = this.getPlayerInv().getCurrentItem();

        if (currentItem == null) {
            this.setValidContainer(false);
        } else if (currentItem != this.toolInv.getItemStack()) {
            if (Platform.isSameItem(this.toolInv.getItemStack(), currentItem)) {
                this.getPlayerInv()
                        .setInventorySlotContents(this.getPlayerInv().currentItem, this.toolInv.getItemStack());
            } else {
                this.setValidContainer(false);
            }
        }

        if (Platform.isServer() && this.isValidContainer() && --this.ticksToRefresh <= 0) this.updateData(false);

        super.detectAndSendChanges();
    }

    @Override
    public void addCraftingToCrafters(ICrafting p_75132_1_) {
        super.addCraftingToCrafters(p_75132_1_);
        updateData(true);
    }

    private NBTTagCompound getSuperTag() {
        return WireLessToolHelper.getSuperTag(this.toolInv.getItemStack());
    }

    /**
     * Collects the remembered network and the free connectors kept by the kit, and sends them when anything changed.
     * Splits, merges and channel paths settle over the next ticks, so an open GUI is refreshed regularly too; that pass
     * doesn't load chunks and keeps the last known state of whatever is unloaded.
     */
    private void updateData(final boolean loadChunks) {
        this.ticksToRefresh = REFRESH_TICKS;

        final NBTTagCompound stash = this.getSuperTag();
        final World w = this.toolInv.getWorld();
        final List<WirelessToolDataObject> previous = new ArrayList<>(this.data);
        this.data.clear();

        final IGrid grid = this.collectNetwork(stash, w, loadChunks, previous);
        this.collectKeptFree(stash, w, loadChunks, previous, grid);

        // the active device is dropped once its block is gone; elsewhere it can't be checked
        final DimensionalCoord active = WireLessToolHelper.getSuperActive(stash);
        if (active != null && active.getDimension() == w.provider.dimensionId
                && (loadChunks || w.blockExists(active.x, active.y, active.z))
                && !isDevice(active, w)) {
            WireLessToolHelper.setSuperActive(stash, null);
        }

        // copies: the kit's own tags change in place, and the last sent state has to stay as it was
        final NBTTagCompound nbtData = new NBTTagCompound();
        nbtData.setTag(WireLessToolHelper.NbtSuperPos, stash.getCompoundTag(WireLessToolHelper.NbtSuperPos).copy());
        if (stash.hasKey(WireLessToolHelper.NbtSuperActive)) {
            nbtData.setTag(
                    WireLessToolHelper.NbtSuperActive,
                    stash.getCompoundTag(WireLessToolHelper.NbtSuperActive).copy());
        }

        // the order of grid machines is not stable, compare as sets
        final Set<WirelessToolDataObject> snapshot = new HashSet<>(this.data);
        if (nbtData.equals(this.sentNbt) && snapshot.equals(this.sentData)) return;
        this.sentNbt = nbtData;
        this.sentData = snapshot;

        for (final ICrafting crafter : this.crafters) {
            if (!(crafter instanceof EntityPlayerMP emp)) continue;
            try {
                NetworkHandler.instance.sendTo(new PacketWirelessToolData(nbtData, this.data), emp);
            } catch (IOException e) {
                AELog.debug(e);
            }
        }
    }

    /**
     * Adds the hubs and connectors of the remembered network, and forgets the network once its anchor block is gone.
     *
     * @return the grid of the network, or null when it can't be read right now
     */
    @Nullable
    private IGrid collectNetwork(final NBTTagCompound stash, final World w, final boolean loadChunks,
            final List<WirelessToolDataObject> previous) {
        final DimensionalCoord network = WireLessToolHelper.getSuperNetwork(stash);
        if (network == null || network.getDimension() != w.provider.dimensionId) return null;

        if (!loadChunks && !w.blockExists(network.x, network.y, network.z)) {
            keepPrevious(previous, network, null);
            return null;
        }

        final TileEntity anchor = w.getTileEntity(network.x, network.y, network.z);
        if (!(anchor instanceof IGridHost)) {
            forgetNetwork(stash, network, previous);
            return null;
        }

        final IGrid grid = WireLessToolHelper.getGrid(anchor);
        if (grid == null) {
            // a chunk that has just loaded joins its grid on the next tick
            keepPrevious(previous, network, null);
            return null;
        }

        for (final IGridNode gn : grid.getMachines(TileWirelessConnector.class)) {
            this.data.add(((TileWirelessBase) gn.getMachine()).getDataForTool(network));
        }
        for (final IGridNode gn : grid.getMachines(TileWirelessHub.class)) {
            this.data.add(((TileWirelessBase) gn.getMachine()).getDataForTool(network));
        }
        return grid;
    }

    /**
     * Adds the free connectors the kit keeps, unless they are part of the network anyway, and forgets the ones that are
     * broken or bound since; a bound connector is listed with its hub.
     */
    private void collectKeptFree(final NBTTagCompound stash, final World w, final boolean loadChunks,
            final List<WirelessToolDataObject> previous, @Nullable final IGrid networkGrid) {
        final List<DimensionalCoord> kept = WireLessToolHelper.getSuperFree(stash);
        boolean keptChanged = false;
        for (final Iterator<DimensionalCoord> it = kept.iterator(); it.hasNext();) {
            final DimensionalCoord coord = it.next();
            if (coord.getDimension() != w.provider.dimensionId) continue;
            if (!loadChunks && !w.blockExists(coord.x, coord.y, coord.z)) {
                keepPrevious(previous, coord, coord);
                continue;
            }

            if (!(w.getTileEntity(coord.x, coord.y, coord.z) instanceof TileWirelessConnector connector)
                    || connector.isLinked()) {
                it.remove();
                keptChanged = true;
                continue;
            }

            // one wired into the network is listed with it, also while the network itself can't be read
            if (this.isListed(coord)) continue;

            final IGrid grid = WireLessToolHelper.getGrid(connector);
            if (grid == null) keepPrevious(previous, coord, coord);
            else if (grid != networkGrid) this.data.add(connector.getDataForTool(coord));
        }
        if (keptChanged) WireLessToolHelper.setSuperFree(stash, kept);
    }

    private boolean isListed(final DimensionalCoord device) {
        for (final WirelessToolDataObject d : this.data) {
            if (d.cord.equals(device)) return true;
        }
        return false;
    }

    /**
     * Forgets the network, and the active device with it when it was part of the network.
     */
    private static void forgetNetwork(final NBTTagCompound stash, final DimensionalCoord network,
            final List<WirelessToolDataObject> devices) {
        final DimensionalCoord active = WireLessToolHelper.getSuperActive(stash);
        for (final WirelessToolDataObject device : devices) {
            if (device.cord.equals(active) && device.network.equals(network)) {
                WireLessToolHelper.setSuperActive(stash, null);
                break;
            }
        }
        WireLessToolHelper.forgetSuperNetwork(stash);
    }

    /**
     * Carries over the last known devices of the network, or of one kept free connector, that can't be read now.
     */
    private void keepPrevious(final List<WirelessToolDataObject> previous, final DimensionalCoord network,
            @Nullable final DimensionalCoord device) {
        for (final WirelessToolDataObject d : previous) {
            if (d.network.equals(network) && (device == null || d.cord.equals(device))) this.data.add(d);
        }
    }

    /**
     * An unbind that leaves the network without hubs and links leaves nothing for the tree. Its connectors, all free
     * now, stay in the kit.
     */
    private static void forgetIfEmpty(final NBTTagCompound stash, final World w) {
        final DimensionalCoord network = WireLessToolHelper.getSuperNetwork(stash);
        if (network == null || network.getDimension() != w.provider.dimensionId) return;

        final IGrid grid = WireLessToolHelper.getGrid(w.getTileEntity(network.x, network.y, network.z));
        if (grid == null || !grid.getMachines(TileWirelessHub.class).isEmpty()) return;
        for (final IGridNode node : grid.getMachines(TileWirelessConnector.class)) {
            if (((TileWirelessBase) node.getMachine()).isLinked()) return;
        }

        for (final IGridNode node : grid.getMachines(TileWirelessConnector.class)) {
            WireLessToolHelper.addSuperFree(stash, ((TileWirelessBase) node.getMachine()).getLocation());
        }
        WireLessToolHelper.forgetSuperNetwork(stash);
    }

    /**
     * Any hub or connector can be the active device. A hub or a free connector is also the bind target, a connector
     * under a hub hands that to its hub, and an end of a pair is only outlined; the GUI works that out.
     */
    private static boolean isDevice(final DimensionalCoord coord, final World w) {
        return coord.getDimension() == w.provider.dimensionId
                && w.getTileEntity(coord.x, coord.y, coord.z) instanceof TileWirelessBase;
    }

    public void processCommand(WirelessKitCommand command) {
        World w = toolInv.getWorld();
        NBTTagCompound stash = this.getSuperTag();
        switch (command.command) {
            case RENAME_SINGLE -> {
                final DimensionalCoord coord = command.subCommand == null ? null : command.subCommand.coord;
                if (coord != null && w.getTileEntity(coord.x, coord.y, coord.z) instanceof TileWirelessBase twc) {
                    twc.setCustomName(command.name);
                }

                updateData(true);
            }
            case DELETE -> {
                final DimensionalCoord network = WireLessToolHelper.getSuperNetwork(stash);
                if (network != null && network.equals(command.networkPos)) forgetNetwork(stash, network, this.data);

                updateData(true);
            }
            case RECOLOR -> {
                for (final SubCommand subCommand : command.toBindRow) {
                    switch (subCommand.groupBy) {
                        case SINGLE -> {
                            if (w.getTileEntity(
                                    subCommand.coord.x,
                                    subCommand.coord.y,
                                    subCommand.coord.z) instanceof TileWirelessBase tw) {
                                if (!WireLessToolHelper
                                        .securityCheck(tw, new PlayerSource(this.getPlayerInv().player, null)))
                                    continue;
                                if (command.color != null)
                                    tw.recolourBlock(ForgeDirection.UNKNOWN, command.color, this.getPlayerInv().player);
                                else tw.madChameleonRecolor();
                            }
                        }

                        case NETWORK, COLOR -> {
                            final boolean isColor = subCommand.groupBy == PinType.COLOR;
                            for (WirelessToolDataObject sd : data) {
                                if (!subCommand.networkPos.equals(sd.network)) continue;

                                if (!(w.getTileEntity(sd.cord.x, sd.cord.y, sd.cord.z) instanceof TileWirelessBase tw))
                                    continue;

                                if (!WireLessToolHelper
                                        .securityCheck(tw, new PlayerSource(this.getPlayerInv().player, null)))
                                    continue;

                                if (isColor) if (sd.color != subCommand.color) continue;
                                if (command.color != null) {
                                    tw.recolourBlock(ForgeDirection.UNKNOWN, command.color, this.getPlayerInv().player);
                                } else {
                                    tw.madChameleonRecolor();
                                }
                            }
                        }
                    }
                }

                updateData(true);
            }
            case BIND -> {
                final List<TileWirelessBase> twToBind = this.fletchConnectors(false, command.toBindRow, w);
                final List<TileWirelessBase> twTarget = this.fletchConnectors(false, command.targetRow, w);

                // a link shares names and would rename a named group after the newcomer: keep the group name
                final Map<TileWirelessBase, String> groupNames = new HashMap<>();
                for (final TileWirelessBase hub : twTarget) {
                    if (hub.isHub() && hub.hasCustomName()) groupNames.put(hub, hub.getCustomName());
                }

                WireLessToolHelper.bindRows(twToBind, twTarget, this.getPlayerInv().player);

                groupNames.forEach((hub, name) -> { if (!name.equals(hub.getCustomName())) hub.setCustomName(name); });

                updateData(true);
            }
            case UNBIND -> {
                // unbound connectors stay in the kit as free ones, ready for another hub
                for (final TileWirelessBase tw : this.fletchConnectors(true, command.toBindRow, w)) {
                    if (!tw.isHub() && !tw.isLinked()) WireLessToolHelper.addSuperFree(stash, tw.getLocation());
                }
                forgetIfEmpty(stash, w);

                updateData(true);
            }
            case SET_ACTIVE -> {
                final DimensionalCoord device = command.subCommand == null ? null : command.subCommand.coord;
                if (device != null && isDevice(device, w)) WireLessToolHelper.setSuperActive(stash, device);

                updateData(true);
            }
        }
    }

    private ArrayList<TileWirelessBase> fletchConnectors(final boolean unbind,
            final ArrayList<WirelessKitCommand.SubCommand> list, final World w) {
        final ArrayList<TileWirelessBase> connectors = new ArrayList<>();
        for (final SubCommand subCommand : list) {
            switch (subCommand.groupBy) {
                case SINGLE -> {
                    if (w.getTileEntity(
                            subCommand.coord.x,
                            subCommand.coord.y,
                            subCommand.coord.z) instanceof TileWirelessBase wc) {
                        if (unbind) {
                            connectors.addAll(wc.getConnectedTiles());
                            WireLessToolHelper.breakConnection(wc, new PlayerSource(this.getPlayerInv().player, null));
                        }
                        connectors.add(wc);
                    }
                }
                case NETWORK, COLOR -> {
                    final DimensionalCoord network = subCommand.networkPos;
                    final boolean isColor = subCommand.groupBy == PinType.COLOR;
                    if (w.getTileEntity(network.x, network.y, network.z) instanceof IGridHost gh) {
                        if (subCommand.includeConnectors) {
                            for (IGridNode gn : gh.getGridNode(ForgeDirection.UNKNOWN).getGrid()
                                    .getMachines(TileWirelessConnector.class)) {
                                TileWirelessBase wc = (TileWirelessBase) gn.getMachine();
                                if (!wc.isLinked()) {
                                    if (isColor && wc.getColor() != subCommand.color) continue;
                                    if (unbind) {
                                        connectors.addAll(wc.getConnectedTiles());
                                        WireLessToolHelper.breakConnection(
                                                wc,
                                                new PlayerSource(this.getPlayerInv().player, null));
                                    }
                                    connectors.add(wc);
                                }
                            }
                        }

                        if (subCommand.includeHubs) {
                            for (IGridNode gn : gh.getGridNode(ForgeDirection.UNKNOWN).getGrid()
                                    .getMachines(TileWirelessHub.class)) {
                                TileWirelessHub wc = (TileWirelessHub) gn.getMachine();
                                if (wc.getFreeSlots() > 0 || unbind) {
                                    if (isColor && wc.getColor() != subCommand.color) continue;
                                    if (unbind) {
                                        connectors.addAll(wc.getConnectedTiles());
                                        WireLessToolHelper.breakConnection(
                                                wc,
                                                new PlayerSource(this.getPlayerInv().player, null));
                                    }
                                    connectors.add(wc);
                                }
                            }
                        }
                    }
                }
            }
        }

        return connectors;
    }
}
