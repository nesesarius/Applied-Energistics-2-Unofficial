package appeng.items.contents;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import appeng.api.implementations.guiobjects.IGuiItemObject;

public class WirelessKitObject implements IGuiItemObject {

    private final ItemStack stack;
    private final World world;

    public WirelessKitObject(final ItemStack stack, World w) {
        this.stack = stack;
        this.world = w;
    }

    @Override
    public ItemStack getItemStack() {
        return this.stack;
    }

    public World getWorld() {
        return this.world;
    }
}
