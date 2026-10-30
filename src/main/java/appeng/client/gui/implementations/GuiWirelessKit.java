package appeng.client.gui.implementations;

import static appeng.client.gui.implementations.wirelesskit.WirelessKitTree.CHANNEL_CAPACITY;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import appeng.api.util.AEColor;
import appeng.api.util.DimensionalCoord;
import appeng.api.util.NamedDimensionalCoord;
import appeng.client.gui.AEBaseGui;
import appeng.client.gui.implementations.wirelesskit.WirelessKitIcon;
import appeng.client.gui.implementations.wirelesskit.WirelessKitRow;
import appeng.client.gui.implementations.wirelesskit.WirelessKitRow.Button;
import appeng.client.gui.implementations.wirelesskit.WirelessKitRow.Look;
import appeng.client.gui.implementations.wirelesskit.WirelessKitRow.Style;
import appeng.client.gui.implementations.wirelesskit.WirelessKitRow.Toggle;
import appeng.client.gui.implementations.wirelesskit.WirelessKitRowBuilder;
import appeng.client.gui.implementations.wirelesskit.WirelessKitTree;
import appeng.client.gui.implementations.wirelesskit.WirelessKitViewState;
import appeng.client.gui.widgets.GuiAeButton;
import appeng.client.gui.widgets.GuiScrollbar;
import appeng.client.gui.widgets.MEGuiTextField;
import appeng.client.render.highlighter.BlockPosHighlighter;
import appeng.client.render.highlighter.WirelessKitHighlighter;
import appeng.container.implementations.ContainerWirelessKit;
import appeng.core.AELog;
import appeng.core.localization.ButtonToolTips;
import appeng.core.localization.ColorUtils;
import appeng.core.localization.GuiText;
import appeng.core.localization.PlayerMessages;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.PacketWirelessToolCommand;
import appeng.helpers.WirelessKitCommand;
import appeng.helpers.WirelessKitCommand.SubCommand;
import appeng.helpers.WirelessKitCommand.WirelessKitCommands;
import appeng.helpers.WirelessToolDataObject;
import appeng.items.contents.WirelessKitObject;

/**
 * Super mode of the Wireless Kit: one scrolling list with the networks as trees and the free connectors at the bottom.
 * Every action is a single command; the list only changes when the server answers with fresh data.
 */
public class GuiWirelessKit extends AEBaseGui implements WirelessKitRowBuilder.Actions {

    private static final ResourceLocation TEXTURE = new ResourceLocation(
            "appliedenergistics2",
            "textures/guis/wirelesskit.png");

    // layout, relative to the GUI corner
    private static final int WIDTH = 288;
    private static final int LIST_X = 8;
    private static final int LIST_Y = 20;
    private static final int LIST_W = 256;
    private static final int BOTTOM = 8;
    private static final int MIN_ROWS = 4;
    private static final int SCREEN_MARGIN = 8;
    private static final int SCROLL_X = 270;
    private static final int HEADER_Y = 5;
    private static final int HEADER_TITLE_Y = 7;
    private static final int SIZE_X = 271;
    private static final int SEARCH_X = 130;
    private static final int SEARCH_Y = 4;
    private static final int SEARCH_W = 136;
    private static final int SEARCH_H = 12;
    private static final int RENAME_MAX_LENGTH = 64;

    // layout of a row
    private static final int ROW_H = 24;
    private static final int INDENT = 12;
    private static final int ICON_X = 11;
    private static final int ITEM_Y = 4;
    private static final int ITEM_SIZE = 16;
    private static final int TOGGLE_Y = 7;
    private static final int TEXT_X = 30;
    private static final int TITLE_Y = 3;
    private static final int SUBTITLE_Y = 14;
    private static final int UPPER_Y = 1;
    private static final int LOWER_Y = 12;
    private static final int BUTTON = 11;
    private static final int TEXT_BUTTON_MAX = 110;
    /** Widgets on the right of a row end here. */
    private static final int ROW_RIGHT = LIST_X + LIST_W - 2;
    /** One pixel per channel. */
    private static final int METER_W = CHANNEL_CAPACITY;
    private static final int METER_Y = 4;
    private static final int METER_H = 7;

    // colour palette: 16 colours, fluix and Mad Chameleon
    private static final int PALETTE_CELL = 12;
    private static final int PALETTE_SWATCH = 10;
    private static final int PALETTE_PAD = 2;
    private static final int PALETTE_COLUMNS = 6;
    private static final int PALETTE_CELLS = AEColor.VALUES.length + 1;
    private static final int PALETTE_W = PALETTE_COLUMNS * PALETTE_CELL + 2 * PALETTE_PAD;
    private static final int PALETTE_H = (PALETTE_CELLS + PALETTE_COLUMNS - 1) / PALETTE_COLUMNS * PALETTE_CELL
            + 2 * PALETTE_PAD;

    private static final long CONFIRM_MS = 3000;
    private static final int WHITE = 0xFFFFFF;

    // texture atlas, see WirelessKitIcon for the glyphs
    private static final int PANEL_PATCH = 24;
    private static final int LIGHT_INSET_U = 24;
    private static final int DARK_INSET_U = 27;
    private static final int INSET_PATCH = 3;
    private static final int FRAME_V = 24;
    private static final int FRAME_NORMAL = 0;
    private static final int FRAME_HOVER = 1;
    private static final int FRAME_DISABLED = 2;
    private static final int FRAME_DANGER = 3;
    /** Unchecked, checked, then the same two hovered. */
    private static final int CHECKBOX_V = 36;
    private static final int CHECKBOX = 9;

    private final WirelessKitViewState state = WirelessKitViewState.INSTANCE;
    private final GuiScrollbar scrollbar = new GuiScrollbar();
    private final MEGuiTextField search;
    private final MEGuiTextField renameField = new MEGuiTextField(0, 12) {

        // the field scrolls its text by its own width to keep the cursor in view: the width has to be in place first
        @Override
        public void setText(String text, boolean ignoreTrigger) {
            this.setDimensionsAndColor();
            super.setText(text, ignoreTrigger);
        }
    };
    /** Draws every text button of the list, vanilla style. */
    private final GuiAeButton textButton = new GuiAeButton(0, 0, 0, 0, BUTTON, "", null);

    /** Null until the first data packet arrives. */
    @Nullable
    private WirelessKitTree tree;
    private List<WirelessKitRow> rows = Collections.emptyList();
    private boolean rowsDirty = true;
    private int visibleRows = MIN_ROWS;

    /** Device row to scroll into view: a new active device. */
    @Nullable
    private DimensionalCoord reveal;
    @Nullable
    private DimensionalCoord armedHub;
    private long armedUntil;

    @Nullable
    private WirelessKitRow renaming;
    private String renameInitial = "";
    @Nullable
    private Consumer<String> renameSubmit;

    @Nullable
    private WirelessKitRow paletteRow;

    public GuiWirelessKit(final InventoryPlayer inventoryPlayer, final WirelessKitObject te) {
        super(new ContainerWirelessKit(inventoryPlayer, te));
        this.xSize = WIDTH;
        this.setScrollBar(this.scrollbar);

        this.search = new MEGuiTextField(SEARCH_W, SEARCH_H) {

            @Override
            public void onTextChange(final String oldText) {
                GuiWirelessKit.this.state.search = this.getText();
                GuiWirelessKit.this.scrollbar.setCurrentScroll(0);
                GuiWirelessKit.this.rowsDirty = true;
            }
        };
        this.search.x = SEARCH_X;
        this.search.y = SEARCH_Y;
        this.search.setText(this.state.search, true);
        this.renameField.setMaxStringLength(RENAME_MAX_LENGTH);
    }

    @Override
    public void initGui() {
        final int fitting = (this.height - 2 * SCREEN_MARGIN - LIST_Y - BOTTOM - 2) / ROW_H;
        this.visibleRows = Math.max(MIN_ROWS, Math.min(this.state.size.rows, fitting));
        this.ySize = LIST_Y + this.visibleRows * ROW_H + 1 + BOTTOM;
        super.initGui();

        this.scrollbar.setLeft(SCROLL_X).setTop(LIST_Y).setHeight(this.visibleRows * ROW_H);
        this.updateScrollRange();
        this.cancelRename();
        this.paletteRow = null;
    }

    /**
     * The server sends the list on open, after every command and whenever it changes while the GUI is open.
     */
    public void setData(final NBTTagCompound nbt, final List<WirelessToolDataObject> devices) {
        final WirelessToolDataObject previousActive = this.tree == null ? null : this.tree.getActive();
        final WirelessToolDataObject previousTarget = this.tree == null ? null : this.tree.getBindTarget();
        this.tree = WirelessKitTree.build(nbt, devices, this.mc.theWorld.provider.dimensionId);

        final Set<DimensionalCoord> free = new HashSet<>();
        this.tree.getFree().forEach(c -> free.add(c.cord));
        this.state.retainChecked(free);

        final WirelessToolDataObject active = this.tree.getActive();
        if (active != null && (previousActive == null || !previousActive.cord.equals(active.cord))) {
            // the hub that was open as the target stays open
            if (previousTarget != null && previousTarget.isHub) this.state.keepHubExpanded(previousTarget.cord);

            // the new target opens up together with its network
            final WirelessToolDataObject target = this.tree.getBindTarget();
            if (target != null) this.state.resetExpansion(target.network, target.cord);
            this.reveal = active.cord;
        }

        WirelessKitHighlighter.setLinks(
                active == null ? null : active.cord,
                active == null ? Collections.emptyList() : active.targets);
        this.rowsDirty = true;
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        WirelessKitHighlighter.setHovered(Collections.emptyList());
    }

    // ---- rows

    private void rebuildRows() {
        if (this.tree == null) return;

        this.rows = new WirelessKitRowBuilder(this.tree, this.state, this).build();
        this.rowsDirty = false;
        this.updateScrollRange();

        // popups follow their row through a rebuild
        if (this.renaming != null) {
            this.renaming = this.findRow(this.renaming);
            if (this.renaming == null) this.cancelRename();
        }
        if (this.paletteRow != null) this.paletteRow = this.findRow(this.paletteRow);

        if (this.reveal != null) {
            final int index = this.indexOfDevice(this.reveal);
            final int scroll = this.scrollbar.getCurrentScroll();
            // keep the parent row in sight when scrolling up
            if (index >= 0 && index < scroll) this.scrollbar.setCurrentScroll(index - 1);
            else if (index >= scroll + this.visibleRows) this.scrollbar.setCurrentScroll(index - this.visibleRows + 1);
            this.reveal = null;
        }
    }

    private int indexOfDevice(final DimensionalCoord device) {
        for (int i = 0; i < this.rows.size(); i++) {
            final WirelessKitRow row = this.rows.get(i);
            if (row.style == Style.DEVICE && device.equals(row.key)) return i;
        }
        return -1;
    }

    @Nullable
    private WirelessKitRow findRow(final WirelessKitRow old) {
        for (WirelessKitRow row : this.rows) {
            if (row.isSameRow(old)) return row;
        }
        return null;
    }

    private void updateScrollRange() {
        this.scrollbar.setRange(0, Math.max(0, this.rows.size() - this.visibleRows), 1);
    }

    /** Index on screen, or -1 when the row is scrolled away. */
    private int screenIndex(final WirelessKitRow row) {
        final int index = this.rows.indexOf(row) - this.scrollbar.getCurrentScroll();
        return index >= 0 && index < this.visibleRows ? index : -1;
    }

    private static int rowY(final int screenIndex) {
        return LIST_Y + screenIndex * ROW_H;
    }

    private static int rowX(final WirelessKitRow row) {
        return LIST_X + row.depth * INDENT;
    }

    // ---- drawing

    @Override
    public void drawBG(final int offsetX, final int offsetY, final int mouseX, final int mouseY) {
        final int listH = this.visibleRows * ROW_H;
        this.bindTexture(TEXTURE);
        this.drawTextured9PatchRect(offsetX, offsetY, this.xSize, this.ySize, 0, 0, PANEL_PATCH, PANEL_PATCH);
        this.drawInset(offsetX + LIST_X - 1, offsetY + LIST_Y - 1, LIST_W + 2, listH + 2, LIGHT_INSET_U);
        this.drawInset(offsetX + SCROLL_X - 1, offsetY + LIST_Y - 1, 14, listH + 2, DARK_INSET_U);
        this.drawInset(offsetX + SEARCH_X - 1, offsetY + SEARCH_Y - 1, SEARCH_W + 2, SEARCH_H + 2, DARK_INSET_U);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (this.armedHub != null && System.currentTimeMillis() > this.armedUntil) {
            this.armedHub = null;
            this.rowsDirty = true;
        }
    }

    @Override
    public void drawFG(final int offsetX, final int offsetY, final int mouseX, final int mouseY) {
        if (this.rowsDirty) this.rebuildRows();

        final int mx = mouseX - this.guiLeft;
        final int my = mouseY - this.guiTop;
        final boolean popup = this.paletteRow != null;

        this.fontRendererObj.drawString(
                GuiText.WirelessKitTitle.getLocal(),
                LIST_X,
                HEADER_TITLE_Y,
                ColorUtils.wirelessKitText.getColor());
        this.search.drawTextBox();
        this.drawIconButton(
                SIZE_X,
                HEADER_Y,
                this.state.size.icon,
                Look.NORMAL,
                true,
                !popup && isInside(mx, my, SIZE_X, HEADER_Y, BUTTON, BUTTON));

        WirelessKitRow hovered = null;
        int hoveredY = 0;
        final int scroll = this.scrollbar.getCurrentScroll();
        for (int i = 0; i < this.visibleRows && scroll + i < this.rows.size(); i++) {
            final WirelessKitRow row = this.rows.get(scroll + i);
            final int y = rowY(i);
            final boolean over = !popup && isInside(mx, my, LIST_X, y, LIST_W, ROW_H);
            if (over) {
                hovered = row;
                hoveredY = y;
            }
            this.drawRow(row, y, i, over, mx, my);
        }

        this.drawRenameBar();
        this.drawPalette(mx, my);
        this.drawTooltips(hovered, hoveredY, mx, my);

        WirelessKitHighlighter.setHovered(hovered == null ? Collections.emptyList() : hovered.devices);
    }

    private void drawRow(final WirelessKitRow row, final int y, final int screenIndex, final boolean hovered,
            final int mx, final int my) {
        if (row.style == Style.HEADER) {
            drawRect(LIST_X, y, LIST_X + LIST_W, y + ROW_H, ColorUtils.wirelessKitRowHeader.getColor());
            if (screenIndex > 0) {
                drawRect(LIST_X, y, LIST_X + LIST_W, y + 1, ColorUtils.wirelessKitRowSeparator.getColor());
            }
        }
        if (row.active) drawRect(LIST_X, y, LIST_X + LIST_W, y + ROW_H, ColorUtils.wirelessKitRowActive.getColor());
        if (hovered && row.style != Style.NOTE) {
            drawRect(LIST_X, y, LIST_X + LIST_W, y + ROW_H, ColorUtils.wirelessKitRowHover.getColor());
        }

        if (row.style == Style.NOTE) {
            final String text = this.fit(row.title, LIST_W - 8);
            this.fontRendererObj.drawString(
                    text,
                    LIST_X + (LIST_W - this.fontRendererObj.getStringWidth(text)) / 2,
                    y + 8,
                    row.titleColor);
            return;
        }

        final List<Placed> widgets = this.layout(row, y);
        int upperEnd = ROW_RIGHT;
        int lowerEnd = upperEnd;
        for (Placed p : widgets) {
            if (p.y == y + UPPER_Y) upperEnd = Math.min(upperEnd, p.x);
            else lowerEnd = Math.min(lowerEnd, p.x);
        }
        if (row.meter >= 0) {
            upperEnd -= this.meterWidth();
            this.drawMeter(row.meter, upperEnd, y);
        }

        final int x = rowX(row);
        this.drawGuides(row, y);
        if (row.toggle != Toggle.NONE) {
            this.drawGlyph(
                    x + 1,
                    y + TOGGLE_Y,
                    row.toggle == Toggle.EXPANDED ? WirelessKitIcon.EXPANDED : WirelessKitIcon.COLLAPSED,
                    ColorUtils.wirelessKitText.getColor());
        } else if (row.checked != null) {
            final boolean over = isInside(mx, my, x, y, ICON_X, ROW_H);
            final int u = (row.checked ? CHECKBOX : 0) + (over ? 2 * CHECKBOX : 0);
            this.drawSprite(x + 1, y + TOGGLE_Y, u, CHECKBOX_V, CHECKBOX, CHECKBOX, WHITE);
        }
        if (row.icon != null) this.drawItem(x + ICON_X, y + ITEM_Y, row.icon);

        final int textX = x + TEXT_X;
        this.fontRendererObj.drawString(this.fit(row.title, upperEnd - 4 - textX), textX, y + TITLE_Y, row.titleColor);

        final String subtitle = this.fit(row.subtitle, lowerEnd - 4 - textX);
        this.fontRendererObj.drawString(subtitle, textX, y + SUBTITLE_Y, row.subtitleColor);
        if (row.badge != null) {
            final int badgeX = textX + this.fontRendererObj.getStringWidth(subtitle) + (subtitle.isEmpty() ? 0 : 4);
            this.fontRendererObj.drawString(
                    this.fit(row.badge, lowerEnd - 4 - badgeX),
                    badgeX,
                    y + SUBTITLE_Y,
                    ColorUtils.wirelessKitTextTarget.getColor());
        }

        for (Placed p : widgets) this.drawWidget(p, mx, my);
    }

    /**
     * Tree lines: the branch to this row, the lines of ancestors that go on below it, and a stub down to its children.
     */
    private void drawGuides(final WirelessKitRow row, final int y) {
        final int color = ColorUtils.wirelessKitGuide.getColor();
        final int mid = y + ROW_H / 2;

        if (row.depth > 0) {
            for (int d = 1; d < row.depth; d++) {
                if (row.hasOpenGuide(d)) drawRect(guideX(d), y, guideX(d) + 1, y + ROW_H, color);
            }
            final int x = guideX(row.depth);
            drawRect(x, y, x + 1, row.isLastChild() ? mid + 1 : y + ROW_H, color);
            final boolean hasControl = row.toggle != Toggle.NONE || row.checked != null;
            drawRect(x + 1, mid, rowX(row) + (hasControl ? 1 : ICON_X - 1), mid + 1, color);
        }

        if (row.isParent()) {
            final int x = guideX(row.depth + 1);
            drawRect(x, y + TOGGLE_Y + WirelessKitIcon.SIZE, x + 1, y + ROW_H, color);
        }
    }

    private static int guideX(final int depth) {
        return LIST_X + (depth - 1) * INDENT + 5;
    }

    private int meterWidth() {
        return METER_W + 6 + this.fontRendererObj.getStringWidth(CHANNEL_CAPACITY + "/" + CHANNEL_CAPACITY);
    }

    /**
     * Channels of a hub: grey while there is room, yellow from 24, red when all 32 are taken.
     */
    private void drawMeter(final int used, final int x, final int y) {
        final int fill;
        if (used >= CHANNEL_CAPACITY) fill = ColorUtils.wirelessKitMeterFull.getColor();
        else if (used >= CHANNEL_CAPACITY * 3 / 4) fill = ColorUtils.wirelessKitMeterBusy.getColor();
        else fill = ColorUtils.wirelessKitMeterIdle.getColor();

        final int top = y + METER_Y;
        final int bottom = top + METER_H;
        drawRect(x, top, x + METER_W + 2, bottom, ColorUtils.wirelessKitMeterFrame.getColor());
        drawRect(x + 1, top + 1, x + 1 + METER_W, bottom - 1, ColorUtils.wirelessKitMeterBackground.getColor());
        drawRect(
                x + 1,
                top + 1,
                x + 1 + Math.min(used, CHANNEL_CAPACITY) * METER_W / CHANNEL_CAPACITY,
                bottom - 1,
                fill);

        this.fontRendererObj.drawString(
                used + "/" + CHANNEL_CAPACITY,
                x + METER_W + 5,
                y + TITLE_Y,
                used >= CHANNEL_CAPACITY ? ColorUtils.wirelessKitTextBad.getColor()
                        : ColorUtils.wirelessKitText.getColor());
    }

    private void drawWidget(final Placed p, final int mx, final int my) {
        final Button button = p.button;
        final boolean hovered = this.paletteRow == null && p.contains(mx, my);

        if (button.label != null) {
            this.textButton.xPosition = p.x;
            this.textButton.yPosition = p.y;
            this.textButton.width = p.w;
            this.textButton.height = p.h;
            this.textButton.enabled = button.enabled;
            this.textButton.displayString = this.fit(button.label, p.w - 6);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            // no hover glow under the palette
            this.textButton.drawButton(this.mc, this.paletteRow == null ? mx : -1, my);
        } else if (button.action == null) {
            this.drawGlyph(p.x + 1, p.y + 1, button.icon, ColorUtils.wirelessKitText.getColor());
        } else {
            this.drawIconButton(p.x, p.y, button.icon, button.look, button.enabled, hovered);
            if (button.swatch != null) {
                drawRect(p.x + 2, p.y + 2, p.x + BUTTON - 2, p.y + BUTTON - 2, 0xFF000000 | button.swatch);
            }
        }
    }

    private void drawIconButton(final int x, final int y, @Nullable final WirelessKitIcon icon, final Look look,
            final boolean enabled, final boolean hovered) {
        final int frame;
        final int glyph;
        if (!enabled) {
            frame = FRAME_DISABLED;
            glyph = ColorUtils.wirelessKitTextDisabled.getColor();
        } else if (look == Look.DANGER) {
            frame = FRAME_DANGER;
            glyph = WHITE;
        } else {
            frame = hovered ? FRAME_HOVER : FRAME_NORMAL;
            glyph = ColorUtils.wirelessKitText.getColor();
        }

        this.drawSprite(x, y, frame * BUTTON, FRAME_V, BUTTON, BUTTON, WHITE);
        if (icon != null) this.drawGlyph(x + 1, y + 1, icon, glyph);
    }

    private void drawGlyph(final int x, final int y, final WirelessKitIcon icon, final int rgb) {
        this.drawSprite(
                x,
                y,
                icon.u(),
                icon.v(),
                WirelessKitIcon.SIZE,
                WirelessKitIcon.SIZE,
                icon.isTinted() ? rgb : WHITE);
    }

    /**
     * Draws a piece of the atlas tinted with the colour. Text and plain rectangles leave their colour behind, so it is
     * always set first.
     */
    private void drawSprite(final int x, final int y, final int u, final int v, final int w, final int h,
            final int rgb) {
        this.bindTexture(TEXTURE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f((rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f, 1.0F);
        this.drawTexturedModalRect(x, y, u, v, w, h);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void drawInset(final int x, final int y, final int w, final int h, final int u) {
        this.bindTexture(TEXTURE);
        this.drawTextured9PatchRect(x, y, w, h, u, 0, INSET_PATCH, INSET_PATCH);
    }

    private void drawTooltips(@Nullable final WirelessKitRow hovered, final int hoveredY, final int mx, final int my) {
        List<String> lines = null;

        if (this.paletteRow != null) {
            final int cell = this.paletteCellAt(mx, my);
            if (cell >= AEColor.VALUES.length) {
                lines = Arrays.asList(
                        ButtonToolTips.WirelessKitMadChameleonRecolorName.getLocal(),
                        ButtonToolTips.WirelessKitMadChameleonRecolorDesc.getLocal());
            } else if (cell >= 0) {
                lines = Collections.singletonList(AEColor.VALUES[cell].toString());
            }
        } else if (isInside(mx, my, SIZE_X, HEADER_Y, BUTTON, BUTTON)) {
            lines = Arrays.asList(this.state.size.tooltip.getLocal(), ButtonToolTips.WirelessKitSizeHint.getLocal());
        } else if (this.search.isMouseIn(mx, my)) {
            lines = Arrays.asList(
                    ButtonToolTips.WirelessKitSearch.getLocal(),
                    ButtonToolTips.WirelessKitSearchWords.getLocal(),
                    ButtonToolTips.WirelessKitSearchPhrase.getLocal(),
                    ButtonToolTips.WirelessKitSearchClear.getLocal());
        } else if (hovered != null && this.renaming == null) {
            for (Placed p : this.layout(hovered, hoveredY)) {
                if (p.contains(mx, my)) lines = p.button.tooltip;
            }
            if (lines == null && isInside(mx, my, rowX(hovered) + ICON_X, hoveredY + ITEM_Y, ITEM_SIZE, ITEM_SIZE)) {
                lines = hovered.tooltip;
            }
        }

        if (lines != null && !lines.isEmpty()) this.drawTooltip(mx, my, lines.toArray(new String[0]));
    }

    // ---- widgets on the right side of a row

    private static final class Placed {

        final Button button;
        final int x;
        final int y;
        final int w;
        final int h;

        Placed(final Button button, final int x, final int y, final int w, final int h) {
            this.button = button;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean contains(final int mx, final int my) {
            return isInside(mx, my, this.x, this.y, this.w, this.h);
        }
    }

    /**
     * Buttons of a line are right-aligned and keep their order.
     */
    private List<Placed> layout(final WirelessKitRow row, final int y) {
        final List<Placed> placed = new ArrayList<>();
        this.placeLine(placed, row.upper, y + UPPER_Y);
        this.placeLine(placed, row.lower, y + LOWER_Y);
        return placed;
    }

    private void placeLine(final List<Placed> placed, final List<Button> buttons, final int y) {
        int x = ROW_RIGHT;
        for (int i = buttons.size() - 1; i >= 0; i--) {
            final Button button = buttons.get(i);
            final int w = button.label == null ? BUTTON
                    : Math.min(TEXT_BUTTON_MAX, this.fontRendererObj.getStringWidth(button.label) + 10);
            x -= w;
            placed.add(new Placed(button, x, y, w, BUTTON));
            x -= 1;
        }
    }

    // ---- rename bar

    private void drawRenameBar() {
        if (this.renaming == null) return;

        final int index = this.screenIndex(this.renaming);
        if (index < 0) {
            this.cancelRename();
            return;
        }

        final int y = rowY(index);
        this.placeRenameField(this.renaming);
        this.renameField.y = y + 6;
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        this.drawInset(this.renameField.x - 1, y + 5, this.renameField.w + 2, 14, DARK_INSET_U);
        this.renameField.drawTextBox();
    }

    private void placeRenameField(final WirelessKitRow row) {
        this.renameField.x = rowX(row) + TEXT_X - 3;
        this.renameField.w = ROW_RIGHT - this.renameField.x;
    }

    private void commitRename() {
        final String name = this.renameField.getText().trim();
        if (this.renameSubmit != null && !name.equals(this.renameInitial)) this.renameSubmit.accept(name);
        this.cancelRename();
    }

    private void cancelRename() {
        this.renaming = null;
        this.renameSubmit = null;
        this.renameField.setFocused(false);
    }

    // ---- colour palette

    private static int paletteX() {
        return ROW_RIGHT - PALETTE_W;
    }

    private int paletteY(final int screenIndex) {
        final int below = rowY(screenIndex + 1);
        return below + PALETTE_H <= this.ySize ? below : rowY(screenIndex) - PALETTE_H;
    }

    private void drawPalette(final int mx, final int my) {
        if (this.paletteRow == null) return;

        final int index = this.screenIndex(this.paletteRow);
        if (index < 0) {
            this.paletteRow = null;
            return;
        }

        final int x = paletteX();
        final int y = this.paletteY(index);
        drawRect(x, y, x + PALETTE_W, y + PALETTE_H, ColorUtils.contextMenuBorder.getColor());
        drawRect(x + 1, y + 1, x + PALETTE_W - 1, y + PALETTE_H - 1, ColorUtils.contextMenuBackground.getColor());

        final int hovered = this.paletteCellAt(mx, my);
        for (int cell = 0; cell < PALETTE_CELLS; cell++) {
            // a cell is a 1 px outline around the swatch
            final int ox = x + PALETTE_PAD + cell % PALETTE_COLUMNS * PALETTE_CELL;
            final int oy = y + PALETTE_PAD + cell / PALETTE_COLUMNS * PALETTE_CELL;
            drawRect(
                    ox,
                    oy,
                    ox + PALETTE_CELL,
                    oy + PALETTE_CELL,
                    cell == hovered ? 0xFF000000 | WHITE : ColorUtils.colorButtonOutline.getColor());

            final int sx = ox + 1;
            final int sy = oy + 1;
            final int half = PALETTE_SWATCH / 2;
            if (cell < AEColor.VALUES.length) {
                drawRect(
                        sx,
                        sy,
                        sx + PALETTE_SWATCH,
                        sy + PALETTE_SWATCH,
                        0xFF000000 | AEColor.VALUES[cell].mediumVariant);
            } else {
                // Mad Chameleon picks a colour by itself
                drawRect(sx, sy, sx + half, sy + half, 0xFF000000 | AEColor.Red.mediumVariant);
                drawRect(sx + half, sy, sx + PALETTE_SWATCH, sy + half, 0xFF000000 | AEColor.Yellow.mediumVariant);
                drawRect(sx, sy + half, sx + half, sy + PALETTE_SWATCH, 0xFF000000 | AEColor.Lime.mediumVariant);
                drawRect(
                        sx + half,
                        sy + half,
                        sx + PALETTE_SWATCH,
                        sy + PALETTE_SWATCH,
                        0xFF000000 | AEColor.LightBlue.mediumVariant);
            }
        }
    }

    private int paletteCellAt(final int mx, final int my) {
        if (this.paletteRow == null) return -1;

        final int index = this.screenIndex(this.paletteRow);
        if (index < 0) return -1;

        final int dx = mx - paletteX() - PALETTE_PAD;
        final int dy = my - this.paletteY(index) - PALETTE_PAD;
        if (dx < 0 || dy < 0) return -1;

        final int col = dx / PALETTE_CELL;
        final int row = dy / PALETTE_CELL;
        if (col >= PALETTE_COLUMNS) return -1;

        final int cell = row * PALETTE_COLUMNS + col;
        return cell < PALETTE_CELLS ? cell : -1;
    }

    // ---- input

    @Override
    protected void mouseClicked(final int mouseX, final int mouseY, final int button) {
        final int mx = mouseX - this.guiLeft;
        final int my = mouseY - this.guiTop;

        if (this.paletteRow != null) {
            final int cell = this.paletteCellAt(mx, my);
            if (cell >= 0 && button == 0) {
                this.playClick();
                this.sendRecolor(
                        this.paletteRow.recolorDevices,
                        cell < AEColor.VALUES.length ? AEColor.VALUES[cell] : null);
            }
            this.paletteRow = null;
            return;
        }

        if (this.renaming != null) {
            if (this.renameField.isMouseIn(mx, my)) {
                this.renameField.mouseClicked(mx, my, button);
                return;
            }
            this.cancelRename();
        }

        this.search.mouseClicked(mx, my, button);
        if (this.search.isMouseIn(mx, my)) return;

        if (button <= 1 && isInside(mx, my, SIZE_X, HEADER_Y, BUTTON, BUTTON)) {
            this.playClick();
            this.state.size = this.state.size.next(button == 1);
            this.initGui();
            return;
        }

        if (isInside(mx, my, LIST_X, LIST_Y, LIST_W, this.visibleRows * ROW_H)) {
            final int index = (my - LIST_Y) / ROW_H;
            final int scroll = this.scrollbar.getCurrentScroll();
            if (scroll + index < this.rows.size()
                    && this.clickRow(this.rows.get(scroll + index), rowY(index), mx, my, button)) {
                return;
            }
        }

        super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean clickRow(final WirelessKitRow row, final int y, final int mx, final int my, final int button) {
        for (Placed p : this.layout(row, y)) {
            if (!p.contains(mx, my)) continue;
            if (button == 0 && p.button.enabled && p.button.action != null) {
                this.playClick();
                p.button.action.run();
            }
            return true;
        }

        if (button == 0 && isInside(mx, my, rowX(row), y, ICON_X, ROW_H)) {
            if (row.onToggle != null) {
                row.onToggle.run();
                return true;
            }
            if (row.onCheck != null) {
                this.playClick();
                row.onCheck.run();
                return true;
            }
        }

        if (button == 0 && isShiftKeyDown() && !row.devices.isEmpty()) {
            this.highlightInWorld(row);
            return true;
        }

        final Runnable action = button == 0 ? row.onClick : button == 1 ? row.onRightClick : null;
        if (action == null) return false;

        action.run();
        return true;
    }

    @Override
    protected void keyTyped(final char typedChar, final int keyCode) {
        if (this.renaming != null) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) this.commitRename();
            else if (keyCode == Keyboard.KEY_ESCAPE) this.cancelRename();
            else this.renameField.textboxKeyTyped(typedChar, keyCode);
            return;
        }

        if (this.paletteRow != null && keyCode == Keyboard.KEY_ESCAPE) {
            this.paletteRow = null;
            return;
        }

        if (this.search.isFocused() && this.search.textboxKeyTyped(typedChar, keyCode)) return;

        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected boolean mouseWheelEvent(final int x, final int y, final int wheel) {
        // popups stick to their row, scrolling closes them
        this.paletteRow = null;
        this.cancelRename();
        return false;
    }

    private void highlightInWorld(final WirelessKitRow row) {
        final String[] messages = { PlayerMessages.WirelessHighlighted.getUnlocalized(),
                PlayerMessages.WirelessInOtherDim.getUnlocalized() };
        final Map<NamedDimensionalCoord, String[]> blocks = new HashMap<>();
        row.devices.forEach(device -> blocks.put(device, messages));
        BlockPosHighlighter.highlightNamedBlocks(this.mc.thePlayer, blocks, row.highlightPrefix);
        this.closeGui();
    }

    private void playClick() {
        this.mc.getSoundHandler()
                .playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
    }

    // ---- actions of the rows

    @Override
    public void setActive(final DimensionalCoord device) {
        final WirelessKitCommand command = new WirelessKitCommand(WirelessKitCommands.SET_ACTIVE);
        command.setCommand(SubCommand.single(device));
        send(command);
    }

    @Override
    public void bind(final List<DimensionalCoord> sources) {
        final WirelessToolDataObject target = this.tree == null ? null : this.tree.getBindTarget();
        if (target == null || sources.isEmpty()) return;

        final WirelessKitCommand command = new WirelessKitCommand(WirelessKitCommands.BIND);
        sources.forEach(source -> command.addToBind(SubCommand.single(source)));
        command.addTarget(SubCommand.single(target.cord));
        send(command);
    }

    @Override
    public void unbind(final DimensionalCoord device) {
        final WirelessKitCommand command = new WirelessKitCommand(WirelessKitCommands.UNBIND);
        command.addToBind(SubCommand.single(device));
        send(command);
    }

    @Override
    public void unbindAll(final DimensionalCoord hub) {
        if (this.isUnbindArmed(hub)) {
            this.armedHub = null;
            this.unbind(hub);
        } else {
            this.armedHub = hub;
            this.armedUntil = System.currentTimeMillis() + CONFIRM_MS;
        }
        this.rowsDirty = true;
    }

    @Override
    public boolean isUnbindArmed(final DimensionalCoord hub) {
        return hub.equals(this.armedHub);
    }

    @Override
    public void rename(final WirelessKitRow row, final String current, final Consumer<String> submit) {
        this.renaming = row;
        this.renameInitial = current;
        this.renameSubmit = submit;
        this.placeRenameField(row);
        this.renameField.setText(current, true);
        this.renameField.setCursorPositionEnd();
        this.renameField.setFocused(true);
        this.search.setFocused(false);
    }

    @Override
    public void renameDevice(final DimensionalCoord device, final String name) {
        final WirelessKitCommand command = new WirelessKitCommand(WirelessKitCommands.RENAME_SINGLE);
        command.setName(name);
        command.setCommand(SubCommand.single(device));
        send(command);
    }

    @Override
    public void recolor(final WirelessKitRow row) {
        this.paletteRow = row;
    }

    /** A null colour is Mad Chameleon. */
    private void sendRecolor(final List<DimensionalCoord> devices, @Nullable final AEColor color) {
        final WirelessKitCommand command = new WirelessKitCommand(WirelessKitCommands.RECOLOR);
        devices.forEach(device -> command.addToBind(SubCommand.single(device)));
        command.setColor(color);
        send(command);
    }

    @Override
    public void removeNetwork(final DimensionalCoord anchor) {
        final WirelessKitCommand command = new WirelessKitCommand(WirelessKitCommands.DELETE);
        command.setNetworkPos(anchor);
        send(command);
    }

    @Override
    public void viewChanged() {
        this.rowsDirty = true;
    }

    private static void send(final WirelessKitCommand command) {
        try {
            NetworkHandler.instance.sendToServer(new PacketWirelessToolCommand(command));
        } catch (IOException e) {
            AELog.debug(e);
        }
    }

    private static boolean isInside(final int mx, final int my, final int x, final int y, final int w, final int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private String fit(final String text, final int width) {
        if (this.fontRendererObj.getStringWidth(text) <= width) return text;

        final String ellipsis = "...";
        final int room = width - this.fontRendererObj.getStringWidth(ellipsis);
        return room <= 0 ? "" : this.fontRendererObj.trimStringToWidth(text, room) + ellipsis;
    }
}
