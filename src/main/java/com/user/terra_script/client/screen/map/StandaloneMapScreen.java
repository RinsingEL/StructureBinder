package com.user.terra_script.client.screen.map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.config.StructurePlan;
import com.user.terra_script.scan.*;
import com.user.terra_script.util.ScanDataIO;
import com.user.terra_script.util.StructureDiscovery;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class StandaloneMapScreen extends Screen {
    // --- 布局常量 ---
    private static final int RIGHT_PANEL_WIDTH = 220; // 右侧面板宽度
    private static final int MAP_PADDING = 10;

    private final Screen parent;
    private final WorldCreationContext context;
    private final long seed;

    // 数据状态
    private ScanPixel[][] scanData = null;
    private int[][] clusterMap = null;

    // 玩家信息
    private Integer playerChunkX = null;
    private Integer playerChunkZ = null;

    // GUI 状态
    private boolean isScanning = false;
    private String statusMsg = "Ready";
    private ClusterAnalyzer.TargetType clusterTarget = ClusterAnalyzer.TargetType.CONTINENT;

    // 交互状态
    private double scale = 1.0;
    private double offX = 0, offY = 0;

    // 右键菜单状态
    private boolean showMenu = false;
    private int menuX = 0, menuY = 0;
    private ScanPixel selectedPixel = null;
    private int selectedR = -1, selectedC = -1;

    // 控件
    private EditBox minSizeInput;
    private EditBox mergeDistInput;

    // 【新增】结构列表控件
    private StructureListWidget structureListWidget;
    private StructureDiscovery.StructureInfo selectedStructure = null; // 当前选中的结构

    public StandaloneMapScreen(Screen parent, WorldCreationContext context) {
        super(Component.literal("World Architect"));
        this.parent = parent;
        this.context = context;

        // 获取种子
        if (context != null) {
            this.seed = context.options().seed();
        } else if (Minecraft.getInstance().getSingleplayerServer() != null) {
            this.seed = Minecraft.getInstance().getSingleplayerServer().getWorldData().worldGenOptions().seed();
        } else if (ScanResultHolder.get().seedUsed != 0) {
            this.seed = ScanResultHolder.get().seedUsed;
        } else {
            this.seed = 0;
        }

        // 获取玩家位置
        if (Minecraft.getInstance().player != null) {
            this.playerChunkX = Minecraft.getInstance().player.chunkPosition().x;
            this.playerChunkZ = Minecraft.getInstance().player.chunkPosition().z;
        }

        // 读取缓存
        var holder = ScanResultHolder.get();
        if (holder.lastScanData != null) {
            this.scanData = holder.lastScanData;
            this.clusterMap = holder.lastClusterMap;
            this.statusMsg = "Loaded cached data.";
        }
    }

    @Override
    protected void init() {
        // 计算布局坐标
        int panelX = this.width - RIGHT_PANEL_WIDTH + 10;
        int btnW = RIGHT_PANEL_WIDTH - 20;
        int y = 10;

        // --- 1. 地形扫描区 ---
        addRenderableWidget(Button.builder(Component.literal("1. Scan Terrain"), b -> startScan())
                .bounds(panelX, y, btnW, 20).build());
        y += 25;

        // --- 2. 聚类控制区 ---
        // 参数行
        this.minSizeInput = new EditBox(this.font, panelX, y, btnW / 2 - 2, 20, Component.literal("Min Size"));
        this.minSizeInput.setValue("5");
        this.minSizeInput.setTooltip(Tooltip.create(Component.literal("Min Pixel Size")));
        addRenderableWidget(this.minSizeInput);

        this.mergeDistInput = new EditBox(this.font, panelX + btnW / 2 + 2, y, btnW / 2 - 2, 20, Component.literal("Merge"));
        this.mergeDistInput.setValue("0");
        this.mergeDistInput.setTooltip(Tooltip.create(Component.literal("Merge Dist")));
        addRenderableWidget(this.mergeDistInput);
        y += 25;

        // 模式切换
        addRenderableWidget(Button.builder(Component.literal("Target: " + clusterTarget.name), b -> {
            if (clusterTarget == ClusterAnalyzer.TargetType.CONTINENT) clusterTarget = ClusterAnalyzer.TargetType.OCEAN;
            else if (clusterTarget == ClusterAnalyzer.TargetType.OCEAN) clusterTarget = ClusterAnalyzer.TargetType.MOUNTAIN;
            else clusterTarget = ClusterAnalyzer.TargetType.CONTINENT;
            b.setMessage(Component.literal("Target: " + clusterTarget.name));
            if (scanData != null) runClustering();
        }).bounds(panelX, y, btnW, 20).build());
        y += 25;

        // 执行聚类
        addRenderableWidget(Button.builder(Component.literal("Run Clustering"), b -> runClustering())
                .bounds(panelX, y, btnW, 20).build());
        y += 30; // 增加间隔

        // --- 3. 结构列表区 ---
        addRenderableWidget(Button.builder(Component.literal("Scan All Structures"), b -> scanStructures())
                .bounds(panelX, y, btnW, 20).build());
        y += 25;

        // 列表控件 (高度自适应，留出底部按钮空间)
        int listBottom = this.height - 60;
        int listHeight = listBottom - y;
        this.structureListWidget = new StructureListWidget(this.minecraft, btnW, listHeight, y, listBottom);
        this.structureListWidget.setLeftPos(panelX); // 设置左边距
        this.addRenderableWidget(structureListWidget);

        // --- 4. 底部控制区 ---
        int bottomY = this.height - 50;

        // 应用并开始
        addRenderableWidget(Button.builder(Component.literal(">>> APPLY & START <<<"), b -> applyAndStart())
                .bounds(panelX, bottomY, btnW, 20).build());

        // 关闭
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(panelX, bottomY + 25, btnW, 20).build());
    }

    // --- 结构扫描逻辑 ---
    private void scanStructures() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            this.statusMsg = "Error: Not in-game.";
            return;
        }

        this.statusMsg = "Scanning structures (Async)...";
        CompletableFuture.supplyAsync(() -> StructureDiscovery.scanAllStructures(server.overworld()))
                .thenAccept(list -> {
                    // 必须在主线程更新 UI
                    Minecraft.getInstance().execute(() -> {
                        this.minecraft.setScreen(new StructureExportScreen(this, list));
                    });
                });
    }

    private void startScan() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            this.statusMsg = "Error: Not in-game.";
            return;
        }
        this.isScanning = true;
        this.statusMsg = "Scanning (Async)...";

        int radiusChunks = 500;
        int targetRes = 100;

        SatelliteScanner.scanAsync(server.overworld(), radiusChunks, targetRes)
                .thenAccept(result -> onScanComplete(result, radiusChunks, targetRes))
                .exceptionally(e -> {
                    this.isScanning = false;
                    this.statusMsg = "Error: " + e.getMessage();
                    e.printStackTrace();
                    return null;
                });
    }

    private void onScanComplete(ScanPixel[][] result, int radiusChunks, int targetRes) {
        this.scanData = result;
        this.clusterMap = null;

        int totalWidth = radiusChunks * 16 * 2;
        int actualStep = Math.max(1, totalWidth / targetRes);

        var holder = ScanResultHolder.get();
        holder.lastScanData = result;
        holder.lastClusterMap = null;
        holder.seedUsed = seed;
        holder.scanRadiusChunks = radiusChunks;
        holder.scanStep = actualStep;

        this.isScanning = false;
        this.statusMsg = "Done. Points: " + (result.length * result[0].length);
        ScanDataIO.saveAll();
    }

    private void runClustering() {
        if (scanData == null) { this.statusMsg = "No data."; return; }
        int minSize = 5;
        int mergeDist = 0;
        try {
            minSize = Integer.parseInt(minSizeInput.getValue());
            mergeDist = Integer.parseInt(mergeDistInput.getValue());
        } catch (NumberFormatException ignored) {}

        this.statusMsg = "Clustering...";
        List<ScanRegion> regions = ClusterAnalyzer.analyze(scanData, clusterTarget, minSize, mergeDist);
        generateRenderMask(regions);

        ScanResultHolder.get().lastClusters = regions;
        ScanResultHolder.get().lastClusterMap = this.clusterMap;
        this.statusMsg = "Found " + regions.size() + " regions.";
        ScanDataIO.saveAll();
    }

    private void generateRenderMask(List<ScanRegion> regions) {
        int rows = scanData.length;
        int cols = scanData[0].length;
        this.clusterMap = new int[rows][cols];
        java.util.Map<ScanPixel, Integer> pixelToId = new java.util.HashMap<>();
        for (ScanRegion r : regions) for (ScanPixel p : r.pixels) pixelToId.put(p, r.id);

        for(int r=0; r<rows; r++) {
            for(int c=0; c<cols; c++) {
                if(scanData[r][c] != null && pixelToId.containsKey(scanData[r][c])) {
                    this.clusterMap[r][c] = pixelToId.get(scanData[r][c]);
                }
            }
        }
    }

    private void applyAndStart() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        this.statusMsg = "Applying...";

        Map<Long, String> plan = StructurePlan.get().getAllPlans();
        plan.forEach((posLong, structId) -> {
            net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(posLong);
            if (level.hasChunk(cp.x, cp.z)) {
                server.execute(() -> com.user.terra_script.world.StructureInjector.spawnStructure(level, cp, structId));
                StructurePlan.get().removeStructure(cp.x, cp.z);
            }
        });

        server.execute(() -> {
            if (Minecraft.getInstance().player == null) return;
            var playerUUID = Minecraft.getInstance().player.getUUID();
            var serverPlayer = server.getPlayerList().getPlayer(playerUUID);
            if (serverPlayer != null) {
                serverPlayer.setGameMode(GameType.SURVIVAL);
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, serverPlayer.getBlockX(), serverPlayer.getBlockZ());
                serverPlayer.teleportTo(serverPlayer.getBlockX(), h + 1, serverPlayer.getBlockZ());
                serverPlayer.setHealth(serverPlayer.getMaxHealth());
            }
        });
        this.onClose();
    }

    // --- 交互逻辑 ---

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 1. 如果点击了右侧面板，优先处理子控件 (列表、按钮)
        if (mouseX > this.width - RIGHT_PANEL_WIDTH) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // 2. 菜单交互
        if (showMenu) {
            int w = 120, h = 68;
            if (mouseX >= menuX && mouseX <= menuX + w && mouseY >= menuY && mouseY <= menuY + h) {
                if (mouseY <= menuY + 20) {
                    // Teleport
                    if (selectedPixel != null && Minecraft.getInstance().player != null) {
                        String cmd = String.format("tp @s %d 150 %d", selectedPixel.x(), selectedPixel.z());
                        Minecraft.getInstance().player.connection.sendCommand(cmd);
                        this.statusMsg = "Teleported.";
                    }
                } else if (mouseY <= menuY + 42) {
                    // Place Selected Structure
                    if (selectedPixel != null) {
                        if (selectedStructure != null) {
                            int cx = selectedPixel.x() >> 4;
                            int cz = selectedPixel.z() >> 4;
                            StructurePlan.get().addStructure(cx, cz, selectedStructure.id().toString());
                            this.statusMsg = "Planned: " + selectedStructure.id().getPath();
                        } else {
                            this.statusMsg = "No structure selected in list!";
                        }
                    }
                } else if (mouseY >= menuY + 44) {
                    // Inspect Region
                    int rId = getRegionIdAt();
                    if (rId > 0) {
                        ScanRegion target = null;
                        if (ScanResultHolder.get().lastClusters != null) {
                            for (ScanRegion reg : ScanResultHolder.get().lastClusters) {
                                if (reg.id == rId) { target = reg; break; }
                            }
                        }
                        if (target != null) {
                            this.minecraft.setScreen(new RegionEditorScreen(this, target));
                        }
                    }
                }
                showMenu = false;
                return true;
            } else {
                showMenu = false;
                return true;
            }
        }

        // 3. 地图交互
        int mapW = this.width - RIGHT_PANEL_WIDTH - (MAP_PADDING * 2);
        int mapH = this.height - (MAP_PADDING * 2);
        int mapX = MAP_PADDING;
        int mapY = MAP_PADDING;

        if (scanData != null && mouseX >= mapX && mouseX < mapX + mapW && mouseY >= mapY && mouseY < mapY + mapH) {
            if (button == 1) { // 右键
                int cx = mapX + mapW / 2;
                int cy = mapY + mapH / 2;
                int r = (int)((mouseX - cx) / scale - offX + scanData.length / 2.0);
                int c = (int)((mouseY - cy) / scale - offY + scanData[0].length / 2.0);

                if (r >= 0 && r < scanData.length && c >= 0 && c < scanData[0].length) {
                    this.selectedPixel = scanData[r][c];
                    if (this.selectedPixel != null) {
                        this.selectedR = r;
                        this.selectedC = c;
                        this.showMenu = true;
                        this.menuX = (int)mouseX;
                        this.menuY = (int)mouseY;
                        return true;
                    }
                }
            } else if (button == 0) { showMenu = false; }
        } else { showMenu = false; }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    // --- 渲染逻辑 ---

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);

        // 1. 绘制右侧面板背景
        int panelX = this.width - RIGHT_PANEL_WIDTH;
        g.fill(panelX, 0, this.width, this.height, 0xFF222222);
        g.vLine(panelX, 0, this.height, 0xFFAAAAAA); // 分割线

        // 2. 绘制地图背景 (左侧区域)
        int mapW = panelX - (MAP_PADDING * 2);
        int mapH = this.height - (MAP_PADDING * 2);
        g.fill(MAP_PADDING, MAP_PADDING, MAP_PADDING + mapW, MAP_PADDING + mapH, 0xFF111111);

        if (scanData != null) {
            drawMap(g, MAP_PADDING, MAP_PADDING, mapW, mapH);
        }

        // 3. 状态栏 (左下角)
        g.drawString(this.font, statusMsg, MAP_PADDING + 5, MAP_PADDING + mapH - 15, 0xFFFFFFFF);
        if (isScanning) g.drawCenteredString(this.font, "SCANNING...", mapW / 2, mapH / 2, 0xFFFF0000);

        // 4. 上下文菜单 & Tooltip
        if (!showMenu) drawTooltip(g, mouseX, mouseY, MAP_PADDING, MAP_PADDING, mapW, mapH);
        if (showMenu) renderContextMenu(g, mouseX, mouseY);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawMap(GuiGraphics g, int x, int y, int w, int h) {
        g.enableScissor(x, y, x+w, y+h);
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f mat = g.pose().last().pose();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        int rows = scanData.length;
        int cols = scanData[0].length;
        double pSize = scale;
        int cx = x + w/2;
        int cy = y + h/2;

        for (int r=0; r<rows; r++) {
            for (int c=0; c<cols; c++) {
                ScanPixel p = scanData[r][c];
                if (p == null) continue;
                double sx = cx + (r - rows/2.0 + offX) * pSize;
                double sy = cy + (c - cols/2.0 + offY) * pSize;
                if (sx < x-pSize || sx > x+w || sy < y-pSize || sy > y+h) continue;

                int color = 0xFF000044; // Ocean

                int chunkX = p.x() >> 4;
                int chunkZ = p.z() >> 4;
                boolean hasPlan = StructurePlan.get().getStructureAt(chunkX, chunkZ) != null;

                if (hasPlan) {
                    color = 0xFFFFFF00; // Plan = Yellow
                } else if (clusterMap != null && clusterMap[r][c] > 0) {
                    int cid = clusterMap[r][c];
                    if (clusterTarget == ClusterAnalyzer.TargetType.MOUNTAIN) color = Color.HSBtoRGB((cid * 0.1f) % 0.15f, 0.9f, 1.0f);
                    else if (clusterTarget == ClusterAnalyzer.TargetType.OCEAN) color = Color.HSBtoRGB(0.6f + (cid * 0.05f) % 0.1f, 0.8f, 0.9f);
                    else color = Color.HSBtoRGB((cid * 0.618f) % 1.0f, 0.8f, 0.9f);
                } else {
                    if (p.isLand()) {
                        int val = Math.min(255, (p.height() - 63) * 2 + 50);
                        color = 0xFF000000 | (val << 8);
                    } else if (p.height() > 45) {
                        color = 0xFF004488;
                    }
                }

                int red = (color >> 16) & 0xFF;
                int grn = (color >> 8) & 0xFF;
                int blu = (color) & 0xFF;

                buf.vertex(mat, (float)sx, (float)sy, 0).color(red, grn, blu, 255).endVertex();
                buf.vertex(mat, (float)sx, (float)(sy+pSize), 0).color(red, grn, blu, 255).endVertex();
                buf.vertex(mat, (float)(sx+pSize), (float)(sy+pSize), 0).color(red, grn, blu, 255).endVertex();
                buf.vertex(mat, (float)(sx+pSize), (float)sy, 0).color(red, grn, blu, 255).endVertex();
            }
        }

        drawPlayerCrosshair(buf, mat, x, y, w, h);
        tess.end();
        g.disableScissor();
    }

    private void drawPlayerCrosshair(BufferBuilder buf, Matrix4f mat, int mapX, int mapY, int mapW, int mapH) {
        if (playerChunkX == null || playerChunkZ == null) return;
        var holder = ScanResultHolder.get();
        if (holder.scanStep == 0) return;

        int step = holder.scanStep;
        int radiusBlocks = holder.scanRadiusChunks * 16;
        int rows = scanData.length;
        int cols = scanData[0].length;
        int cx = mapX + mapW / 2;
        int cy = mapY + mapH / 2;

        int pX = playerChunkX * 16;
        int pZ = playerChunkZ * 16;

        double gridR = (double)(pX + radiusBlocks) / step;
        double gridC = (double)(pZ + radiusBlocks) / step;

        double sx = cx + (gridR - rows/2.0 + offX) * scale;
        double sy = cy + (gridC - cols/2.0 + offY) * scale;

        float size = 4.0f;
        int r=255, g=50, b=50, a=255;

        buf.vertex(mat, (float)(sx - size), (float)sy - 1, 0).color(r, g, b, a).endVertex();
        buf.vertex(mat, (float)(sx - size), (float)(sy + 1), 0).color(r, g, b, a).endVertex();
        buf.vertex(mat, (float)(sx + size), (float)(sy + 1), 0).color(r, g, b, a).endVertex();
        buf.vertex(mat, (float)(sx + size), (float)sy - 1, 0).color(r, g, b, a).endVertex();
        buf.vertex(mat, (float)sx - 1, (float)(sy - size), 0).color(r, g, b, a).endVertex();
        buf.vertex(mat, (float)sx - 1, (float)(sy + size), 0).color(r, g, b, a).endVertex();
        buf.vertex(mat, (float)(sx + 1), (float)(sy + size), 0).color(r, g, b, a).endVertex();
        buf.vertex(mat, (float)(sx + 1), (float)(sy - size), 0).color(r, g, b, a).endVertex();
    }

    private void renderContextMenu(GuiGraphics g, int mouseX, int mouseY) {
        int w = 120;
        int h = 68;
        g.fill(menuX, menuY, menuX + w, menuY + h, 0xFF222222);
        g.renderOutline(menuX, menuY, w, h, 0xFFFFFFFF);

        boolean hoverTp = mouseX >= menuX && mouseX <= menuX + w && mouseY >= menuY && mouseY <= menuY + 20;
        g.fill(menuX + 1, menuY + 1, menuX + w - 1, menuY + 20, hoverTp ? 0xFF444444 : 0xFF333333);
        g.drawCenteredString(this.font, "Teleport", menuX + w/2, menuY + 6, 0xFFFFFF);

        boolean hoverPl = mouseX >= menuX && mouseX <= menuX + w && mouseY >= menuY + 22 && mouseY <= menuY + 44;
        g.fill(menuX + 1, menuY + 22, menuX + w - 1, menuY + 44, hoverPl ? 0xFF444444 : 0xFF333333);

        // 显示当前选中的结构名，或者提示选择
        String placeText = "Place Structure";
        int textColor = 0xFFFFFF;
        if (selectedStructure != null) {
            placeText = "Place: " + selectedStructure.id().getPath();
            if (placeText.length() > 15) placeText = placeText.substring(0, 15) + "..";
        } else {
            placeText = "Select Struct First";
            textColor = 0xFF5555;
        }
        g.drawCenteredString(this.font, placeText, menuX + w/2, menuY + 28, textColor);

        int rId = getRegionIdAt();
        boolean hoverInsp = mouseX >= menuX && mouseX <= menuX + w && mouseY >= menuY + 44 && mouseY <= menuY + 64;
        int inspColor = (rId > 0) ? 0xFFFFFF : 0x888888;
        g.fill(menuX + 1, menuY + 44, menuX + w - 1, menuY + 64, hoverInsp && rId > 0 ? 0xFF444444 : 0xFF333333);
        g.drawCenteredString(this.font, "Inspect Region", menuX + w/2, menuY + 50, inspColor);
    }

    private void drawTooltip(GuiGraphics g, int mx, int my, int x, int y, int w, int h) {
        if (scanData == null) return;
        if (mx < x || mx > x+w || my < y || my > y+h) return;
        int cx = x + w/2;
        int cy = y + h/2;
        int r = (int)((mx - cx)/scale - offX + scanData.length/2.0);
        int c = (int)((my - cy)/scale - offY + scanData[0].length/2.0);

        if (r >= 0 && r < scanData.length && c >= 0 && c < scanData[0].length) {
            ScanPixel p = scanData[r][c];
            if (p != null) {
                List<Component> list = new ArrayList<>();
                list.add(Component.literal("Block: [" + p.x() + ", " + p.z() + "]"));
                list.add(Component.literal("H: " + p.height() + (p.isLand() ? " (L)" : " (W)")));
                if (clusterMap != null && clusterMap[r][c] > 0) {
                    list.add(Component.literal("Region ID: " + clusterMap[r][c]));
                }

                int chunkX = p.x() >> 4;
                int chunkZ = p.z() >> 4;
                String plan = StructurePlan.get().getStructureAt(chunkX, chunkZ);
                if (plan != null) {
                    list.add(Component.literal("§ePlanned: " + plan));
                }

                g.renderTooltip(this.font, list, java.util.Optional.empty(), mx, my);
            }
        }
    }

    private int getRegionIdAt() {
        if (clusterMap == null) return 0;
        if (selectedR < 0 || selectedR >= clusterMap.length) return 0;
        if (selectedC < 0 || selectedC >= clusterMap[0].length) return 0;
        return clusterMap[selectedR][selectedC];
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (btn == 0) { offX += dx / scale; offY += dy / scale; return true; }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }
    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (delta > 0) scale *= 1.1; else scale /= 1.1;
        return true;
    }
    @Override
    public void onClose() {
        if (parent == null) this.minecraft.setScreen(null);
        else this.minecraft.setScreen(parent);
    }

    // --- 内部类: 结构列表控件 ---
    class StructureListWidget extends ObjectSelectionList<StructureListWidget.Entry> {
        public StructureListWidget(Minecraft mc, int width, int height, int top, int bottom) {
            super(mc, width, height, top, bottom, 18); // 18px per item
            this.setRenderBackground(false);
            this.setRenderTopAndBottom(false);
        }

        public void refreshList(List<StructureDiscovery.StructureInfo> list) {
            this.clearEntries();
            for (StructureDiscovery.StructureInfo info : list) {
                this.addEntry(new Entry(info));
            }
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            private final StructureDiscovery.StructureInfo info;

            public Entry(StructureDiscovery.StructureInfo info) {
                this.info = info;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean isHovering, float partialTick) {
                // 背景高亮
                if (selectedStructure == this.info) {
                    g.fill(left, top, left + width, top + height, 0xFF555555);
                } else if (isHovering) {
                    g.fill(left, top, left + width, top + height, 0xFF333333);
                }

                // 渲染文本
                String name = info.id().toString().replace("minecraft:", ""); // 简化显示
                // 截断长文本
                if (name.length() > 20) name = name.substring(0, 18) + "..";

                g.drawString(StandaloneMapScreen.this.font, name, left + 2, top + 2, 0xFFFFFF);

                // 渲染尺寸 (右对齐)
                String sizeStr = String.format("%dx%d", info.size().getX(), info.size().getZ());
                g.drawString(StandaloneMapScreen.this.font, sizeStr, left + width - 35, top + 2, 0xFFAAAAAA);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0) {
                    StandaloneMapScreen.this.selectedStructure = this.info;
                    return true;
                }
                return false;
            }

            @Override
            public Component getNarration() { return Component.literal(info.id().toString()); }
        }
    }
}