package appeng.client.gui.implementations.wirelesskit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.StatCollector;

import appeng.api.AEApi;
import appeng.api.definitions.IBlockDefinition;
import appeng.api.util.DimensionalCoord;
import appeng.core.AEConfig;
import appeng.helpers.WireLessToolHelper;
import appeng.helpers.WirelessToolDataObject;

/**
 * Client view of the Super mode data, built in one pass over a data packet: the remembered network holds hubs with
 * their connectors, connector pairs and its own free connectors; free connectors the kit keeps from other grids are
 * kept apart.
 */
public final class WirelessKitTree {

    /** Links of a {@code TileWirelessHub}. */
    public static final int HUB_LINKS = 32;
    /** Channels through a {@code DENSE_CAPACITY} node, which every wireless device is. */
    public static final int CHANNEL_CAPACITY = 32;

    public static final class Network {

        public final DimensionalCoord anchor;
        public final List<Hub> hubs = new ArrayList<>();
        public final List<Pair> pairs = new ArrayList<>();
        /** Unlinked connectors wired into the grid; they are listed in the network like hubs. */
        public final List<WirelessToolDataObject> free = new ArrayList<>();
        private int connectors;

        Network(DimensionalCoord anchor) {
            this.anchor = anchor;
        }

        public int getConnectorCount() {
            return this.connectors;
        }
    }

    public static final class Hub {

        public final WirelessToolDataObject device;
        public final List<WirelessToolDataObject> connectors = new ArrayList<>();

        Hub(WirelessToolDataObject device) {
            this.device = device;
        }
    }

    /**
     * Two linked connectors, shown like a hub with one connector. The end with the lower coordinates leads: nothing
     * else about a pair is both stable and the same seen from either end.
     */
    public static final class Pair {

        public final WirelessToolDataObject leader;
        /** Null only if the other end did not come with the packet. */
        @Nullable
        public final WirelessToolDataObject follower;
        public final DimensionalCoord followerCoord;

        private Pair(WirelessToolDataObject leader, @Nullable WirelessToolDataObject follower,
                DimensionalCoord followerCoord) {
            this.leader = leader;
            this.follower = follower;
            this.followerCoord = followerCoord;
        }

        static Pair of(WirelessToolDataObject end, DimensionalCoord otherCoord,
                @Nullable WirelessToolDataObject other) {
            if (other == null || COORD_ORDER.compare(end.cord, otherCoord) < 0) {
                return new Pair(end, other, otherCoord);
            }
            return new Pair(other, end, end.cord);
        }
    }

    private static final Comparator<DimensionalCoord> COORD_ORDER = Comparator.<DimensionalCoord>comparingInt(c -> c.x)
            .thenComparingInt(c -> c.z).thenComparingInt(c -> c.y);
    private static final Comparator<WirelessToolDataObject> DEVICE_ORDER = Comparator
            .comparing((WirelessToolDataObject d) -> d.cord, COORD_ORDER);

    @Nullable
    private final Network network;
    private final boolean networkElsewhere;
    private final List<WirelessToolDataObject> free;
    @Nullable
    private final WirelessToolDataObject active;
    @Nullable
    private final WirelessToolDataObject bindTarget;

    private WirelessKitTree(@Nullable Network network, boolean networkElsewhere, List<WirelessToolDataObject> free,
            @Nullable WirelessToolDataObject active, @Nullable WirelessToolDataObject bindTarget) {
        this.network = network;
        this.networkElsewhere = networkElsewhere;
        this.free = free;
        this.active = active;
        this.bindTarget = bindTarget;
    }

    public static WirelessKitTree build(NBTTagCompound nbt, List<WirelessToolDataObject> devices, int dimension) {
        final DimensionalCoord anchor = WireLessToolHelper.getSuperNetwork(nbt);
        final Network network = anchor != null && anchor.getDimension() == dimension ? new Network(anchor) : null;

        final Map<DimensionalCoord, WirelessToolDataObject> byCoord = new HashMap<>();
        final Map<DimensionalCoord, Hub> hubs = new HashMap<>();
        for (WirelessToolDataObject device : devices) {
            byCoord.put(device.cord, device);
            if (device.isHub && network != null && network.anchor.equals(device.network)) {
                final Hub hub = new Hub(device);
                network.hubs.add(hub);
                hubs.put(device.cord, hub);
            }
        }

        final List<WirelessToolDataObject> free = new ArrayList<>();
        final Set<DimensionalCoord> paired = new HashSet<>();
        for (WirelessToolDataObject device : devices) {
            if (device.isHub) continue;

            // the connectors the kit keeps from other grids come without the network
            final boolean inNetwork = network != null && network.anchor.equals(device.network);
            final boolean unlinked = !device.isConnected || device.targets.isEmpty();
            if (!inNetwork) {
                if (unlinked) free.add(device);
                continue;
            }

            network.connectors++;
            if (unlinked) {
                network.free.add(device);
                continue;
            }

            final DimensionalCoord other = device.targets.get(0);
            final Hub hub = hubs.get(other);
            if (hub != null) {
                hub.connectors.add(device);
            } else if (paired.add(device.cord) && paired.add(other)) {
                network.pairs.add(Pair.of(device, other, byCoord.get(other)));
            }
        }

        final DimensionalCoord activeCoord = WireLessToolHelper.getSuperActive(nbt);
        final WirelessToolDataObject active = activeCoord == null ? null : byCoord.get(activeCoord);
        final WirelessToolDataObject target = bindTargetOf(active, hubs);

        if (network != null) {
            network.hubs.sort(
                    Comparator.<Hub, Boolean>comparing(h -> h.device != target)
                            .thenComparing(h -> customName(h.device) == null)
                            .thenComparing(h -> String.valueOf(customName(h.device)), String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(h -> h.device.cord, COORD_ORDER));
            network.pairs.sort(Comparator.comparing(p -> p.leader.cord, COORD_ORDER));
            network.free.sort(DEVICE_ORDER);
            for (Hub hub : network.hubs) {
                // keep the order the hub was linked in
                hub.connectors.sort(Comparator.comparingInt(c -> {
                    final int index = hub.device.targets.indexOf(c.cord);
                    return index < 0 ? Integer.MAX_VALUE : index;
                }));
            }
        }
        free.sort(DEVICE_ORDER);

        return new WirelessKitTree(network, anchor != null && network == null, free, active, target);
    }

    /**
     * A hub or a free connector is a bind target by itself; binds for a connector under a hub go to its hub, and an end
     * of a pair has none.
     */
    @Nullable
    private static WirelessToolDataObject bindTargetOf(@Nullable WirelessToolDataObject active,
            Map<DimensionalCoord, Hub> hubs) {
        if (active == null || active.isHub || !active.isConnected) return active;

        final Hub hub = active.targets.isEmpty() ? null : hubs.get(active.targets.get(0));
        return hub == null ? null : hub.device;
    }

    /** The remembered network, if it is in this dimension. */
    @Nullable
    public Network getNetwork() {
        return this.network;
    }

    /** A network is remembered, but in another dimension. */
    public boolean isNetworkElsewhere() {
        return this.networkElsewhere;
    }

    /** Free connectors the kit keeps from outside the remembered network. */
    public List<WirelessToolDataObject> getFree() {
        return this.free;
    }

    /** The device picked in the list and outlined in the world, if it is listed in this dimension. */
    @Nullable
    public WirelessToolDataObject getActive() {
        return this.active;
    }

    public boolean isActive(WirelessToolDataObject device) {
        return this.active != null && this.active.cord.equals(device.cord);
    }

    /** Where binds go: the active hub or free connector, or the hub of the active connector. */
    @Nullable
    public WirelessToolDataObject getBindTarget() {
        return this.bindTarget;
    }

    public boolean isBindTarget(WirelessToolDataObject device) {
        return this.bindTarget != null && this.bindTarget.cord.equals(device.cord);
    }

    static IBlockDefinition blockOf(boolean hub) {
        return hub ? AEApi.instance().definitions().blocks().wirelessHub()
                : AEApi.instance().definitions().blocks().wirelessConnector();
    }

    /**
     * The server sends the block name when a device has no custom name; that name may come from either language.
     */
    @Nullable
    public static String customName(WirelessToolDataObject device) {
        final Block block = blockOf(device.isHub).maybeBlock().orNull();
        if (block != null) {
            final String key = block.getUnlocalizedName() + ".name";
            if (device.customName.equals(StatCollector.translateToLocal(key))
                    || device.customName.equals(StatCollector.translateToFallback(key))) {
                return null;
            }
        }
        return device.customName.isEmpty() ? null : device.customName;
    }

    public static String coords(DimensionalCoord c) {
        return c.x + ", " + c.y + ", " + c.z;
    }

    public static double distance(DimensionalCoord a, DimensionalCoord b) {
        final double dx = a.x - b.x;
        final double dy = a.y - b.y;
        final double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Idle draw of one link, the formula of {@code TileWirelessBase.setConnectionsPowerDraw}.
     */
    public static double linkPower(double distance) {
        return AEConfig.instance.getWirelessConnectorPowerBase()
                + AEConfig.instance.getWirelessConnectorPowerDistanceMultiplier() * distance
                        * Math.log(distance * distance + 3);
    }
}
