package com.rinsing.geomantia.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.rinsing.geomantia.systems.realm_planning.application.access.AccessBoundary;
import com.rinsing.geomantia.systems.realm_planning.application.access.AccessBoundary.Segment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.List;

/** World-space ribbons: no entities, terrain lookups, custom shader, or full-screen darkening. */
@Mod.EventBusSubscriber(modid="geomantia",value=Dist.CLIENT)
public final class BoundaryVeil {
    private static List<Segment> segments=List.of();
    private static String dimension="";
    private static long receivedAt;
    public static void receive(String dim,List<Segment> lines) {
        dimension=dim; segments=lines; receivedAt=System.nanoTime();
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        segments=List.of(); dimension="";
    }
    private static boolean visible() {
        var mc=Minecraft.getInstance();
        return mc.level!=null && mc.player!=null && dimension.equals(mc.level.dimension().location().toString())
                && System.nanoTime()-receivedAt<10_000_000_000L && !segments.isEmpty();
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || !visible()) return;
        var mc=Minecraft.getInstance();
        var camera=event.getCamera().getPosition();
        if(segments.stream().noneMatch(s -> AccessBoundary.distance(s,camera.x,camera.z)<80)) return;
        var pose=event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x,-camera.y,-camera.z);
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest(); RenderSystem.depthMask(false); RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1,1,1,1);
        var tess=Tesselator.getInstance(); var b=tess.getBuilder();
        b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        float time=mc.level.getGameTime()+event.getPartialTick();
        for(Segment s:segments) {
            double distance=AccessBoundary.distance(s,camera.x,camera.z);
            float fade=(float)Math.max(0,1-distance/80);
            if(fade<=0) continue;
            // Small vertical strips drift independently, with feathered top and bottom edges.
            for(int strip=0;strip<4;strip++) {
                double a=strip/4.0,c=(strip+1)/4.0;
                double x1=s.x1()+(s.x2()-s.x1())*a,z1=s.z1()+(s.z2()-s.z1())*a;
                double x2=s.x1()+(s.x2()-s.x1())*c,z2=s.z1()+(s.z2()-s.z1())*c;
                for(int band=0;band<8;band++) {
                    double y1=camera.y-32+band*8,y2=y1+8;
                    float pulse=(float)(0.90+0.10*Math.sin(x1*.23+z1*.19+band*.9-time*.045));
                    float alpha=(float)Math.sqrt(fade)*(.55f+.40f*fade)*pulse;
                    float low=alpha*(float)Math.sin(Math.PI*band/8),high=alpha*(float)Math.sin(Math.PI*(band+1)/8);
                    var m=pose.last().pose();
                    b.vertex(m,(float)x1,(float)y1,(float)z1).color(.012f,.018f,.030f,low).endVertex();
                    b.vertex(m,(float)x2,(float)y1,(float)z2).color(.012f,.018f,.030f,low).endVertex();
                    b.vertex(m,(float)x2,(float)y2,(float)z2).color(.012f,.018f,.030f,high).endVertex();
                    b.vertex(m,(float)x1,(float)y2,(float)z1).color(.012f,.018f,.030f,high).endVertex();
                }
            }
        }
        tess.end();
        RenderSystem.enableCull(); RenderSystem.depthMask(true); RenderSystem.disableBlend();
        pose.popPose();
    }
    @SubscribeEvent public static void hud(RenderGuiEvent.Post event) {
        if(!visible() || Minecraft.getInstance().options.hideGui) return;
        var mc=Minecraft.getInstance();
        double distance=segments.stream().mapToDouble(s -> AccessBoundary.distance(s,mc.player.getX(),mc.player.getZ())).min().orElse(96);
        if(distance>80) return;
        Component label=Component.translatable("geomantia.boundary.distance",(int)Math.ceil(distance));
        int width=event.getGuiGraphics().guiWidth();
        event.getGuiGraphics().drawCenteredString(mc.font,label,width/2,event.getGuiGraphics().guiHeight()-72,0xD6C9AD);
    }
}
