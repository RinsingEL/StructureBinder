package com.user.terra_script.client.screen.editor;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.user.terra_script.config.WorldProjectData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ContinentEditScreen extends Screen {
    private final Screen parent;
    private final WorldProjectData.Continent continent;

    private int leftPanelWidth = 140;

    // Palette State
    private String selectedBiomeId = null;
    private List<String> paletteList = new ArrayList<>();

    // Map State
    private double mapScale = 3.0;
    private double mapOffsetX = 0;
    private double mapOffsetY = 0;
    private boolean isDraggingMap = false;
    private boolean isPainting = false;

    public ContinentEditScreen(Screen parent, WorldProjectData.Continent continent) {
        super(Component.literal("Edit Continent: " + continent.name));
        this.parent = parent;
        this.continent = continent;
        // Init palette list
        this.paletteList.addAll(continent.biomePaletteColors.keySet());
        if (!paletteList.isEmpty()) selectedBiomeId = paletteList.get(0);
    }

    @Override
    protected void init() {
        // --- Button: Add New Biome ---
        // 点击后打开 BiomeSelectionScreen
        this.addRenderableWidget(Button.builder(Component.literal("+ Add Biome"), b -> {
            this.minecraft.setScreen(new BiomeSelectionScreen(this, (resourceLocation) -> {
                String id = resourceLocation.toString();
                // 回调处理：如果该群系不在列表中，则添加
                if (!continent.biomePaletteColors.containsKey(id)) {
                    Random r = new Random(id.hashCode());
                    int color = Color.HSBtoRGB(r.nextFloat(), 0.6f, 1.0f);
                    continent.biomePaletteColors.put(id, color);
                    continent.biomeWeights.putIfAbsent(id, 10);

                    // 刷新列表
                    paletteList.clear();
                    paletteList.addAll(continent.biomePaletteColors.keySet());
                    selectedBiomeId = id;
                }
            }));
        }).bounds(10, this.height - 55, leftPanelWidth - 20, 20).build());

        // --- Back Button ---
        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(10, this.height - 30, leftPanelWidth - 20, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);

        // 1. Render Map
        renderMap(guiGraphics, mouseX, mouseY);

        // 2. Left Panel Background
        guiGraphics.fill(0, 0, leftPanelWidth, this.height, 0xFF333333);
        guiGraphics.drawCenteredString(this.font, "Biome Palette", leftPanelWidth / 2, 10, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, "(Left:Paint / Right:Clear)", leftPanelWidth / 2, 20, 0xAAAAAA);

        // 3. Render Biome List
        int listY = 40;
        int itemHeight = 16;

        for (String biomeId : paletteList) {
            int color = continent.biomePaletteColors.get(biomeId);
            boolean isSelected = biomeId.equals(selectedBiomeId);

            // Highlight background
            if (isSelected) {
                guiGraphics.fill(5, listY - 2, leftPanelWidth - 5, listY + 12, 0xFF555555);
            }

            // Color Icon
            guiGraphics.fill(10, listY, 20, listY + 10, color | 0xFF000000);

            // Name Truncation
            String displayName = biomeId.replace("minecraft:", "");
            if(displayName.length() > 14) displayName = displayName.substring(0, 14) + "..";
            guiGraphics.drawString(this.font, displayName, 25, listY + 1, isSelected ? 0xFFFFFFFF : 0xFFAAAAAA);

            // Weight Info (模拟，暂时不可编辑权重，可后续点击弹窗编辑)
            int weight = continent.biomeWeights.getOrDefault(biomeId, 0);
            guiGraphics.drawString(this.font, String.valueOf(weight), leftPanelWidth - 25, listY + 1, 0xFFDDDDDD);

            listY += itemHeight;
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Tooltip for map
        if (mouseX > leftPanelWidth) {
            int cx = getChunkXAtMouse(mouseX);
            int cz = getChunkZAtMouse(mouseY);
            if (continent.containsChunk(cx, cz)) {
                String b = continent.fixedBiomeChunks.get(WorldProjectData.ChunkPos.asLong(cx, cz));
                String tip = "Chunk [" + cx + "," + cz + "] " + (b == null ? "(Random Pool)" : b);
                guiGraphics.drawCenteredString(this.font, tip, this.width/2 + leftPanelWidth/2, this.height - 20, 0xFFFFFF00);
            }
        }
    }

    private void renderMap(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int mapX = leftPanelWidth;
        // 修正宽度计算，确保不留白
        int mapW = this.width - leftPanelWidth;
        int centerX = mapW / 2;
        int centerY = this.height / 2;

        guiGraphics.enableScissor(mapX, 0, this.width, this.height);
        guiGraphics.fill(mapX, 0, this.width, this.height, 0xFF101015);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f matrix = guiGraphics.pose().last().pose();

        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        int baseR = (continent.color >> 16) & 0xFF;
        int baseG = (continent.color >> 8) & 0xFF;
        int baseB = (continent.color) & 0xFF;

        for (Long posLong : continent.chunkPositions) {
            int cx = WorldProjectData.ChunkPos.getX(posLong);
            int cz = WorldProjectData.ChunkPos.getZ(posLong);

            double screenX = mapX + centerX + (cx - mapOffsetX) * 16 * mapScale;
            double screenY = centerY + (cz - mapOffsetY) * 16 * mapScale;
            double size = 16 * mapScale;

            if (screenX + size < mapX || screenX > this.width || screenY + size < 0 || screenY > this.height) continue;

            // Check fixed biome
            int r = baseR, g = baseG, b = baseB;
            if (continent.fixedBiomeChunks.containsKey(posLong)) {
                String bid = continent.fixedBiomeChunks.get(posLong);
                int c = continent.biomePaletteColors.getOrDefault(bid, 0xFFFFFFFF);
                r = (c >> 16) & 0xFF;
                g = (c >> 8) & 0xFF;
                b = (c) & 0xFF;
            }

            float padding = (float) (mapScale < 0.5 ? 0 : 0.5);
            buffer.vertex(matrix, (float)screenX + padding, (float)screenY + padding, 0).color(r, g, b, 255).endVertex();
            buffer.vertex(matrix, (float)screenX + padding, (float)(screenY + size - padding), 0).color(r, g, b, 255).endVertex();
            buffer.vertex(matrix, (float)(screenX + size - padding), (float)(screenY + size - padding), 0).color(r, g, b, 255).endVertex();
            buffer.vertex(matrix, (float)(screenX + size - padding), (float)screenY + padding, 0).color(r, g, b, 255).endVertex();
        }

        tesselator.end();
        guiGraphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX > leftPanelWidth) {
            if (button == 0) {
                if (selectedBiomeId != null) {
                    isPainting = true;
                    applyPaint(getChunkXAtMouse(mouseX), getChunkZAtMouse(mouseY));
                    return true;
                }
            } else if (button == 2) {
                isDraggingMap = true;
                return true;
            } else if (button == 1) {
                int cx = getChunkXAtMouse(mouseX);
                int cz = getChunkZAtMouse(mouseY);
                if (continent.containsChunk(cx, cz)) {
                    continent.setFixedBiome(cx, cz, null);
                }
                return true;
            }
        }

        if (mouseX < leftPanelWidth) {
            int listY = 40;
            int itemHeight = 16;
            for (String bid : paletteList) {
                if (mouseY >= listY && mouseY < listY + itemHeight) {
                    selectedBiomeId = bid;
                    return true;
                }
                listY += itemHeight;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private int getChunkXAtMouse(double mouseX) {
        int mapW = this.width - leftPanelWidth;
        int centerX = leftPanelWidth + mapW / 2;
        return (int) Math.floor((mouseX - centerX) / (16 * mapScale) + mapOffsetX);
    }
    private int getChunkZAtMouse(double mouseY) {
        int centerY = this.height / 2;
        return (int) Math.floor((mouseY - centerY) / (16 * mapScale) + mapOffsetY);
    }

    private void applyPaint(int cx, int cz) {
        if (continent.containsChunk(cx, cz)) {
            continent.setFixedBiome(cx, cz, selectedBiomeId);
        }
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        isDraggingMap = false;
        isPainting = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (isDraggingMap) {
            mapOffsetX -= dragX / (16 * mapScale);
            mapOffsetY -= dragY / (16 * mapScale);
            return true;
        }
        if (isPainting && mouseX > leftPanelWidth) {
            applyPaint(getChunkXAtMouse(mouseX), getChunkZAtMouse(mouseY));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX > leftPanelWidth) {
            double zoomFactor = 1.1;
            if (delta > 0) mapScale *= zoomFactor;
            else mapScale /= zoomFactor;
            if (mapScale < 0.1) mapScale = 0.1;
            if (mapScale > 10.0) mapScale = 10.0;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}