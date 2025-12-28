package com.user.terra_script.client.screen.map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.config.StructurePlan;
import com.user.terra_script.scan.*;
import com.user.terra_script.util.ScanDataIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
import java.util.List;
import java.util.Map;

public class StandaloneMapScreen extends Screen {
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

    // 记录当前右键选中的网格坐标
    private int selectedR = -1;
    private int selectedC = -1;

    // 控件
    private EditBox minSizeInput;
    private EditBox mergeDistInput;

    public StandaloneMapScreen(Screen parent, WorldCreationContext context) {
        super(Component.literal("Satellite Map Debugger"));
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
        int btnY = 10;
        int btnH = 20;
        int rightX = this.width - 10;

        // 1. 扫描按钮
        addRenderableWidget(Button.builder(Component.literal("1. Scan Terrain"), b -> startScan())
                .bounds(10, btnY, 120, btnH).build());

        // 2. 右侧控制栏
        // Close
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(rightX - 50, btnY, 50, btnH).build());

        // Cluster
        addRenderableWidget(Button.builder(Component.literal("Cluster"), b -> runClustering())
                .bounds(rightX - 50 - 5 - 60, btnY, 60, btnH).build());

        // Merge Dist Input
        this.mergeDistInput = new EditBox(this.font, rightX - 115 - 5 - 40, btnY, 40, btnH, Component.literal("Merge"));
        this.mergeDistInput.setValue("0");
        this.mergeDistInput.setTooltip(Tooltip.create(Component.literal("Merge Dist (Blocks)")));
        addRenderableWidget(this.mergeDistInput);

        // Min Size Input
        this.minSizeInput = new EditBox(this.font, rightX - 160 - 5 - 40, btnY, 40, btnH, Component.literal("Size"));
        this.minSizeInput.setValue("5");
        this.minSizeInput.setTooltip(Tooltip.create(Component.literal("Min Pixel Size")));
        addRenderableWidget(this.minSizeInput);

        // Mode Switch
        addRenderableWidget(Button.builder(Component.literal("Mode: " + clusterTarget.name), b -> {
            if (clusterTarget == ClusterAnalyzer.TargetType.CONTINENT) clusterTarget = ClusterAnalyzer.TargetType.OCEAN;
            else if (clusterTarget == ClusterAnalyzer.TargetType.OCEAN) clusterTarget = ClusterAnalyzer.TargetType.MOUNTAIN;
            else clusterTarget = ClusterAnalyzer.TargetType.CONTINENT;

            b.setMessage(Component.literal("Mode: " + clusterTarget.name));
            if (scanData != null) runClustering();
        }).bounds(rightX - 205 - 5 - 100, btnY, 100, btnH).build());

        // 3. 应用并开始游戏
        addRenderableWidget(Button.builder(Component.literal(">>> 2. APPLY & START <<<"), b -> applyAndStart())
                .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());
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

        // 1. 生成所有计划中的结构
        Map<Long, String> plan = StructurePlan.get().getAllPlans();
        plan.forEach((posLong, structId) -> {
            net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(posLong);
            if (level.hasChunk(cp.x, cp.z)) {
                server.execute(() -> com.user.terra_script.world.StructureInjector.spawnStructure(level, cp, structId));
                StructurePlan.get().removeStructure(cp.x, cp.z);
            }
        });

        // 2. 强制切换玩家模式并传送 (使用 ServerPlayer，无需作弊权限)
        server.execute(() -> {
            if (Minecraft.getInstance().player == null) return;
            var playerUUID = Minecraft.getInstance().player.getUUID();
            var serverPlayer = server.getPlayerList().getPlayer(playerUUID);

            if (serverPlayer != null) {
                // 强制切换生存模式
                serverPlayer.setGameMode(GameType.SURVIVAL);

                // 计算安全落地高度
                int currentX = serverPlayer.getBlockX();
                int currentZ = serverPlayer.getBlockZ();
                int safeY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, currentX, currentZ);

                // 传送
                serverPlayer.teleportTo(currentX, safeY + 1, currentZ);
            }
        });

        this.onClose();
    }

    // --- 交互逻辑 ---

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 1. 菜单交互
        if (showMenu) {
            int w = 100;
            int h = 68; // 【关键修复】高度设为 68 以容纳第三个按钮

            // 判定是否点击在菜单范围内
            if (mouseX >= menuX && mouseX <= menuX + w && mouseY >= menuY && mouseY <= menuY + h) {

                if (mouseY <= menuY + 20) {
                    // 按钮 1: Teleport
                    if (selectedPixel != null && Minecraft.getInstance().player != null) {
                        String cmd = String.format("tp @s %d 150 %d", selectedPixel.x(), selectedPixel.z());
                        Minecraft.getInstance().player.connection.sendCommand(cmd);
                        this.statusMsg = "Teleported.";
                    }
                } else if (mouseY <= menuY + 42) {
                    // 按钮 2: Place Structure
                    if (selectedPixel != null) {
                        int cx = selectedPixel.x() >> 4;
                        int cz = selectedPixel.z() >> 4;
                        StructurePlan.get().addStructure(cx, cz, "minecraft:village/plains/town_centers/plains_meeting_point_1");
                        this.statusMsg = "Planned Village at [" + cx + "," + cz + "]";
                    }
                } else if (mouseY >= menuY + 44) {
                    // 按钮 3: Inspect Region
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

        // 2. 地图交互
        int mapX = 20, mapY = 40;
        int mapW = this.width - 40, mapH = this.height - 60;

        if (scanData != null && mouseX >= mapX && mouseX < mapX + mapW && mouseY >= mapY && mouseY < mapY + mapH) {
            if (button == 1) { // 右键：打开菜单
                int cx = mapX + mapW / 2;
                int cy = mapY + mapH / 2;

                // 这里计算出了 r 和 c
                int r = (int)((mouseX - cx) / scale - offX + scanData.length / 2.0);
                int c = (int)((mouseY - cy) / scale - offY + scanData[0].length / 2.0);

                if (r >= 0 && r < scanData.length && c >= 0 && c < scanData[0].length) {
                    this.selectedPixel = scanData[r][c];
                    if (this.selectedPixel != null) {
                        // 【关键】保存索引，供 getRegionIdAt 使用
                        this.selectedR = r;
                        this.selectedC = c;

                        this.showMenu = true;
                        this.menuX = (int)mouseX;
                        this.menuY = (int)mouseY;
                        return true;
                    }
                }
            }else if (button == 0) {
                showMenu = false; // 左键点击关闭菜单
            }
        } else {
            showMenu = false;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    // --- 渲染逻辑 ---

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        int mapX = 20, mapY = 40;
        int mapW = this.width - 40, mapH = this.height - 60;
        g.fill(mapX, mapY, mapX + mapW, mapY + mapH, 0xFF111111);

        if (scanData != null) {
            drawMap(g, mapX, mapY, mapW, mapH);
        }

        g.drawString(this.font, statusMsg, 20, this.height - 15, 0xFFFFFFFF);
        if (isScanning) g.drawCenteredString(this.font, "SCANNING...", this.width/2, this.height/2, 0xFFFF0000);

        if (!showMenu) drawTooltip(g, mouseX, mouseY, mapX, mapY, mapW, mapH);
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
                    color = 0xFFFFFF00; // Yellow for Plan
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

        // 绘制玩家十字 (在 disableScissor 之前)
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

        // Draw crosshair logic (vertex calls)
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
        int w = 100;
        int h = 68; // 修复：高度 68 以容纳三个按钮
        g.fill(menuX, menuY, menuX + w, menuY + h, 0xFF222222);
        g.renderOutline(menuX, menuY, w, h, 0xFFFFFFFF);

        boolean hoverTp = mouseX >= menuX && mouseX <= menuX + w && mouseY >= menuY && mouseY <= menuY + 20;
        g.fill(menuX + 1, menuY + 1, menuX + w - 1, menuY + 20, hoverTp ? 0xFF444444 : 0xFF333333);
        g.drawCenteredString(this.font, "Teleport", menuX + w/2, menuY + 6, 0xFFFFFF);

        boolean hoverPl = mouseX >= menuX && mouseX <= menuX + w && mouseY >= menuY + 22 && mouseY <= menuY + 44;
        g.fill(menuX + 1, menuY + 22, menuX + w - 1, menuY + 44, hoverPl ? 0xFF444444 : 0xFF333333);
        g.drawCenteredString(this.font, "Place Village", menuX + w/2, menuY + 28, 0xFFFFFF);

        int rId = (selectedPixel != null) ? getRegionIdAt() : 0;

        boolean hoverInsp = mouseX >= menuX && mouseX <= menuX + w && mouseY >= menuY + 44 && mouseY <= menuY + 64;
        // 只有 rId > 0 (属于某个大陆/区域) 时才高亮可用，否则变灰
        int textColor = (rId > 0) ? 0xFFFFFF : 0x888888;

        g.fill(menuX + 1, menuY + 44, menuX + w - 1, menuY + 64, hoverInsp && rId > 0 ? 0xFF444444 : 0xFF333333);
        g.drawCenteredString(this.font, "Inspect Region", menuX + w/2, menuY + 50, textColor);
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
                list.add(Component.literal("Chunk: [" + (p.x() >> 4) + ", " + (p.z() >> 4) + "]"));
                list.add(Component.literal("H: " + p.height() + (p.isLand() ? " (L)" : " (W)")));
                list.add(Component.literal("Biome: " + p.biomeId()));

                if (clusterMap != null && clusterMap[r][c] > 0) {
                    int regionId = clusterMap[r][c];
                    list.add(Component.literal("§aRegion ID: " + regionId));
                    var holder = ScanResultHolder.get();
                    if (holder.lastClusters != null) {
                        for (ScanRegion reg : holder.lastClusters) {
                            if (reg.id == regionId) {
                                list.add(Component.literal("§bAvg H: " + String.format("%.1f", reg.avgHeight)));
                                list.add(Component.literal("§bRough: " + String.format("%.2f", reg.roughness)));
                                break;
                            }
                        }
                    }
                }
                g.renderTooltip(this.font, list, java.util.Optional.empty(), mx, my);
            }
        }
    }

    // 辅助方法：获取当前选中像素对应的 Region ID
    private int getRegionIdAt() {
        // 检查 clusterMap 是否存在，以及索引是否有效
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
}