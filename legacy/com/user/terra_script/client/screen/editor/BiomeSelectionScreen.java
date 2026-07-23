package com.user.terra_script.client.screen.editor;

import com.user.terra_script.TerraScriptMod;
import com.user.terra_script.config.WorldProjectData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@SuppressWarnings("removal")
public class BiomeSelectionScreen extends Screen {
    private final Screen parent;
    private final Consumer<ResourceLocation> onSelect;
    private BiomeList biomeList;
    private EditBox searchBox;
    private final List<ResourceLocation> allBiomes;

    public BiomeSelectionScreen(Screen parent, Consumer<ResourceLocation> onSelect) {
        super(Component.literal("Select Biome"));
        this.parent = parent;
        this.onSelect = onSelect;

        this.allBiomes = new ArrayList<>();
        for (String id : WorldProjectData.availableBiomes) {
            this.allBiomes.add(new ResourceLocation(id));
        }
        // 如果列表是空的(例如直接调试GUI时)，防止崩溃，可以加个保底
        if (this.allBiomes.isEmpty()) {
            TerraScriptMod.LOGGER.error("怎么是空的啊（恼）");
            this.allBiomes.add(new ResourceLocation("minecraft:plains"));
        }
    }

    @Override
    protected void init() {
        // 1. 搜索框
        this.searchBox = new EditBox(this.font, this.width / 2 - 100, 22, 200, 20, Component.literal("Search"));
        this.searchBox.setResponder(this::refreshList);
        this.addRenderableWidget(searchBox);

        // 2. 列表控件
        this.biomeList = new BiomeList(this.minecraft, this.width, this.height, 50, this.height - 40, 24);
        this.addRenderableWidget(biomeList);

        // 初始填充
        refreshList(searchBox.getValue());

        // 3. 底部按钮
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose())
                .bounds(this.width / 2 - 75, this.height - 30, 150, 20).build());
    }

    private void refreshList(String query) {
        this.biomeList.updateEntries(query);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        this.biomeList.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    // --- 内部列表类 ---
    class BiomeList extends ObjectSelectionList<BiomeList.Entry> {
        public BiomeList(Minecraft mc, int width, int height, int top, int bottom, int itemHeight) {
            super(mc, width, height, top, bottom, itemHeight);
        }

        public void updateEntries(String query) {
            this.clearEntries();
            String q = query.toLowerCase();
            for (ResourceLocation id : allBiomes) {
                if (q.isEmpty() || id.toString().contains(q)) {
                    this.addEntry(new Entry(id));
                }
            }
            this.setScrollAmount(0);
        }

        public class Entry extends ObjectSelectionList.Entry<Entry> {
            private final ResourceLocation id;

            public Entry(ResourceLocation id) {
                this.id = id;
            }

            @Override
            public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean isHovering, float partialTick) {
                // 绘制背景 (可选: 悬停高亮由 List 控件自动处理一部分，但这里可以自定义)
                if (isHovering) {
                    guiGraphics.fill(left, top, left + width, top + height, 0x40FFFFFF);
                }

                // 绘制文本
                Component name = Component.translatable(Util.makeDescriptionId("biome", id));
                guiGraphics.drawString(BiomeSelectionScreen.this.font, name, left + 5, top + 3, 0xFFFFFF);
                guiGraphics.drawString(BiomeSelectionScreen.this.font, id.toString(), left + 5, top + 13, 0x888888);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0) {
                    // 回调并关闭
                    BiomeSelectionScreen.this.onSelect.accept(this.id);
                    BiomeSelectionScreen.this.onClose();
                    return true;
                }
                return false;
            }

            @Override
            public Component getNarration() {
                return Component.literal(id.toString());
            }
        }
    }
}