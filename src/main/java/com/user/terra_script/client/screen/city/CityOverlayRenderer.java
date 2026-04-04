package com.user.terra_script.client.screen.city;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.user.terra_script.domain.world.scan.MapTransform;
import net.minecraft.client.gui.GuiGraphics;

import com.user.terra_script.world.city.CityLayout;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * 城市在大陆编辑器上的叠加渲染
 * 只负责“画”，不负责“算”
 */
public class CityOverlayRenderer {

    /* =========================
     * 颜色定义（ARGB）
     * ========================= */

    private static final int COLOR_CORE   = 0xAAE53935; // 深红
    private static final int COLOR_URBAN  = 0xAAFB8C00; // 橙
    private static final int COLOR_RING   = 0xAA1E88E5; // 蓝
    private static final int COLOR_BUFFER = 0xAA43A047; // 绿
    private static final int COLOR_BORDER = 0xCC000000; // 黑边
    private static final int COLOR_CENTER = 0xFFFFFFFF; // 白

    private static final int CHUNK_SIZE = 16;

    /* =========================
     * 主入口
     * ========================= */

    public static void render(
            GuiGraphics gui,
            CityLayout layout,
            MapTransform transform
    ) {
        if (layout == null || layout.chunks == null) {
            return;
        }

        PoseStack pose = gui.pose();
        pose.pushPose();

        // 渲染城市区块
        for (CityLayout.CityChunk chunk : layout.chunks) {
            int color = getZoneColor(chunk.zoneType);
            drawChunk(gui, transform, chunk.x, chunk.z, color);
        }

        // 渲染城市中心
        drawCenterPoint(
                gui,
                transform,
                layout.centerChunkX,
                layout.centerChunkZ
        );

        pose.popPose();
    }

    /* =========================
     * 区块绘制
     * ========================= */

    private static void drawChunk(
            GuiGraphics gui,
            MapTransform transform,
            int chunkX,
            int chunkZ,
            int fillColor
    ) {
        // chunk → block → screen
        int blockX = chunkX * CHUNK_SIZE;
        int blockZ = chunkZ * CHUNK_SIZE;

        int x0 = transform.blockToScreenX(blockX);
        int z0 = transform.blockToScreenZ(blockZ);
        int x1 = transform.blockToScreenX(blockX + CHUNK_SIZE);
        int z1 = transform.blockToScreenZ(blockZ + CHUNK_SIZE);

        // 填充
        gui.fill(x0, z0, x1, z1, fillColor);

        // 描边（增强层次感）
        drawBorder(gui, x0, z0, x1, z1, COLOR_BORDER);
    }

    private static void drawBorder(
            GuiGraphics gui,
            int x0, int y0,
            int x1, int y1,
            int color
    ) {
        gui.fill(x0, y0, x1, y0 + 1, color); // top
        gui.fill(x0, y1 - 1, x1, y1, color); // bottom
        gui.fill(x0, y0, x0 + 1, y1, color); // left
        gui.fill(x1 - 1, y0, x1, y1, color); // right
    }

    /* =========================
     * 城市中心点
     * ========================= */

    private static void drawCenterPoint(
            GuiGraphics gui,
            MapTransform transform,
            int centerChunkX,
            int centerChunkZ
    ) {
        int blockX = centerChunkX * CHUNK_SIZE + CHUNK_SIZE / 2;
        int blockZ = centerChunkZ * CHUNK_SIZE + CHUNK_SIZE / 2;

        int x = transform.blockToScreenX(blockX);
        int z = transform.blockToScreenZ(blockZ);

        int r = 3;

        gui.fill(x - r, z - r, x + r + 1, z + r + 1, COLOR_CENTER);
    }

    /* =========================
     * Zone → Color
     * ========================= */

    private static int getZoneColor(String zoneType) {
        if (zoneType == null) {
            return 0xAA888888;
        }
        return switch (zoneType.toUpperCase(java.util.Locale.ROOT)) {
            case "CORE" -> COLOR_CORE;
            case "URBAN" -> COLOR_URBAN;
            case "RING" -> COLOR_RING;
            case "BUFFER" -> COLOR_BUFFER;
            default -> 0xAA888888;
        };
    }

    // CityOverlayRenderer.java 增加

    /* =========================
     * 泰森多边形绘制 (新增)
     * ========================= */

    public static void renderDistricts(
            GuiGraphics gui,
            CityLayout layout,
            MapTransform transform
    ) {
        if (layout == null || layout.districts == null) return;

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f mat = gui.pose().last().pose();

        for (CityLayout.DistrictRenderData district : layout.districts) {
            // 【修复】判空保护
            if (district.polygon == null || district.polygon.isEmpty()) {
                // 如果没有多边形数据，只画一个中心点作为 fallback
                // 借用 drawCenterPoint 的逻辑，或者画一个小方块
                float cx = transform.worldToScreenX((int) district.centerX);
                float cy = transform.worldToScreenZ((int) district.centerZ);

                // 画个小点 (红色代表 Core, 橙色 Urban, 绿色 Buffer)
                int color = getZoneColor(district.type);
                // ... fill rect ...
                // 这里不能用 gui.fill，因为我们在 Tesselator 批次外?
                // 不，renderDistricts 是独立的，可以用 Tesselator 画个小 Quad
                buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                float r = 2.0f;
                int red = (color >> 16) & 0xFF;
                int grn = (color >> 8) & 0xFF;
                int blu = color & 0xFF;
                int alp = 255;

                buf.vertex(mat, cx - r, cy - r, 0).color(red, grn, blu, alp).endVertex();
                buf.vertex(mat, cx - r, cy + r, 0).color(red, grn, blu, alp).endVertex();
                buf.vertex(mat, cx + r, cy + r, 0).color(red, grn, blu, alp).endVertex();
                buf.vertex(mat, cx + r, cy - r, 0).color(red, grn, blu, alp).endVertex();
                tess.end();

                continue; // 跳过后续的多边形绘制
            }
        }

        RenderSystem.disableBlend();
    }
}

