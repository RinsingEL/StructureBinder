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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class RegionEditorScreen extends Screen {
    private final Screen parent;
    private final ScanRegion targetRegion; // 传入的目标区域
    private final int worldMinX, worldMinZ, worldW, worldH; // 扫描的世界坐标范围

    private int scanStep; // 实际扫描步长

    // 数据
    private ScanPixel[][] detailData;
    private double[][] slopeData;
    private double[][] roughnessData;
    private double[][] tpiData;

    // 视图状态
    private enum ViewMode { HEIGHT, SLOPE, ROUGHNESS, TPI }
    private ViewMode currentMode = ViewMode.HEIGHT;

    // GUI 状态
    private boolean isScanning = true;
    private String statusMsg = "Initializing...";

    // 交互状态
    private double scale = 1.0;
    private double offX = 0, offY = 0;
    private  boolean ignoreOcean = true;

    // 玩家位置 (Chunk 坐标)
    private Integer playerChunkX = null;
    private Integer playerChunkZ = null;

    // 右键菜单状态
    private boolean showMenu = false;
    private int menuX = 0, menuY = 0;
    private ScanPixel selectedPixel = null; // 当前被右键选中的像素

    public RegionEditorScreen(Screen parent, ScanRegion region) {
        super(Component.literal("Region Editor"));
        this.parent = parent;
        this.targetRegion = region;

        int padding = 128;
        this.worldMinX = region.minX - padding;
        this.worldMinZ = region.minZ - padding;
        int rw = region.maxX - region.minX + padding * 2;
        int rh = region.maxZ - region.minZ + padding * 2;
        int size = Math.max(rw, rh);
        this.worldW = size;
        this.worldH = size;

        // 默认步长
        int maxResolution = 512;
        this.scanStep = Math.max(1, size / maxResolution);

        if (Minecraft.getInstance().player != null) {
            this.playerChunkX = Minecraft.getInstance().player.chunkPosition().x;
            this.playerChunkZ = Minecraft.getInstance().player.chunkPosition().z;
        }

        // --- 缓存加载逻辑 (修复 NPE 隐患) ---
        var holder = ScanResultHolder.get();
        if (holder.lastEditedRegion != null && holder.lastEditedRegion.id == region.id && holder.lastRegionDetailData != null) {
            this.detailData = holder.lastRegionDetailData;
            // 只有当数组不为空时才赋值
            this.slopeData = holder.lastRegionSlopeData;
            this.roughnessData = holder.lastRegionRoughnessData;
            this.tpiData = holder.lastRegionTpiData;

            // 使用缓存的步长
            this.scanStep = holder.lastRegionStep > 0 ? holder.lastRegionStep : this.scanStep;

            this.isScanning = false;
            this.statusMsg = "Loaded cached data.";

            // 重新计算缩放
            if (detailData.length > 0) {
                double gridW = Math.max(1, detailData.length);
                double gridH = Math.max(1, detailData[0].length);
                this.scale = Math.min((double)(this.width - 40) / gridW, (double)(this.height - 60) / gridH);
            }
        }
    }

    @Override
    protected void init() {
        // 返回按钮
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(this.width - 60, 10, 50, 20).build());

        // 视图切换按钮
        addRenderableWidget(Button.builder(Component.literal("View: " + currentMode), b -> {
            switchViewMode(b);
        }).bounds(10, 10, 120, 20).build());

        // 如果还没数据，自动开始扫描 (如果没从缓存加载的话)
        if (detailData == null) {
            startLocalScan();
        }
    }

    private void startLocalScan() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            this.statusMsg = "Error: Not in Singleplayer.";
            return;
        }

        this.statusMsg = "Scanning Area " + worldW + "x" + worldH + " (Step: " + scanStep + ")...";

        SatelliteScanner.scanRegionAsync(server.overworld(), worldMinX, worldMinZ, worldW, worldH, scanStep)
                .thenAccept(result -> {
                    this.detailData = result;
                    this.isScanning = false;
                    this.statusMsg = "Scan Done. Grid: " + result.length + "x" + result[0].length;

                    // 自动适配缩放
                    double gridW = Math.max(1, result.length);
                    double gridH = Math.max(1, result[0].length);
                    this.scale = Math.min(
                            (double)(this.width - 40) / gridW,
                            (double)(this.height - 60) / gridH
                    );
                    // 【新增】缓存局部数据
                    cacheRegionData();

                }).exceptionally(e -> {
                    this.statusMsg = "Error: " + e.getMessage();
                    e.printStackTrace();
                    return null;
                });
    }

    private void cacheRegionData() {
        var holder = ScanResultHolder.get();
        holder.lastEditedRegion = this.targetRegion;
        holder.lastRegionDetailData = this.detailData;
        holder.lastRegionSlopeData = this.slopeData; // 存当前已计算的
        holder.lastRegionRoughnessData = this.roughnessData; // 存当前已计算的
        holder.lastRegionTpiData = this.tpiData;
        holder.lastRegionMinX = this.worldMinX;
        holder.lastRegionMinZ = this.worldMinZ;
        holder.lastRegionW = this.worldW;
        holder.lastRegionH = this.worldH;
        holder.lastRegionStep = this.scanStep;

        ScanDataIO.saveAll();
    }


    private void switchViewMode(Button b) {
        int next = (currentMode.ordinal() + 1) % ViewMode.values().length;
        currentMode = ViewMode.values()[next];
        b.setMessage(Component.literal("View: " + currentMode));

        // 懒加载计算特征
        if (detailData == null) return; // 没数据，不能算

        if (currentMode == ViewMode.SLOPE && slopeData == null) {
            calculateSlopeAsync();
        } else if (currentMode == ViewMode.ROUGHNESS && roughnessData == null) {
            calculateRoughnessAsync();
        } else if (currentMode == ViewMode.TPI && tpiData == null) {
            calculateTPIAsync();
        }
    }

    private void calculateSlopeAsync() {
        if (detailData == null) return;
        this.statusMsg = "Calculating Slope...";
        CompletableFuture.supplyAsync(() -> TerrainFeatureComputer.computeSlope(detailData, ignoreOcean))
                .thenAccept(res -> {
                    this.slopeData = res;
                    this.statusMsg = "Slope Ready.";
                    cacheRegionData(); // 计算完也缓存
                }).exceptionally(e -> {
                    this.statusMsg = "Slope Error: " + e.getMessage();
                    return null;
                });
    }

    private void calculateRoughnessAsync() {
        if (detailData == null) return;
        this.statusMsg = "Calculating Roughness...";
        CompletableFuture.supplyAsync(() -> TerrainFeatureComputer.computeRoughness(detailData, 2, ignoreOcean))
                .thenAccept(res -> {
                    this.roughnessData = res;
                    this.statusMsg = "Roughness Ready.";
                    cacheRegionData(); // 计算完也缓存
                }).exceptionally(e -> {
                    this.statusMsg = "Roughness Error: " + e.getMessage();
                    return null;
                });
    }

    private void calculateTPIAsync() {
        if (detailData == null) return;
        this.statusMsg = "Calculating TPI (R=5)..."; // 半径设为 5，既能看清山脊也不至于太慢
        CompletableFuture.supplyAsync(() -> TerrainFeatureComputer.computeTPI(detailData, 5, true)) // true=忽略海洋
                .thenAccept(res -> {
                    this.tpiData = res;
                    this.statusMsg = "TPI Ready.";
                    cacheRegionData();
                }).exceptionally(e -> {
                    e.printStackTrace();
                    return null;
                });
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);

        int x = 20, y = 40;
        int w = this.width - 40, h = this.height - 60;
        g.fill(x, y, x + w, y + h, 0xFF000000);

        if (detailData != null) {
            drawMap(g, x, y, w, h);
            drawTooltip(g, mouseX, mouseY, x, y, w, h);
        } else {
            g.drawCenteredString(this.font, statusMsg, this.width/2, this.height/2, 0xFF00FF00);
        }

        // 底部状态栏
        g.drawString(this.font, statusMsg, 20, this.height - 15, 0xFFFFFFFF);

        // 绘制右键菜单
        if (showMenu) {
            renderContextMenu(g, mouseX, mouseY);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawMap(GuiGraphics g, int x, int y, int w, int h) {
        if (detailData == null) return; // 防御

        g.enableScissor(x, y, x + w, y + h);
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f mat = g.pose().last().pose();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        int rows = detailData.length;
        int cols = detailData[0].length;
        int cx = x + w/2;
        int cy = y + h/2;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                ScanPixel p = detailData[r][c];
                if (p == null) continue;

                double sx = cx + (r - rows/2.0 + offX) * scale;
                double sy = cy + (c - cols/2.0 + offY) * scale;
                if (sx < x - scale || sx > x + w || sy < y - scale || sy > y + h) continue;

                int red=0, grn=0, blu=0;

                // --- 严格判空 ---
                if (currentMode == ViewMode.HEIGHT) {
                    if (p.isLand()) {
                        int val = Math.min(255, (p.height() - 63) * 3 + 50);
                        grn = val;
                    } else { blu = 180; }
                }
                else if (currentMode == ViewMode.SLOPE) {
                    // 修复：如果 slopeData 为 null (还没算完)，显示默认颜色
                    if (slopeData != null && r < slopeData.length && c < slopeData[0].length) {
                        double slope = slopeData[r][c];
                        float factor = (float)Math.min(1.0, slope / 3.0);
                        red = (int)(factor * 255);
                        grn = (int)((1 - factor) * 255);
                    } else {
                        // 数据未就绪：显示深灰色
                        red = 50; grn = 50; blu = 50;
                    }
                }
                else if (currentMode == ViewMode.ROUGHNESS) {
                    // 修复：如果 roughnessData 为 null，显示默认颜色
                    if (roughnessData != null && r < roughnessData.length && c < roughnessData[0].length) {
                        double rough = roughnessData[r][c];
                        int val = (int)(Math.min(1.0, rough / 5.0) * 255);
                        red = val; grn = val; blu = val;
                    } else {

                        red = 50; grn = 50; blu = 50;
                    }
                }  else if (currentMode == ViewMode.TPI) {
                    if (tpiData != null) {
                        double tpi = tpiData[r][c];
                        // TPI 范围通常在 -10 到 10 之间 (取决于地形剧烈程度)
                        // 我们做一个简单的归一化映射
                        // > 0 (山脊): 灰色 -> 红色
                        // < 0 (山谷): 灰色 -> 蓝色
                        // 0 : 灰色 (128, 128, 128)

                        int base = 128;
                        int intensity = (int)(Math.abs(tpi) * 20); // 放大系数，可调
                        intensity = Math.min(127, intensity);

                        if (tpi > 0.5) { // 山脊/凸起
                            red = base + intensity;
                            grn = base - (intensity / 2);
                            blu = base - (intensity / 2);
                        } else if (tpi < -0.5) { // 山谷/凹陷
                            red = base - (intensity / 2);
                            grn = base - (intensity / 2);
                            blu = base + intensity;
                        } else { // 平坦
                            red = base; grn = base; blu = base;
                        }
                    } else {
                        red = 40; grn = 40; blu = 40; // Loading
                    }
                }

                buf.vertex(mat, (float)sx, (float)sy, 0).color(red, grn, blu, 255).endVertex();
                buf.vertex(mat, (float)sx, (float)(sy+scale), 0).color(red, grn, blu, 255).endVertex();
                buf.vertex(mat, (float)(sx+scale), (float)(sy+scale), 0).color(red, grn, blu, 255).endVertex();
                buf.vertex(mat, (float)(sx+scale), (float)sy, 0).color(red, grn, blu, 255).endVertex();
            }
        }

        drawPlayerCrosshair(buf, mat, x, y, w, h);
        tess.end();
        g.disableScissor();
    }

    // 【新增】玩家十字绘制方法 (适配局部地图)
    private void drawPlayerCrosshair(BufferBuilder buf, Matrix4f mat, int mapX, int mapY, int mapW, int mapH) {
        if (playerChunkX == null || playerChunkZ == null || detailData == null) return;

        // 计算玩家的方块坐标
        int pX = playerChunkX * 16;
        int pZ = playerChunkZ * 16;

        // 检查玩家是否在当前局部扫描区域内
        if (pX < worldMinX || pX > worldMinX + worldW || pZ < worldMinZ || pZ > worldMinZ + worldH) {
            return; // 玩家不在这个区域，不显示
        }

        // 将玩家的世界坐标映射到当前 detailData 数组的索引
        double gridR = (double)(pX - worldMinX) / scanStep;
        double gridC = (double)(pZ - worldMinZ) / scanStep;

        // 将网格索引映射到屏幕坐标
        int rows = detailData.length;
        int cols = detailData[0].length;
        int cx = mapX + mapW / 2;
        int cy = mapY + mapH / 2;

        double sx = cx + (gridR - rows/2.0 + offX) * scale;
        double sy = cy + (gridC - cols/2.0 + offY) * scale;

        float size = 4.0f;
        int r=255, g=50, b=50, a=255;

        // 绘制十字
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
        int w = 100, h = 68;
        g.fill(menuX, menuY, menuX + w, menuY + h, 0xFF222222);
        g.renderOutline(menuX, menuY, w, h, 0xFFFFFFFF);

        boolean hoverTp = mouseX >= menuX && mouseX <= menuX + w && mouseY >= menuY && mouseY <= menuY + 20;
        g.fill(menuX + 1, menuY + 1, menuX + w - 1, menuY + 20, hoverTp ? 0xFF444444 : 0xFF333333);
        g.drawCenteredString(this.font, "Teleport", menuX + w/2, menuY + 6, 0xFFFFFF);

        boolean hoverPl = mouseX >= menuX && mouseX <= menuX + w && mouseY >= menuY + 22 && mouseY <= menuY + 44;
        g.fill(menuX + 1, menuY + 22, menuX + w - 1, menuY + 44, hoverPl ? 0xFF444444 : 0xFF333333);
        g.drawCenteredString(this.font, "Place Village", menuX + w/2, menuY + 28, 0xFFFFFF);
    }

    // 【新增】Tooltip 绘制，现在能显示更精确的特征
    private void drawTooltip(GuiGraphics g, int mx, int my, int x, int y, int w, int h) {
        if (detailData == null) return;
        if (mx < x || mx > x + w || my < y || my > y + h) return;

        int cx = x + w / 2;
        int cy = y + h / 2;
        int r = (int) ((mx - cx) / scale - offX + detailData.length / 2.0);
        int c = (int) ((my - cy) / scale - offY + detailData[0].length / 2.0);

        if (r >= 0 && r < detailData.length && c >= 0 && c < detailData[0].length) {
            ScanPixel p = detailData[r][c];
            if (p != null) {
                java.util.List<Component> list = new java.util.ArrayList<>();
                list.add(Component.literal("Pos: " + p.x() + ", " + p.z()));
                list.add(Component.literal("Height: " + p.height()));

                // --- 修复部分 ---
                // 1. 斜率视图：只检查斜率数据
                if (currentMode == ViewMode.SLOPE) {
                    if (slopeData != null)
                        list.add(Component.literal("Slope: " + String.format("%.2f", slopeData[r][c])));
                    else
                        list.add(Component.literal("Slope: Calculating..."));
                }
                // 2. 崎岖度视图：只检查崎岖度数据
                else if (currentMode == ViewMode.ROUGHNESS) {
                    if (roughnessData != null)
                        list.add(Component.literal("Roughness: " + String.format("%.2f", roughnessData[r][c])));
                    else
                        list.add(Component.literal("Roughness: Calculating..."));
                }
                // 3. TPI 视图
                else if (currentMode == ViewMode.TPI) {
                    if (tpiData != null) {
                        double val = tpiData[r][c];
                        String type = "Flat";
                        if (val > 2.0) type = "Ridge";
                        else if (val < -2.0) type = "Valley";
                        list.add(Component.literal("TPI: " + String.format("%.2f", val) + " (" + type + ")"));
                    } else {
                        list.add(Component.literal("TPI: Calculating..."));
                    }
                }
                // 4. 默认视图 (Height)：尝试显示所有已有的数据
                else {
                    if (slopeData != null)
                        list.add(Component.literal("Slope: " + String.format("%.2f", slopeData[r][c])));

                    // 【之前这里报错】：增加了 roughnessData != null 的检查
                    if (roughnessData != null)
                        list.add(Component.literal("Roughness: " + String.format("%.2f", roughnessData[r][c])));

                    if (tpiData != null)
                        list.add(Component.literal("TPI: " + String.format("%.2f", tpiData[r][c])));
                }

                // 检查计划
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
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 1. 菜单交互
        if (showMenu) {
            int w = 100;
            int h = 68;
            if (mouseX >= menuX && mouseX <= menuX + w && mouseY >= menuY && mouseY <= menuY + h) {
                if (mouseY <= menuY + 20) {
                    // Teleport
                    if (selectedPixel != null && Minecraft.getInstance().player != null) {
                        String cmd = String.format("tp @s %d 150 %d", selectedPixel.x(), selectedPixel.z());
                        Minecraft.getInstance().player.connection.sendCommand(cmd);
                        this.statusMsg = "Teleported.";
                    }
                } else if (mouseY <= menuY + 42) {
                    // Place Structure
                    if (selectedPixel != null) {
                        int cx = selectedPixel.x() >> 4;
                        int cz = selectedPixel.z() >> 4;
                        StructurePlan.get().addStructure(cx, cz, "minecraft:village/plains/town_centers/plains_meeting_point_1");
                        this.statusMsg = "Planned Village at [" + cx + "," + cz + "]";
                    }
                } else if (mouseY >= menuY + 44) {
                    // Inspect Region Clicked - 这里已经是 RegionEditor，所以这个按钮的功能可以改成别的，例如“平整区域”
                    this.statusMsg = "Inspect not available here."; // 或者实现平整功能
                }
                showMenu = false;
                return true;
            } else {
                showMenu = false;
                return true;
            }
        }

        // 2. 地图交互 (点击地图打开菜单)
        int mapX = 20, mapY = 40;
        int mapW = this.width - 40, mapH = this.height - 60;
        if (detailData != null && mouseX >= mapX && mouseX < mapX + mapW && mouseY >= mapY && mouseY < mapY + mapH) {
            if (button == 1) { // 右键：打开菜单
                int cx = mapX + mapW / 2;
                int cy = mapY + mapH / 2;
                int r = (int)((mouseX - cx) / scale - offX + detailData.length / 2.0);
                int c = (int)((mouseY - cy) / scale - offY + detailData[0].length / 2.0);

                if (r >= 0 && r < detailData.length && c >= 0 && c < detailData[0].length) {
                    this.selectedPixel = detailData[r][c];
                    if (this.selectedPixel != null) {
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
    public void onClose() { this.minecraft.setScreen(parent); }
}