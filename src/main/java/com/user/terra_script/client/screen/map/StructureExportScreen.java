package com.user.terra_script.client.screen.map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.user.terra_script.util.StructureDiscovery;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox; // 引入输入框
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.stream.Collectors;

public class StructureExportScreen extends Screen {
    private final Screen parent;
    private final List<StructureDiscovery.StructureInfo> allStructures;

    // 状态
    private List<String> namespaces;
    private String selectedNamespace = null;

    // 选中的结构集合
    private final Set<ResourceLocation> selectedStructures = new HashSet<>();

    // 控件
    private StructureListWidget structureList;
    private Button exportBtn;
    private EditBox searchBox; // 【新增】搜索框

    public StructureExportScreen(Screen parent, List<StructureDiscovery.StructureInfo> allStructures) {
        super(Component.literal("Structure Exporter"));
        this.parent = parent;
        this.allStructures = allStructures;

        // 提取命名空间
        this.namespaces = allStructures.stream()
                .map(s -> s.id().getNamespace())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        if (!this.namespaces.isEmpty()) {
            this.selectedNamespace = this.namespaces.get(0);
        }
    }

    @Override
    protected void init() {
        int leftPanelW = 140;
        int listX = leftPanelW + 10;
        int listW = this.width - listX - 20;

        // 【新增】搜索框 (位于列表上方)
        this.searchBox = new EditBox(this.font, listX, 10, listW - 60, 20, Component.literal("Search"));
        this.searchBox.setHint(Component.literal("Filter... (e.g. village/plains)"));
        this.searchBox.setResponder(text -> refreshStructureList()); // 输入时自动刷新
        this.addRenderableWidget(this.searchBox);

        // 【新增】清除搜索按钮
        addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
            this.searchBox.setValue("");
            refreshStructureList();
        }).bounds(listX + listW - 55, 10, 55, 20).build());

        // 列表控件 (位置下移，为搜索框留出空间)
        int listTop = 35;
        int listBottom = this.height - 40;

        this.structureList = new StructureListWidget(this.minecraft, listW, this.height, listTop, listBottom, 24);
        this.structureList.setLeftPos(listX);

        this.addRenderableWidget(structureList);

        // 刷新列表 (应用初始过滤)
        refreshStructureList();

        // 底部按钮
        addRenderableWidget(Button.builder(Component.literal("Select All (Filtered)"), b -> selectAllInFilter())
                .bounds(listX, this.height - 30, 120, 20).build());

        this.exportBtn = addRenderableWidget(Button.builder(Component.literal("Export Selected (0)"), b -> export())
                .bounds(this.width - 160, this.height - 30, 150, 20).build());
        updateExportBtn();

        // 返回按钮
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(10, this.height - 30, 120, 20).build());
    }

    private void refreshStructureList() {
        List<StructureDiscovery.StructureInfo> filtered = getFilteredStructures();
        this.structureList.refreshEntries(filtered);
    }

    // 【修改】过滤逻辑：同时匹配 Namespace 和 SearchBox
    private List<StructureDiscovery.StructureInfo> getFilteredStructures() {
        String query = searchBox != null ? searchBox.getValue().toLowerCase() : "";

        return allStructures.stream()
                .filter(s -> {
                    // 1. 检查左侧选中的 Namespace
                    if (selectedNamespace != null && !s.id().getNamespace().equals(selectedNamespace)) {
                        return false;
                    }
                    // 2. 检查搜索框内容 (匹配路径)
                    if (!query.isEmpty() && !s.id().getPath().toLowerCase().contains(query)) {
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    private void selectAllInFilter() {
        List<StructureDiscovery.StructureInfo> filtered = getFilteredStructures();
        // 检查当前过滤列表中是否全部被选中
        boolean allSelected = filtered.stream().allMatch(s -> selectedStructures.contains(s.id()));

        if (allSelected) {
            // 如果全选了，就全取消
            filtered.forEach(s -> selectedStructures.remove(s.id()));
        } else {
            // 否则全选
            filtered.forEach(s -> selectedStructures.add(s.id()));
        }
        updateExportBtn();
        // 强制重绘列表以更新复选框视觉
        // (ObjectSelectionList 不需要手动 refreshEntries，它会在下一帧 render 时读取状态)
    }

    private void updateExportBtn() {
        exportBtn.setMessage(Component.literal("Export Selected (" + selectedStructures.size() + ")"));
    }

    private void export() {
        if (selectedStructures.isEmpty()) return;

        List<StructureDiscovery.StructureInfo> toExport = allStructures.stream()
                .filter(s -> selectedStructures.contains(s.id()))
                .collect(Collectors.toList());

        // 文件名加上搜索词，方便区分 (例如 structures_minecraft_plains.json)
        String suffix = "";
        if (searchBox != null && !searchBox.getValue().isEmpty()) {
            suffix = "_" + searchBox.getValue().replaceAll("[^a-zA-Z0-9]", "");
        }

        String filename = "structures_" + selectedNamespace + suffix + ".json";
        StructureDiscovery.exportToConfig(toExport, filename);

        this.minecraft.player.displayClientMessage(Component.literal("Exported " + toExport.size() + " items to " + filename), false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);

        // 1. 左侧 Namespace 面板
        g.fill(0, 0, 140, this.height, 0xFF222222);
        g.vLine(140, 0, this.height, 0xFF888888);
        g.drawCenteredString(this.font, "Namespaces", 70, 10, 0xFFFFFF);

        int y = 40;
        for (String ns : namespaces) {
            boolean isSelected = ns.equals(selectedNamespace);
            int color = isSelected ? 0xFFFFFF : 0xAAAAAA;

            if (isSelected) {
                g.fill(5, y - 2, 135, y + 10, 0xFF444444);
            }

            g.drawString(this.font, ns, 15, y, color);
            y += 14;
        }

        // 2. 右侧结构列表
        this.structureList.render(g, mouseX, mouseY, partialTick);

        // 列表标题 (由于加了搜索框，标题可以简化或去掉，这里画在搜索框上面一点)
        // g.drawCenteredString(this.font, "Structures", 150 + (this.width - 150)/2, 2, 0xFFFFFF);

        // 3. Tooltip
        var hoveredEntry = this.structureList.getHoveredEntry(mouseX, mouseY);
        if (hoveredEntry != null) {
            g.renderTooltip(this.font, Component.literal(hoveredEntry.info.id().toString()), mouseX, mouseY);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 处理左侧点击
        if (mouseX < 140 && button == 0) {
            int y = 40;
            for (String ns : namespaces) {
                if (mouseY >= y - 2 && mouseY <= y + 10) {
                    this.selectedNamespace = ns;
                    this.searchBox.setValue(""); // 切换命名空间时清空搜索？或者保留看需求
                    refreshStructureList();
                    return true;
                }
                y += 14;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    // --- 内部类：列表控件 (保持不变) ---
    class StructureListWidget extends ObjectSelectionList<StructureListWidget.Entry> {
        public StructureListWidget(Minecraft mc, int width, int height, int top, int bottom, int itemHeight) {
            super(mc, width, height, top, bottom, itemHeight);
            this.setRenderBackground(false);
        }

        public void refreshEntries(List<StructureDiscovery.StructureInfo> list) {
            this.clearEntries();
            for (StructureDiscovery.StructureInfo info : list) {
                this.addEntry(new Entry(info));
            }
            this.setScrollAmount(0);
        }

        public Entry getHoveredEntry(double mouseX, double mouseY) {
            return this.getEntryAtPosition(mouseX, mouseY);
        }

        @Override
        protected int getScrollbarPosition() { return this.getRight() - 6; }

        @Override
        public int getRowWidth() { return this.width - 20; }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            final StructureDiscovery.StructureInfo info;
            final String shortName;

            public Entry(StructureDiscovery.StructureInfo info) {
                this.info = info;
                String path = info.id().getPath();
                int lastSlash = path.lastIndexOf('/');
                this.shortName = (lastSlash >= 0) ? path.substring(lastSlash + 1) : path;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean isHovering, float partialTick) {
                if (isHovering) g.fill(left, top, left + width, top + height, 0x30FFFFFF);

                boolean isChecked = selectedStructures.contains(info.id());
                int checkboxSize = 12;
                int checkboxX = left + 4;
                int checkboxY = top + (height - checkboxSize) / 2;

                g.fill(checkboxX, checkboxY, checkboxX + checkboxSize, checkboxY + checkboxSize, 0xFF000000);
                g.fill(checkboxX + 1, checkboxY + 1, checkboxX + checkboxSize - 1, checkboxY + checkboxSize - 1, 0xFF888888);
                if (isChecked) {
                    g.fill(checkboxX + 3, checkboxY + 3, checkboxX + checkboxSize - 3, checkboxY + checkboxSize - 3, 0xFF55FF55);
                }

                g.drawString(StructureExportScreen.this.font, shortName, left + 20, top + 6, 0xFFFFFF);

                String sizeStr = String.format("%dx%d", info.size().getX(), info.size().getZ());
                g.drawString(StructureExportScreen.this.font, sizeStr, left + width - 40, top + 6, 0xFFAAAAAA);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0) {
                    ResourceLocation id = info.id();
                    if (selectedStructures.contains(id)) selectedStructures.remove(id);
                    else selectedStructures.add(id);
                    updateExportBtn();
                    return true;
                }
                return false;
            }

            @Override
            public Component getNarration() { return Component.literal(shortName); }
        }
    }
}