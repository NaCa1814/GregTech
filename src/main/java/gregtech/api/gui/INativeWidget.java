package gregtech.api.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * Native widget is widget that is based on MC native container mechanics
 * This widget logic delegates to MC slot
 */
public interface INativeWidget {

    ItemStack VANILLA_LOGIC = new ItemStack((Item) null);

    /**
     * You should return MC slot handle instance you created earlier
     * @return MC slot
     */
    Slot allocateSlotHandle();

    /**
     * @return true when this slot is valid for shift clicking
     */
    boolean canMergeSlot(ItemStack stack);

    /**
     * Called when slot is clicked in Container
     * Return {@link INativeWidget#VANILLA_LOGIC} to fallback to vanilla logic
     */
    ItemStack slotClick(int dragType, ClickType clickTypeIn, EntityPlayer player);

}
