package com.user.terra_script.client.screen.map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.client.data.ScanResultHolder.RegionCache;
import com.user.terra_script.client.screen.city.CityOverlayRenderer;
import com.user.terra_script.config.StructurePlan;
import com.user.terra_script.domain.world.scan.MapTransform;
import com.user.terra_script.domain.world.scan.ScanPixel;
import com.user.terra_script.domain.world.scan.ScanRegion;
import com.user.terra_script.domain.world.scan.service.SatelliteScanner;
import com.user.terra_script.util.TerrainFeatureComputer;
import com.user.terra_script.util.ScanDataIO;
import com.user.terra_script.world.city.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

public class RegionEditorScreen extends Screen {
    private static final int BLOCK_SCAN_STEP = 1;

    private final Screen parent;
    private final ScanRegion targetRegion;
    private final int worldMinX, worldMinZ, worldW, worldH;

    private int scanStep;

    // 数据
    private ScanPixel[][] detailData;
    private double[][] slopeData;
    private double[][] roughnessData;
    private double[][] tpiData;

    private enum ViewMode { HEIGHT, SLOPE, ROUGHNESS, TPI, TEMPERATURE, POLITICAL }
    private ViewMode currentMode = ViewMode.HEIGHT;

    private boolean isScanning = true;
    private String statusMsg = "Initializing...";

    private double scale = 1.0;
    private double offX = 0, offY = 0;
    private boolean ignoreOcean = true;

    private Integer playerChunkX = null;
    private Integer playerChunkZ = null;

    private boolean showMenu = false;
    private int menuX = 0, menuY = 0;
    private ScanPixel selectedPixel = null;

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

        // 统一采用方块级扫描
        this.scanStep = BLOCK_SCAN_STEP;

        if (Minecraft.getInstance().player != null) {
            this.playerChunkX = Minecraft.getInstance().player.chunkPosition().x;
            this.playerChunkZ = Minecraft.getInstance().player.chunkPosition().z;
        }

        // --- 缓存加载逻辑 (核心修改) ---
        var holder = ScanResultHolder.get();
        // 标记最后查看的区域
        holder.lastEditedRegionId = region.id;

        // 尝试从 Map 中获取缓存
        RegionCache cache = holder.regionCacheMap.get(region.id);

        if (cache != null && cache.detailData != null && cache.step == BLOCK_SCAN_STEP) {
            this.detailData = cache.detailData;
            this.slopeData = cache.slopeData;
            this.roughnessData = cache.roughnessData;
            this.tpiData = cache.tpiData;

            this.isScanning = false;
            this.statusMsg = "Loaded cached data for Region " + region.id;

            // 重新计算缩放
            if (detailData.length > 0) {
                double gridW = Math.max(1, detailData.length);
                double gridH = Math.max(1, detailData[0].length);
                this.scale = Math.min((double)(this.width - 40) / gridW, (double)(this.height - 60) / gridH);
            }
        } else if (cache != null && cache.detailData != null) {
            // 旧缓存可能来自采样扫描(step>1)，强制重扫为方块级
            this.statusMsg = "Legacy sampled cache detected. Rescanning region in block-level...";
        }
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(this.width - 60, 10, 50, 20).build());

        addRenderableWidget(Button.builder(Component.literal("View: " + currentMode), b -> {
            switchViewMode(b);
        }).bounds(10, 10, 120, 20).build());

        if (detailData == null) {
            startLocalScan();
        }

        // 【新增】打开界面时触发一次领土重算，确保视觉同步
        if (this.detailData != null) {
            com.user.terra_script.world.TerritoryManager.refresh();
        }
    }

    private void startLocalScan() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            this.statusMsg = "Error: Not in Singleplayer.";
            return;
        }

        this.statusMsg = "Scanning Region " + targetRegion.id + " (Block-level, step=1)...";

        SatelliteScanner.scanRegionAsync(server.overworld(), worldMinX, worldMinZ, worldW, worldH, scanStep)
                .thenAccept(result -> {
                    this.detailData = result;
                    this.isScanning = false;
                    this.statusMsg = "Scan Done.";

                    double gridW = Math.max(1, result.length);
                    double gridH = Math.max(1, result[0].length);
                    this.scale = Math.min(
                            (double)(this.width - 40) / gridW,
                            (double)(this.height - 60) / gridH
                    );

                    // 缓存数据
                    cacheRegionData();

                }).exceptionally(e -> {
                    this.statusMsg = "Error: " + e.getMessage();
                    e.printStackTrace();
                    return null;
                });
    }

    private void cacheRegionData() {
        var holder = ScanResultHolder.get();

        // 创建新的缓存对象
        RegionCache cache = new RegionCache(
                this.targetRegion,
                this.worldMinX, this.worldMinZ,
                this.worldW, this.worldH,
                this.scanStep
        );

        cache.detailData = this.detailData;
        cache.slopeData = this.slopeData;
        cache.roughnessData = this.roughnessData;
        cache.tpiData = this.tpiData;

        // 存入 Map
        holder.regionCacheMap.put(this.targetRegion.id, cache);

        // 触发持久化
        ScanDataIO.saveAll();
    }


    private void switchViewMode(Button b) {
        int next = (currentMode.ordinal() + 1) % ViewMode.values().length;
        currentMode = ViewMode.values()[next];
        b.setMessage(Component.literal("View: " + currentMode));

        if (detailData == null) return;

        // 如果切到政治模式，刷新数据
        if (currentMode == ViewMode.POLITICAL) {
            com.user.terra_script.world.TerritoryManager.refresh();
        }
        // 懒加载计算
        else if (currentMode == ViewMode.SLOPE && slopeData == null) {
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
                    cacheRegionData();
                });
    }

    private void calculateRoughnessAsync() {
        if (detailData == null) return;
        this.statusMsg = "Calculating Roughness...";
        CompletableFuture.supplyAsync(() -> TerrainFeatureComputer.computeRoughness(detailData, 2, ignoreOcean))
                .thenAccept(res -> {
                    this.roughnessData = res;
                    this.statusMsg = "Roughness Ready.";
                    cacheRegionData();
                });
    }

    private void calculateTPIAsync() {
        if (detailData == null) return;
        this.statusMsg = "Calculating TPI (R=5)...";
        CompletableFuture.supplyAsync(() -> TerrainFeatureComputer.computeTPI(detailData, 5, true))
                .thenAccept(res -> {
                    this.tpiData = res;
                    this.statusMsg = "TPI Ready.";
                    cacheRegionData();
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
        g.drawString(this.font, statusMsg, 20, this.height - 15, 0xFFFFFFFF);
        if (showMenu) renderContextMenu(g, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawMap(GuiGraphics g, int x, int y, int w, int h) {
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

        // 【优化 1】动态 LOD
        // scale < 2.0 -> step = 2 (减少 75% 顶点)
        // scale < 1.0 -> step = 4 (减少 94% 顶点)
        int renderStep = 1;
        double viewScale = Math.abs(scale);
        if (viewScale < 0.8) renderStep = 4;
        else if (viewScale < 1.5) renderStep = 2;

        // 绘制的像素大小要相应放大，填补跳过的空隙
        double pSize = viewScale * renderStep;

        double rMinRaw = (x - cx) / viewScale + rows / 2.0 - offX;
        double rMaxRaw = (x + w - cx) / viewScale + rows / 2.0 - offX;

        double cMinRaw = (y - cy) / viewScale + cols / 2.0 - offY;
        double cMaxRaw = (y + h - cy) / viewScale + cols / 2.0 - offY;

        // 向下/向上取整，并留一点余量防止边缘裁剪
        int startR = (int) Math.floor(rMinRaw) - renderStep;
        int endR = (int) Math.ceil(rMaxRaw) + renderStep;
        int startC = (int) Math.floor(cMinRaw) - renderStep;
        int endC = (int) Math.ceil(cMaxRaw) + renderStep;

        // 钳制到数组有效范围
        startR = Math.max(0, startR);
        endR = Math.min(rows, endR);
        startC = Math.max(0, startC);
        endC = Math.min(cols, endC);

        // 对齐 step
        // 确保 startR 是 renderStep 的整数倍，防止滚动时网格抖动
        startR = (startR / renderStep) * renderStep;
        startC = (startC / renderStep) * renderStep;

        // 安全检查：如果范围无效，就不画
        if (startR >= endR || startC >= endC) {
            // 调试用：如果在屏幕内却没画，打印一下
            System.out.println("Culling logic hidden everything!");
            tess.end();
            g.disableScissor();
            return;
        }


        for (int r = startR; r < endR; r += renderStep) {
            for (int c = startC; c < endC; c += renderStep) {
                ScanPixel p = detailData[r][c];
                if (p == null) continue;

                double sx = cx + (r - rows/2.0 + offX) * viewScale;
                double sy = cy + (c - cols/2.0 + offY) * viewScale;

                // 二次检查 (虽然循环范围限制了，但为了稳妥)
                if (sx < x - pSize || sx > x + w || sy < y - pSize || sy > y + h) continue;
                if (sx < x - viewScale || sx > x + w || sy < y - viewScale || sy > y + h) continue;

                int color = 0xFF000000;

                // 领土渲染逻辑
                boolean isClaimed = false;

                // 政治视图逻辑
                if (currentMode == ViewMode.POLITICAL) {
                    long chunkKey = net.minecraft.world.level.ChunkPos.asLong(p.x() >> 4, p.z() >> 4);
                    for (var result : com.user.terra_script.world.TerritoryManager.getAllResults()) {
                        if (result.claimedChunks.contains(chunkKey)) {
                            color = result.config.color | 0xFF000000;
                            isClaimed = true; break;
                        } else if (result.wildChunks.contains(chunkKey)) {
                            int tColor = result.config.color;
                            int rC = (tColor >> 16) & 0xFF; int gC = (tColor >> 8) & 0xFF; int bC = tColor & 0xFF;
                            color = 0xFF000000 | ((rC/2) << 16) | ((gC/2) << 8) | (bC/2);
                            isClaimed = true; break;
                        }
                    }
                }

                // 建筑计划高亮 (所有模式都显示)
                if (StructurePlan.get().getStructureAt(p.x() >> 4, p.z() >> 4) != null) {
                    color = 0xFFFFFF00;
                    isClaimed = true;
                }

                if (!isClaimed) {
                    if (currentMode == ViewMode.HEIGHT || currentMode == ViewMode.POLITICAL) { // 政治模式下未占领区域显示地形
                        if (p.isLand()) {
                            int val = Math.min(255, (p.height() - 63) * 3 + 50);
                            color = (0xFF000000 | (val << 8) | (val / 2));
                        } else { color = 0xFF000080; }
                    } else if (currentMode == ViewMode.SLOPE) {
                        if (slopeData != null) {
                            double val = slopeData[r][c];
                            float factor = (float)Math.min(1.0, val / 3.0);
                            color = Color.HSBtoRGB(0.333f * (1-factor), 1.0f, 0.8f + (factor*0.2f));
                        } else { color = 0xFF444444; }
                    } else if (currentMode == ViewMode.ROUGHNESS) {
                        if (roughnessData != null) {
                            double val = roughnessData[r][c];
                            int v = (int)(Math.min(1.0, val / 5.0) * 255);
                            color = (0xFF000000 | (v << 16) | (v << 8) | v);
                        } else { color = 0xFF444444; }
                    } else if (currentMode == ViewMode.TPI) {
                        if (tpiData != null) {
                            double val = tpiData[r][c];
                            int base = 100;
                            int intensity = (int)(Math.min(1.0, Math.abs(val) / 3.0) * 155);
                            if (val > 0) color = (0xFF000000 | ((base+intensity) << 16) | (base << 8) | base);
                            else color = (0xFF000000 | (base << 16) | (base << 8) | (base+intensity));
                        } else { color = 0xFF444444; }
                    } else if (currentMode == ViewMode.TEMPERATURE) {
                        if (p.isLand()) {
                            float temp = p.temperature();
                            float norm = (temp + 0.5f) / 2.5f;
                            norm = Math.max(0, Math.min(1, norm));
                            color = Color.HSBtoRGB(0.7f * (1.0f - norm), 0.8f, 0.9f);
                        } else { color = 0xFF000044; }
                    }
                }

                // 绘制 Quad
                int red = (color >> 16) & 0xFF;
                int grn = (color >> 8) & 0xFF;
                int blu = (color) & 0xFF;
                int alpha = 255;

                buf.vertex(mat, (float)sx, (float)sy, 0).color(red, grn, blu, alpha).endVertex();
                buf.vertex(mat, (float)sx, (float)(sy+pSize), 0).color(red, grn, blu, alpha).endVertex();
                buf.vertex(mat, (float)(sx+pSize), (float)(sy+pSize), 0).color(red, grn, blu, alpha).endVertex();
                buf.vertex(mat, (float)(sx+pSize), (float)sy, 0).color(red, grn, blu, alpha).endVertex();
            }
        }

        tess.end();

        if (currentMode == ViewMode.POLITICAL) {
            renderCities(g, x, y, w, h);
        }

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        drawPlayerCrosshair(buf, mat, x, y, w, h);
        tess.end();

        g.disableScissor();
    }

    private void drawPlayerCrosshair(BufferBuilder buf, Matrix4f mat, int mapX, int mapY, int mapW, int mapH) {
        if (playerChunkX == null || playerChunkZ == null || detailData == null) return;
        int pX = playerChunkX * 16;
        int pZ = playerChunkZ * 16;
        if (pX < worldMinX || pX > worldMinX + worldW || pZ < worldMinZ || pZ > worldMinZ + worldH) return;

        double gridR = (double)(pX - worldMinX) / scanStep;
        double gridC = (double)(pZ - worldMinZ) / scanStep;
        int rows = detailData.length;
        int cols = detailData[0].length;
        int cx = mapX + mapW / 2;
        int cy = mapY + mapH / 2;
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
                list.add(Component.literal("Temp: " + String.format("%.2f", p.temperature())));

                if (currentMode == ViewMode.SLOPE) {
                    if (slopeData != null) list.add(Component.literal("Slope: " + String.format("%.2f", slopeData[r][c])));
                } else if (currentMode == ViewMode.ROUGHNESS) {
                    if (roughnessData != null) list.add(Component.literal("Roughness: " + String.format("%.2f", roughnessData[r][c])));
                } else if (currentMode == ViewMode.TPI) {
                    if (tpiData != null) {
                        double val = tpiData[r][c];
                        String type = "Flat"; if (val > 2.0) type = "Ridge"; else if (val < -2.0) type = "Valley";
                        list.add(Component.literal("TPI: " + String.format("%.2f", val) + " (" + type + ")"));
                    }
                } else {
                    if (slopeData != null) list.add(Component.literal("Slope: " + String.format("%.2f", slopeData[r][c])));
                    if (tpiData != null) list.add(Component.literal("TPI: " + String.format("%.2f", tpiData[r][c])));
                }

                int chunkX = p.x() >> 4;
                int chunkZ = p.z() >> 4;
                String plan = StructurePlan.get().getStructureAt(chunkX, chunkZ);
                if (plan != null) list.add(Component.literal("§ePlanned: " + plan));
                g.renderTooltip(this.font, list, java.util.Optional.empty(), mx, my);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (showMenu) {
            int w = 100, h = 68;
            if (mouseX >= menuX && mouseX <= menuX + w && mouseY >= menuY && mouseY <= menuY + h) {
                if (mouseY <= menuY + 20) {
                    if (selectedPixel != null && Minecraft.getInstance().player != null) {
                        String cmd = String.format("tp @s %d 150 %d", selectedPixel.x(), selectedPixel.z());
                        Minecraft.getInstance().player.connection.sendCommand(cmd);
                        this.statusMsg = "Teleported.";
                    }
                } else if (mouseY <= menuY + 42) {
                    if (selectedPixel != null) {
                        int cx = selectedPixel.x() >> 4;
                        int cz = selectedPixel.z() >> 4;
                        StructurePlan.get().addStructure(cx, cz, "minecraft:village/plains/town_centers/plains_meeting_point_1");
                        this.statusMsg = "Planned Village at [" + cx + "," + cz + "]";
                    }
                }
                showMenu = false;
                return true;
            } else {
                showMenu = false; return true;
            }
        }

        int mapX = 20, mapY = 40;
        int mapW = this.width - 40, mapH = this.height - 60;
        if (detailData != null && mouseX >= mapX && mouseX < mapX + mapW && mouseY >= mapY && mouseY < mapY + mapH) {
            if (button == 1) {
                int cx = mapX + mapW / 2;
                int cy = mapY + mapH / 2;
                int r = (int)((mouseX - cx) / scale - offX + detailData.length / 2.0);
                int c = (int)((mouseY - cy) / scale - offY + detailData[0].length / 2.0);
                if (r >= 0 && r < detailData.length && c >= 0 && c < detailData[0].length) {
                    this.selectedPixel = detailData[r][c];
                    if (this.selectedPixel != null) {
                        this.showMenu = true;
                        this.menuX = (int)mouseX; this.menuY = (int)mouseY;
                        return true;
                    }
                }
            } else if (button == 0) { showMenu = false; }
        } else { showMenu = false; }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void renderCities(GuiGraphics g, int mapX, int mapY, int mapW, int mapH) {
        // 1. 获取所有城市
        var cities = CityManager.get().getAllCities();
        if (cities.isEmpty()) return;

        // 2. 准备坐标参数
        int cx = mapX + mapW / 2;
        int cy = mapY + mapH / 2;
        int rows = detailData.length;
        int cols = detailData[0].length;
        int step = this.scanStep; // 当前编辑器的扫描步长

        // 3. 构建适配器 (Proxy)
        MapTransform transform = new MapTransform(0, 0, 1.0f) {
            @Override
            public int worldToScreenX(int worldX) {
                // World -> Grid
                double gridR = (double)(worldX - worldMinX) / step;
                // Grid -> Screen
                return (int)(cx + (gridR - rows/2.0 + offX) * scale);
            }

            @Override
            public int worldToScreenZ(int worldZ) {
                double gridC = (double)(worldZ - worldMinZ) / step;
                return (int)(cy + (gridC - cols/2.0 + offY) * scale);
            }

            // Chunk 覆盖大小 (屏幕像素)
            @Override
            public int chunkPixelSize() {
                // 1 Chunk = 16 blocks
                // grid step = step blocks
                // grid unit size = scale
                // -> 16 blocks = (16 / step) * scale
                return (int) Math.max(1, (16.0 / step) * scale);
            }

            // 覆盖 blockToScreen
            @Override
            public int blockToScreenX(int blockX) { return worldToScreenX(blockX); }
            @Override
            public int blockToScreenZ(int blockZ) { return worldToScreenZ(blockZ); }
        };

        // 4. 遍历渲染
        for (CityInstance city : cities) {
            // 只渲染当前大陆内的城市 (简单判断中心点是否在范围内)
            if (city.config.centerX < worldMinX || city.config.centerX > worldMinX + worldW) continue;

            // 转换数据结构 CityInstance -> CityLayout
            CityLayout layout = new CityLayout();
            layout.cityInstanceId = city.id;
            layout.centerChunkX = city.config.centerX >> 4;
            layout.centerChunkZ = city.config.centerZ >> 4;

            layout.chunks = new ArrayList<>();
            city.claimedChunks.forEach((key, type) -> {
                CityLayout.CityChunk cc = new CityLayout.CityChunk();
                cc.x = net.minecraft.world.level.ChunkPos.getX(key);
                cc.z = net.minecraft.world.level.ChunkPos.getZ(key);
                cc.zoneType = type.layerType;
                cc.layerIndex = type.layerIndex;
                layout.chunks.add(cc);
            });

            // 填充多边形数据 (如果有)
            if (city.districts != null) {
                layout.districts = new ArrayList<>();
                for (var d : city.districts) {
                    CityLayout.DistrictRenderData dr = new CityLayout.DistrictRenderData();
                    dr.centerX = d.centerX;
                    dr.centerZ = d.centerZ;
                    dr.type = d.zoneType;
                    dr.layerIndex = d.layerIndex;
                    dr.density = d.density;
                    // dr.polygon = ...;
                    layout.districts.add(dr);
                }
            }

            // 5. 调用渲染器
            // 渲染领地 (Chunks)
            CityOverlayRenderer.render(g, layout, transform);

            // 渲染多边形 (如果有)
            CityOverlayRenderer.renderDistricts(g, layout, transform);
        }
    }

    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) { if (btn == 0) { offX += dx / scale; offY += dy / scale; return true; } return super.mouseDragged(mx, my, btn, dx, dy); }
    @Override public boolean mouseScrolled(double mx, double my, double delta) { if (delta > 0) scale *= 1.1; else scale /= 1.1; return true; }
    @Override public void onClose() { this.minecraft.setScreen(parent); }
}
