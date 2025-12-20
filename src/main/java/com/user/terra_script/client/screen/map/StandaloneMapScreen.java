package com.user.terra_script.client.screen.map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
        import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.scan.*;
        import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class StandaloneMapScreen extends Screen {
    // ... 之前的变量保持不变 ...
    private final Screen parent;
    private final WorldCreationContext context;
    private final long seed;
    private ScanPixel[][] scanData = null;
    private int[][] clusterMap = null;
    private Integer playerChunkX = null;
    private Integer playerChunkZ = null;
    private boolean isScanning = false;
    private String statusMsg = "Ready";
    private ClusterAnalyzer.TargetType clusterTarget = ClusterAnalyzer.TargetType.CONTINENT;
    private double scale = 1.0;
    private double offX = 0, offY = 0;
    private EditBox minSizeInput;

    public StandaloneMapScreen(Screen parent, WorldCreationContext context) {
        super(Component.literal("Satellite Map Debugger"));
        this.parent = parent;
        this.context = context;

        // ... 构造函数里的种子获取逻辑保持不变 ...
        if (context != null) this.seed = context.options().seed();
        else if (Minecraft.getInstance().getSingleplayerServer() != null)
            this.seed = Minecraft.getInstance().getSingleplayerServer().getWorldData().worldGenOptions().seed();
        else if (ScanResultHolder.get().seedUsed != 0) this.seed = ScanResultHolder.get().seedUsed;
        else this.seed = 0;

        if (Minecraft.getInstance().player != null) {
            this.playerChunkX = Minecraft.getInstance().player.chunkPosition().x;
            this.playerChunkZ = Minecraft.getInstance().player.chunkPosition().z;
        }

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

        addRenderableWidget(Button.builder(Component.literal("Scan (100x100)"), b -> startScan())
                .bounds(10, btnY, 120, btnH).build());

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(rightX - 50, btnY, 50, btnH).build());

        addRenderableWidget(Button.builder(Component.literal("Cluster"), b -> runClustering())
                .bounds(rightX - 50 - 5 - 60, btnY, 60, btnH).build());

        this.minSizeInput = new EditBox(this.font, rightX - 50 - 5 - 60 - 5 - 40, btnY, 40, btnH, Component.literal("Min Size"));
        this.minSizeInput.setValue("5");
        this.minSizeInput.setTooltip(Tooltip.create(Component.literal("Min Pixel Size")));
        addRenderableWidget(this.minSizeInput);

        // 【修改】Mode Switch 逻辑，支持三种模式循环
        addRenderableWidget(Button.builder(Component.literal("Mode: " + clusterTarget.name), b -> {
            // 循环切换：Continent -> Ocean -> Mountain -> Continent
            if (clusterTarget == ClusterAnalyzer.TargetType.CONTINENT) clusterTarget = ClusterAnalyzer.TargetType.OCEAN;
            else if (clusterTarget == ClusterAnalyzer.TargetType.OCEAN) clusterTarget = ClusterAnalyzer.TargetType.MOUNTAIN;
            else clusterTarget = ClusterAnalyzer.TargetType.CONTINENT;

            b.setMessage(Component.literal("Mode: " + clusterTarget.name));
            if (scanData != null) runClustering();
        }).bounds(rightX - 50 - 5 - 60 - 5 - 40 - 5 - 120, btnY, 120, btnH).build());
    }

    // ... startScan 方法保持不变 ...
    private void startScan() {
        if (context == null) {
            this.statusMsg = "Cannot re-scan inside game (Context null).";
            return;
        }
        this.isScanning = true;
        this.statusMsg = "Scanning (Async)...";
        int radiusChunks = 500;
        int targetRes = 100;
        SatelliteScanner.scanAsync(context, seed, radiusChunks, targetRes)
                .thenAccept(result -> {
                    this.scanData = result;
                    this.clusterMap = null;
                    int worldRadiusBlocks = radiusChunks * 16;
                    int totalWidth = worldRadiusBlocks * 2;
                    int actualStep = Math.max(1, totalWidth / targetRes);
                    var holder = ScanResultHolder.get();
                    holder.lastScanData = result;
                    holder.lastClusterMap = null;
                    holder.seedUsed = seed;
                    holder.scanRadiusChunks = radiusChunks;
                    holder.scanStep = actualStep;
                    this.isScanning = false;
                    this.statusMsg = "Done. Points: " + (result.length * result[0].length);
                }).exceptionally(e -> {
                    this.isScanning = false;
                    this.statusMsg = "Error: " + e.getMessage();
                    e.printStackTrace();
                    return null;
                });
    }

    private void runClustering() {
        if (scanData == null) {
            this.statusMsg = "No data. Scan first.";
            return;
        }
        int minSize = 5;
        try { minSize = Integer.parseInt(minSizeInput.getValue()); } catch (NumberFormatException ignored) {}
        this.statusMsg = "Clustering (" + clusterTarget.name + ")...";
        List<ScanRegion> regions = ClusterAnalyzer.analyze(scanData, clusterTarget, minSize);
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
        for (ScanRegion r : regions) {
            for (ScanPixel p : r.pixels) pixelToId.put(p, r.id);
        }
        for(int r=0; r<rows; r++) {
            for(int c=0; c<cols; c++) {
                if(scanData[r][c] != null && pixelToId.containsKey(scanData[r][c])) {
                    this.clusterMap[r][c] = pixelToId.get(scanData[r][c]);
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mapX = 20, mapY = 40;
        int mapW = this.width - 40, mapH = this.height - 60;

        // 【新增】点击地图传送功能
        if (scanData != null && mouseX >= mapX && mouseX < mapX + mapW && mouseY >= mapY && mouseY < mapY + mapH) {
            // 只有左键(0)且在游戏内时触发
            if (button == 0 && Minecraft.getInstance().player != null) {
                int cx = mapX + mapW / 2;
                int cy = mapY + mapH / 2;
                // 反算网格坐标
                int r = (int)((mouseX - cx) / scale - offX + scanData.length / 2.0);
                int c = (int)((mouseY - cy) / scale - offY + scanData[0].length / 2.0);

                if (r >= 0 && r < scanData.length && c >= 0 && c < scanData[0].length) {
                    ScanPixel p = scanData[r][c];
                    if (p != null) {
                        // 执行传送指令
                        // 注意：需要玩家有作弊权限
                        String command = String.format("tp @s %d 100 %d", p.x(), p.z());
                        Minecraft.getInstance().player.connection.sendCommand(command);

                        // 可选：传送后关闭界面，方便查看
                        // this.onClose();

                        // 或者只显示一条消息
                        this.statusMsg = "Teleported to [" + p.x() + ", " + p.z() + "]";
                        return true; // 消费点击事件
                    }
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        guiGraphics.fill(20, 40, this.width - 20, this.height - 20, 0xFF111111);
        if (scanData != null) drawMap(guiGraphics);
        guiGraphics.drawString(this.font, statusMsg, 20, this.height - 15, 0xFFFFFFFF);
        if (isScanning) guiGraphics.drawCenteredString(this.font, "SCANNING...", this.width/2, this.height/2, 0xFFFF0000);
        drawTooltip(guiGraphics, mouseX, mouseY);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void drawMap(GuiGraphics g) {
        // ... 前置代码同上 ...
        int x = 20, y = 40;
        int w = this.width - 40, h = this.height - 60;
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

                int color = 0xFF000044; // 海洋蓝

                // 聚类高亮逻辑
                if (clusterMap != null && clusterMap[r][c] > 0) {
                    int cid = clusterMap[r][c];

                    if (clusterTarget == ClusterAnalyzer.TargetType.MOUNTAIN) {
                        // 山脉用红色/橙色系
                        // HUE: 0.0 (Red) ~ 0.15 (Orange)
                        color = Color.HSBtoRGB((cid * 0.1f) % 0.15f, 0.9f, 1.0f);
                    } else if (clusterTarget == ClusterAnalyzer.TargetType.OCEAN) {
                        // 海洋用蓝色系
                        color = Color.HSBtoRGB(0.6f + (cid * 0.05f) % 0.1f, 0.8f, 0.9f);
                    } else {
                        // 大陆用杂色
                        color = Color.HSBtoRGB((cid * 0.618f) % 1.0f, 0.8f, 0.9f);
                    }
                } else {
                    // 原始高度色
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

        // ... 绘制玩家位置 (代码同上) ...
        if (playerChunkX != null && playerChunkZ != null) {
            // 这里为了简单，我直接复制上面的渲染逻辑。实际请确保这部分代码存在。
            var holder = ScanResultHolder.get();
            int step = holder.scanStep;
            int radiusBlocks = holder.scanRadiusChunks * 16;
            int pX = playerChunkX * 16;
            int pZ = playerChunkZ * 16;
            double gridR = (double)(pX + radiusBlocks) / step;
            double gridC = (double)(pZ + radiusBlocks) / step;
            double sx = cx + (gridR - rows/2.0 + offX) * pSize;
            double sy = cy + (gridC - cols/2.0 + offY) * pSize;
            float size = 5.0f;
            int red=255, grn=0, blu=0;
            buf.vertex(mat, (float)(sx - size), (float)sy, 0).color(red, grn, blu, 255).endVertex();
            buf.vertex(mat, (float)(sx - size), (float)(sy + 2), 0).color(red, grn, blu, 255).endVertex();
            buf.vertex(mat, (float)(sx + size), (float)(sy + 2), 0).color(red, grn, blu, 255).endVertex();
            buf.vertex(mat, (float)(sx + size), (float)sy, 0).color(red, grn, blu, 255).endVertex();
            buf.vertex(mat, (float)sx, (float)(sy - size), 0).color(red, grn, blu, 255).endVertex();
            buf.vertex(mat, (float)sx, (float)(sy + size), 0).color(255, 0, 0, 255).endVertex();
            buf.vertex(mat, (float)(sx + 2), (float)(sy + size), 0).color(red, grn, blu, 255).endVertex();
            buf.vertex(mat, (float)(sx + 2), (float)(sy - size), 0).color(red, grn, blu, 255).endVertex();
        }

        tess.end();
        g.disableScissor();
    }

    // --- 核心方法实现 (解决报错: drawTooltip) ---
    private void drawTooltip(GuiGraphics g, int mx, int my) {
        if (scanData == null) return;
        int x = 20, y = 40, w = this.width - 40, h = this.height - 60;

        // 检查鼠标是否在地图范围内
        if (mx < x || mx > x+w || my < y || my > y+h) return;

        int cx = x + w/2;
        int cy = y + h/2;

        // 反算鼠标下的网格索引
        int r = (int)((mx - cx)/scale - offX + scanData.length/2.0);
        int c = (int)((my - cy)/scale - offY + scanData[0].length/2.0);

        if (r >= 0 && r < scanData.length && c >= 0 && c < scanData[0].length) {
            ScanPixel p = scanData[r][c];
            if (p != null) {
                List<Component> list = new ArrayList<>();
                list.add(Component.literal("Block: [" + p.x() + ", " + p.z() + "]"));
                // 增加 Chunk 坐标
                list.add(Component.literal("Chunk: [" + (p.x() >> 4) + ", " + (p.z() >> 4) + "]"));
                list.add(Component.literal("Height: " + p.height() + (p.isLand() ? " (Land)" : " (Water)")));
                list.add(Component.literal("Biome: " + p.biomeId()));

                if (clusterMap != null && clusterMap[r][c] > 0) {
                    list.add(Component.literal("Region ID: " + clusterMap[r][c]));
                }
                g.renderTooltip(this.font, list, java.util.Optional.empty(), mx, my);
            }
        }
    }

    // --- 鼠标交互 ---
    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (btn == 0) {
            offX += dx / scale;
            offY += dy / scale;
            return true;
        }
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