package com.user.terra_script.client.screen.editor;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.user.terra_script.config.WorldProjectData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.Random;

public class WorldEditorScreen extends Screen {
    private final Screen parent;
    private final WorldProjectData data;

    private int leftPanelWidth = 140; // 加宽一点给编辑按钮
    private int rightPanelWidth = 120;
    private WorldProjectData.Continent selectedContinent = null;

    private EditBox worldNameField;
    private EditBox worldRadiusField;

    // 地图状态
    private double mapScale = 2.0;
    private double mapOffsetX = 0;
    private double mapOffsetY = 0;
    private boolean isDraggingMap = false;
    private boolean isPainting = false;
    private boolean paintModeAdd = true;

    // 鼠标悬停信息
    private String hoverInfo = "";

    public WorldEditorScreen(Screen parent) {
        super(Component.literal("Terra Script Editor"));
        this.parent = parent;
        this.data = WorldProjectData.get();
        if (!data.continents.isEmpty()) selectedContinent = data.continents.get(0);
    }

    @Override
    protected void init() {
        int rightX = this.width - rightPanelWidth + 10;

        this.worldNameField = new EditBox(this.font, rightX, 40, 100, 20, Component.literal("Name"));
        this.worldNameField.setValue(data.worldName);
        this.worldNameField.setResponder(s -> data.worldName = s);
        this.addRenderableWidget(worldNameField);

        this.worldRadiusField = new EditBox(this.font, rightX, 80, 100, 20, Component.literal("Radius"));
        this.worldRadiusField.setValue(String.valueOf(data.worldRadius));
        this.worldRadiusField.setResponder(s -> {
            try { data.worldRadius = Integer.parseInt(s); } catch (Exception ignored){}
        });
        this.addRenderableWidget(worldRadiusField);

        this.addRenderableWidget(Button.builder(Component.literal("Save & Return"), b -> {
            WorldProjectData.save(); // 保存到磁盘
            this.onClose();
        }).bounds(this.width - 110, this.height - 30, 100, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("+ Continent"), b -> {
            Random r = new Random();
            int randomColor = Color.HSBtoRGB(r.nextFloat(), 0.8f, 0.9f);
            WorldProjectData.Continent newC = new WorldProjectData.Continent("Cont-" + (data.continents.size() + 1), randomColor);
            data.continents.add(newC);
            selectedContinent = newC;
        }).bounds(10, this.height - 30, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);

        // 1. Map
        renderMap(guiGraphics, mouseX, mouseY);

        // 2. Left Panel
        guiGraphics.fill(0, 0, leftPanelWidth, this.height, 0xFF222222);
        guiGraphics.drawCenteredString(this.font, "Continents", leftPanelWidth / 2, 10, 0xFFFFFF);

        int listY = 30;
        for (WorldProjectData.Continent c : data.continents) {
            int textColor = c.equals(selectedContinent) ? 0xFF55FF55 : 0xFFAAAAAA;
            // Name
            guiGraphics.drawString(this.font, c.name, 10, listY + 5, textColor);

            // Edit Button Icon (模拟)
            int btnX = leftPanelWidth - 30;
            boolean hoverBtn = mouseX >= btnX && mouseX < btnX + 20 && mouseY >= listY && mouseY < listY + 20;
            int btnColor = hoverBtn ? 0xFFDDDDDD : 0xFF888888;
            guiGraphics.fill(btnX, listY, btnX + 20, listY + 20, btnColor);
            guiGraphics.drawCenteredString(this.font, "E", btnX + 10, listY + 6, 0xFF000000);

            listY += 24;
        }

        // 3. Right Panel
        guiGraphics.fill(this.width - rightPanelWidth, 0, this.width, this.height, 0xFF222222);
        guiGraphics.drawCenteredString(this.font, "Settings", this.width - rightPanelWidth / 2, 10, 0xFFFFFF);
        guiGraphics.drawString(this.font, "World Name", this.width - rightPanelWidth + 10, 30, 0xAAAAAA);
        guiGraphics.drawString(this.font, "Radius", this.width - rightPanelWidth + 10, 70, 0xAAAAAA);

        // 4. Hover Tips (Bottom Center)
        if (!hoverInfo.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, hoverInfo, this.width / 2, this.height - 20, 0xFFFFFF00);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void renderMap(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int mapX = leftPanelWidth;
        int mapW = this.width - leftPanelWidth - rightPanelWidth;
        int centerX = mapW / 2;
        int centerY = this.height / 2;

        // Reset hover info
        this.hoverInfo = "";

        // Check mouse pos
        if (mouseX >= mapX && mouseX < mapX + mapW) {
            int hCx = getChunkXAtMouse(mouseX);
            int hCz = getChunkZAtMouse(mouseY);
            String contName = "Ocean";
            for(var c : data.continents) {
                if(c.containsChunk(hCx, hCz)) {
                    contName = c.name;
                    // 检查是否有固定群系
                    if (c.fixedBiomeChunks.containsKey(WorldProjectData.ChunkPos.asLong(hCx, hCz))) {
                        contName += " (" + c.fixedBiomeChunks.get(WorldProjectData.ChunkPos.asLong(hCx, hCz)) + ")";
                    }
                    break;
                }
            }
            this.hoverInfo = String.format("Chunk: [%d, %d] - %s", hCx, hCz, contName);
        }

        guiGraphics.enableScissor(mapX, 0, mapX + mapW, this.height);
        guiGraphics.fill(mapX, 0, mapX + mapW, this.height, 0xFF101030);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f matrix = guiGraphics.pose().last().pose();

        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (WorldProjectData.Continent continent : data.continents) {
            // Base Color
            int baseColor = continent.color;
            int r = (baseColor >> 16) & 0xFF;
            int g = (baseColor >> 8) & 0xFF;
            int b = (baseColor) & 0xFF;

            for (Long posLong : continent.chunkPositions) {
                int cx = WorldProjectData.ChunkPos.getX(posLong);
                int cz = WorldProjectData.ChunkPos.getZ(posLong);

                // 检查是否有固定群系覆盖颜色
                int finalR = r, finalG = g, finalB = b;
                if (continent.fixedBiomeChunks.containsKey(posLong)) {
                    String biomeId = continent.fixedBiomeChunks.get(posLong);
                    int fixedColor = continent.biomePaletteColors.getOrDefault(biomeId, 0xFFFFFFFF);
                    finalR = (fixedColor >> 16) & 0xFF;
                    finalG = (fixedColor >> 8) & 0xFF;
                    finalB = (fixedColor) & 0xFF;
                }

                double screenX = mapX + centerX + (cx - mapOffsetX) * 16 * mapScale;
                double screenY = centerY + (cz - mapOffsetY) * 16 * mapScale;
                double size = 16 * mapScale;

                if (screenX + size < mapX || screenX > mapX + mapW || screenY + size < 0 || screenY > this.height) continue;

                float padding = (float) (mapScale < 0.5 ? 0 : 0.5);
                buffer.vertex(matrix, (float)screenX + padding, (float)screenY + padding, 0).color(finalR, finalG, finalB, 255).endVertex();
                buffer.vertex(matrix, (float)screenX + padding, (float)(screenY + size - padding), 0).color(finalR, finalG, finalB, 255).endVertex();
                buffer.vertex(matrix, (float)(screenX + size - padding), (float)(screenY + size - padding), 0).color(finalR, finalG, finalB, 255).endVertex();
                buffer.vertex(matrix, (float)(screenX + size - padding), (float)screenY + padding, 0).color(finalR, finalG, finalB, 255).endVertex();
            }
        }
        tesselator.end();
        guiGraphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mapX = leftPanelWidth;
        int mapW = this.width - leftPanelWidth - rightPanelWidth;

        // 1. Map Interaction
        if (mouseX >= mapX && mouseX < mapX + mapW) {
            if (button == 0) { // Paint
                if (selectedContinent != null) {
                    isPainting = true;
                    int cx = getChunkXAtMouse(mouseX);
                    int cz = getChunkZAtMouse(mouseY);
                    paintModeAdd = !selectedContinent.containsChunk(cx, cz);
                    applyPaint(cx, cz);
                    return true;
                }
            } else if (button == 2) { // Drag
                isDraggingMap = true;
                return true;
            }
        }

        // 2. Left Panel Interaction
        if (mouseX < leftPanelWidth) {
            int listY = 30;
            for (WorldProjectData.Continent c : data.continents) {
                // Check if Edit Button Clicked
                int btnX = leftPanelWidth - 30;
                if (mouseY >= listY && mouseY < listY + 20) {
                    if (mouseX >= btnX && mouseX < btnX + 20) {
                        // Open Edit Screen
                        this.minecraft.setScreen(new ContinentEditScreen(this, c));
                        return true;
                    } else {
                        // Select Continent
                        selectedContinent = c;
                        return true;
                    }
                }
                listY += 24;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    // 省略部分重复的 Helper 方法，如 mouseDragged, mouseScrolled (逻辑与之前相同，请保留)
    // 为节省篇幅，这里假设 getChunkXAtMouse 等方法已存在（同前一次代码）

    // --- Helper Methods Copy ---
    private int getChunkXAtMouse(double mouseX) {
        int mapW = this.width - leftPanelWidth - rightPanelWidth;
        int centerX = leftPanelWidth + mapW / 2;
        return (int) Math.floor((mouseX - centerX) / (16 * mapScale) + mapOffsetX);
    }

    private int getChunkZAtMouse(double mouseY) {
        int centerY = this.height / 2;
        return (int) Math.floor((mouseY - centerY) / (16 * mapScale) + mapOffsetY);
    }

    private void applyPaint(int cx, int cz) {
        if (selectedContinent == null) return;
        if (paintModeAdd) {
            for (WorldProjectData.Continent c : data.continents) {
                if (c != selectedContinent) c.removeChunk(cx, cz);
            }
            selectedContinent.addChunk(cx, cz);
        } else {
            selectedContinent.removeChunk(cx, cz);
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
        if (isPainting && mouseX >= leftPanelWidth && mouseX < this.width - rightPanelWidth && selectedContinent != null) {
            applyPaint(getChunkXAtMouse(mouseX), getChunkZAtMouse(mouseY));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= leftPanelWidth && mouseX < this.width - rightPanelWidth) {
            double zoomFactor = 1.1;
            if (delta > 0) mapScale *= zoomFactor;
            else mapScale /= zoomFactor;
            if (mapScale < 0.05) mapScale = 0.05;
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