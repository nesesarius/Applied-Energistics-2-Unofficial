package appeng.client.render.highlighter;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;

import appeng.api.config.WirelessToolMode;
import appeng.api.util.DimensionalCoord;
import appeng.helpers.WireLessToolHelper;
import appeng.items.tools.ToolWirelessKit;

/**
 * Outlines the active device of the Super mode and its links while the Wireless Kit is in hand, the way the Advanced
 * Memory Card does for P2P tunnels. While the kit GUI is open it also outlines the devices of the row under the cursor.
 */
public class WirelessKitHighlighter extends BlockPosHighlighter {

    public static final WirelessKitHighlighter INSTANCE = new WirelessKitHighlighter();

    private static final int ACTIVE_COLOR = 0x20FF40;
    private static final int LINK_COLOR = 0x66CCFF;
    private static final int HOVER_COLOR = 0xFFFFFF;

    // links come with the GUI data packet; they are only drawn while the kit still names the same device
    @Nullable
    private DimensionalCoord linksOf;
    private final List<DimensionalCoord> links = new ArrayList<>();
    private WeakReference<World> linksWorld = new WeakReference<>(null);

    private final List<DimensionalCoord> hovered = new ArrayList<>();

    /** Read once per frame in noWork(), which the manager always calls right before rendering. */
    @Nullable
    private DimensionalCoord heldActive;

    private WirelessKitHighlighter() {}

    public static void setLinks(@Nullable DimensionalCoord active, Collection<? extends DimensionalCoord> targetLinks) {
        INSTANCE.linksOf = active;
        INSTANCE.links.clear();
        INSTANCE.links.addAll(targetLinks);
        INSTANCE.linksWorld = new WeakReference<>(Minecraft.getMinecraft().theWorld);
    }

    public static void setHovered(Collection<? extends DimensionalCoord> coords) {
        INSTANCE.hovered.clear();
        INSTANCE.hovered.addAll(coords);
    }

    /**
     * The active device is read from the kit itself: its NBT follows world clicks and Shift+Ctrl clears without the
     * GUI.
     */
    @Nullable
    private static DimensionalCoord readHeldActive() {
        final EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return null;

        final ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ToolWirelessKit) || !held.hasTagCompound()) return null;
        if (WireLessToolHelper.getMode(held) != WirelessToolMode.Super) return null;

        return WireLessToolHelper.getSuperActive(held.getTagCompound().getCompoundTag(WireLessToolHelper.NbtSuper));
    }

    @Override
    public boolean noWork() {
        this.heldActive = readHeldActive();
        return this.hovered.isEmpty() && this.heldActive == null;
    }

    @Override
    public boolean blinks() {
        return false;
    }

    @Override
    public long getExpireTime() {
        return Long.MAX_VALUE;
    }

    @Override
    public void clear() {
        this.linksOf = null;
        this.links.clear();
        this.hovered.clear();
    }

    @Override
    public void renderHighlightedBlocks(RenderWorldLastEvent event) {
        updateCameraState(event);

        // thin hover lines go over the links, the active device over everything
        final DimensionalCoord active = this.heldActive;
        final boolean showLinks = active != null && active.equals(this.linksOf)
                && this.linksWorld.get() == Minecraft.getMinecraft().theWorld;
        if (showLinks) {
            for (DimensionalCoord link : this.links) this.outline(link, LINK_COLOR, 3);
        }
        for (DimensionalCoord coord : this.hovered) this.outline(coord, HOVER_COLOR, 1);
        if (active != null) this.outline(active, ACTIVE_COLOR, 4);
    }

    private void outline(DimensionalCoord c, int rgb, float lineWidth) {
        if (c.getDimension() != this.dimension) return;

        beginOutline();
        GL11.glLineWidth(lineWidth);
        renderHighLightedBlocksOutline(
                c.x,
                c.y,
                c.z,
                (rgb >> 16 & 0xFF) / 255f,
                (rgb >> 8 & 0xFF) / 255f,
                (rgb & 0xFF) / 255f,
                1.0f);
        endOutline();
    }
}
