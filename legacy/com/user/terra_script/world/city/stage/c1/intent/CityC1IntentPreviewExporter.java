package com.user.terra_script.world.city.stage.c1.intent;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.user.terra_script.world.city.stage.c1.intent.CityC1ImageIntentModels.IMAGE_SIZE;

public final class CityC1IntentPreviewExporter {
    private CityC1IntentPreviewExporter() {}

    public static void exportOverlay(
            BufferedImage base,
            CityC1ImageIntentModels.UrbanIntentMap map,
            Path output
    ) throws Exception {
        BufferedImage image = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (base != null) {
            g.drawImage(CityC1IntentMaskParser.normalizeToCanvas(base), 0, 0, null);
            g.setColor(new Color(255, 255, 255, 80));
            g.fillRect(0, 0, IMAGE_SIZE, IMAGE_SIZE);
        } else {
            g.setColor(new Color(238, 238, 232));
            g.fillRect(0, 0, IMAGE_SIZE, IMAGE_SIZE);
        }

        int index = 0;
        for (CityC1ImageIntentModels.DistrictPolygon district : map.district_polygons) {
            Polygon polygon = toAwtPolygon(district);
            Color color = districtColor(index++);
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 105));
            g.fillPolygon(polygon);
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 220));
            g.setStroke(new BasicStroke(2f));
            g.drawPolygon(polygon);
        }

        if (map.city_boundary != null) {
            g.setStroke(new BasicStroke(4f));
            g.setColor(new Color(20, 20, 20, 220));
            g.drawPolygon(toAwtPolygon(map.city_boundary));
        }

        if (map.road_sketch != null && map.road_sketch.paths != null) {
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(70, 70, 70, 230));
            for (CityC1ImageIntentModels.RoadPath path : map.road_sketch.paths) {
                for (int i = 1; i < path.polyline.size(); i++) {
                    CityC1ImageIntentModels.IntentPoint a = path.polyline.get(i - 1);
                    CityC1ImageIntentModels.IntentPoint b = path.polyline.get(i);
                    g.drawLine(a.pixel_x, a.pixel_z, b.pixel_x, b.pixel_z);
                }
            }
        }

        if (map.anchor_points != null) {
            g.setColor(new Color(210, 40, 190, 230));
            for (CityC1ImageIntentModels.AnchorPoint anchor : map.anchor_points) {
                g.fillOval(anchor.pixel_x - 5, anchor.pixel_z - 5, 10, 10);
            }
        }
        g.dispose();
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }

    private static Polygon toAwtPolygon(CityC1ImageIntentModels.IntentPolygon input) {
        Polygon polygon = new Polygon();
        if (input != null && input.polygon != null) {
            for (CityC1ImageIntentModels.IntentPoint point : input.polygon) {
                polygon.addPoint(point.pixel_x, point.pixel_z);
            }
        }
        return polygon;
    }

    private static Color districtColor(int index) {
        Color[] colors = {
                new Color(96, 173, 88),
                new Color(220, 185, 70),
                new Color(96, 145, 210),
                new Color(190, 115, 200),
                new Color(210, 118, 92),
                new Color(80, 175, 165)
        };
        return colors[Math.floorMod(index, colors.length)];
    }
}

