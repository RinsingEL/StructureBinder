package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.*;
import com.rinsing.geomantia.systems.realm_planning.application.access.CityPlanningReservation;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Draws the current realm's complete draft, including its unchanged design and protection bounds. */
final class RealmCityDistributionPreview {
    static JsonObject render(Path run, Path image, JsonObject session) throws IOException {
        JsonObject context = read(run.resolve("world_survey_context.json"));
        int step = context.get("cellStepBlocks").getAsInt();
        String realm = session.get("realmId").getAsString();
        Set<String> owned = new HashSet<>();
        JsonArray territory = read(run.resolve("realm_territory_map.json")).getAsJsonArray("territoryCells");
        double minX = Double.POSITIVE_INFINITY, minZ = minX, maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
        for (JsonElement item : territory) {
            JsonObject cell = item.getAsJsonObject();
            if (!realm.equals(text(cell,"realmId")) || cell.has("status") && !"owned".equals(text(cell,"status"))) continue;
            int x = cell.get("gridX").getAsInt(), z = cell.get("gridZ").getAsInt();
            owned.add(x + "," + z);
            minX = Math.min(minX, (double)x * step); minZ = Math.min(minZ, (double)z * step);
            maxX = Math.max(maxX, ((double)x + 1) * step); maxZ = Math.max(maxZ, ((double)z + 1) * step);
        }
        JsonArray legend = new JsonArray();
        int number = 0;
        for (JsonElement item : session.getAsJsonArray("citySeeds")) {
            JsonObject seed = item.getAsJsonObject();
            var bounds = CityPlanningReservation.fromSeed(seed, step);
            minX = Math.min(minX,bounds.protection().minX()); minZ = Math.min(minZ,bounds.protection().minZ());
            maxX = Math.max(maxX,bounds.protection().maxX()+1.0); maxZ = Math.max(maxZ,bounds.protection().maxZ()+1.0);
            JsonObject entry = new JsonObject();
            for (String key : new String[]{"citySeedId","name","role","theoreticalScale","serviceHierarchy","positioning",
                    "anchorBlock","designBounds","protectionBounds"}) {
                if (seed.has(key)) entry.add(key,seed.get(key).deepCopy());
            }
            entry.addProperty("mapLabel","C" + (++number)); legend.add(entry);
        }
        if (!Double.isFinite(minX)) throw new IllegalArgumentException("T4_PATCH_REALM_HAS_NO_OWNED_TERRITORY");
        double originX = minX-step, originZ = minZ-step;
        double scale = Math.min(850.0/(maxX-minX+2*step), 650.0/(maxZ-minZ+2*step));
        BufferedImage bitmap = new BufferedImage(1180,760,BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bitmap.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(23,29,37)); g.fillRect(0,0,1180,760);
            g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,17)); g.setColor(Color.WHITE);
            g.drawString("T4 city distribution - " + realm,30,27);
            g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,12));
            g.drawString("Solid: design bounds   Dashed: protection bounds   Gold: capital",30,49);
            for (JsonElement item : read(run.resolve("world_patch_map.json")).getAsJsonArray("cells")) {
                JsonObject cell = item.getAsJsonObject();
                int x=cell.get("gridX").getAsInt(), z=cell.get("gridZ").getAsInt();
                double bx=(double)x*step, bz=(double)z*step;
                if (bx+step<originX || bz+step<originZ || bx>maxX+step || bz>maxZ+step) continue;
                boolean water="water".equals(text(cell,"baseLandform")) || "water".equals(text(cell,"landWater"));
                g.setColor(owned.contains(x+","+z) ? new Color(76,116,92)
                        : water ? new Color(35,70,101) : new Color(47,53,60));
                g.fillRect(30+(int)((bx-originX)*scale),70+(int)((bz-originZ)*scale),
                        Math.max(1,(int)Math.ceil(step*scale)),Math.max(1,(int)Math.ceil(step*scale)));
            }
            int row=0;
            int rowHeight=Math.max(12,Math.min(48,650/Math.max(1,legend.size())));
            for (JsonElement item : legend) {
                JsonObject seed=item.getAsJsonObject(); var reservation=CityPlanningReservation.fromSeed(seed,step);
                g.setColor("capital".equals(text(seed,"role")) ? new Color(255,211,91) : new Color(255,155,90));
                g.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_MITER,10,new float[]{5,4},0));
                drawBounds(g,reservation.protection().asJson(),originX,originZ,scale);
                g.setStroke(new BasicStroke(2)); drawBounds(g,reservation.design().asJson(),originX,originZ,scale);
                JsonObject anchor=seed.getAsJsonObject("anchorBlock");
                int x=30+(int)((anchor.get("x").getAsDouble()-originX)*scale);
                int z=70+(int)((anchor.get("z").getAsDouble()-originZ)*scale);
                g.fillOval(x-4,z-4,8,8); g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,13));
                g.drawString(text(seed,"mapLabel"),x+6,z-5);
                String name=text(seed,"name"); if (name.isBlank()) name=text(seed,"citySeedId");
                g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,Math.min(13,rowHeight)));
                g.drawString(text(seed,"mapLabel") + "  " + name,905,88+row*rowHeight);
                if (rowHeight>=28) {
                    g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,11));
                    g.drawString(text(seed,"theoreticalScale") + " / " + text(seed,"serviceHierarchy"),905,103+row*rowHeight);
                }
                row++;
            }
            g.setColor(Color.WHITE); g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,12));
            g.drawString("Cities: " + legend.size() + "   Review national roles, sizes and locations before finalizing.",30,745);
        } finally { g.dispose(); }
        Files.createDirectories(image.getParent()); ImageIO.write(bitmap,"png",image.toFile());
        JsonObject result=new JsonObject();
        result.addProperty("cityCount",legend.size()); result.add("cityLegend",legend);
        result.addProperty("instruction","查看全国分布图中的全部城市、设计范围和保护范围，复核规模、服务分工与位置。需要调整则增删城市后重看；成立后提交当前 proposalHash 的 review，再 finalize。");
        return result;
    }
    private static void drawBounds(Graphics2D g,JsonObject bounds,double ox,double oz,double scale) {
        double x=bounds.get("minX").getAsDouble(),z=bounds.get("minZ").getAsDouble();
        g.drawRect(30+(int)((x-ox)*scale),70+(int)((z-oz)*scale),
                Math.max(1,(int)Math.ceil((bounds.get("maxX").getAsDouble()-x+1)*scale)),
                Math.max(1,(int)Math.ceil((bounds.get("maxZ").getAsDouble()-z+1)*scale)));
    }
    private static String text(JsonObject o,String key) { return o.has(key)?o.get(key).getAsString():""; }
    private static JsonObject read(Path path) throws IOException { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
}
