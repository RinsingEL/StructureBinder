package com.rinsing.realmfolk.client;

import com.rinsing.realmfolk.Realmfolk;
import com.rinsing.realmfolk.data.SocietyData;
import com.rinsing.realmfolk.entity.CitizenEntity;
import com.rinsing.realmfolk.net.Messages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

@Mod.EventBusSubscriber(modid=Realmfolk.ID,value=Dist.CLIENT)
public final class ClientViews {
    private static String inspection="";
    private static int ticks;
    public static void receive(Messages.View view) {
        if(view.kind().equals("dialogue"))Minecraft.getInstance().setScreen(new DialogueScreen(SocietyData.JSON.fromJson(view.json(),Messages.Dialogue.class)));
        if(view.kind().equals("inspect"))inspection=SocietyData.JSON.fromJson(view.json(),String.class);
        if(view.kind().equals("shop")){var mc=Minecraft.getInstance();mc.setScreen(new ShopScreen(SocietyData.JSON.fromJson(view.json(),Messages.Shop.class),mc.screen instanceof ShopScreen old?old:null));}
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END)return;Minecraft mc=Minecraft.getInstance();
        if(mc.player==null){inspection="";return;}
        if(++ticks%10==0&&mc.screen==null)Messages.act(-1,"inspect");
        if(ticks%20==0&&mc.screen instanceof DialogueScreen dialogue)Messages.act(dialogue.dialogue.entity(),"dialogue_keepalive");
        if(ticks%20==0&&mc.screen instanceof ShopScreen shop)Messages.act(shop.shop.entity(),"dialogue_keepalive");
        PlaytestBridge.tick();
    }
    @SubscribeEvent public static void overlay(RenderGuiEvent.Post event) {
        Minecraft mc=Minecraft.getInstance();if(mc.screen!=null||inspection.isBlank()||mc.options.hideGui)return;
        var g=event.getGuiGraphics();int width=mc.getWindow().getGuiScaledWidth(), y=mc.getWindow().getGuiScaledHeight()/2+24;
        int textWidth=mc.font.width(inspection);g.fill(width/2-textWidth/2-6,y-4,width/2+textWidth/2+6,y+13,0xB0101820);
        g.drawCenteredString(mc.font,inspection,width/2,y,0xE6DFC9);
    }
    @Mod.EventBusSubscriber(modid=Realmfolk.ID,value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent public static void renderer(EntityRenderersEvent.RegisterRenderers event){event.registerEntityRenderer(Realmfolk.CITIZEN.get(),CitizenRenderer::new);}
        @SubscribeEvent public static void setup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event){event.enqueueWork(PlaytestBridge::start);}
    }
    private static class CitizenRenderer extends MobRenderer<CitizenEntity,VillagerModel<CitizenEntity>> {
        private static final ResourceLocation TEXTURE=new ResourceLocation("minecraft","textures/entity/villager/villager.png");
        CitizenRenderer(EntityRendererProvider.Context context){super(context,new LivingCitizenModel(context.bakeLayer(ModelLayers.VILLAGER)),.5f);
            addLayer(new net.minecraft.client.renderer.entity.layers.CustomHeadLayer<>(this,context.getModelSet(),context.getItemInHandRenderer()));
            addLayer(new net.minecraft.client.renderer.entity.layers.VillagerProfessionLayer<>(this,context.getResourceManager(),"villager"));
        }
        @Override public ResourceLocation getTextureLocation(CitizenEntity entity){return TEXTURE;}
    }
    public static final class DialogueScreen extends Screen {
        public final Messages.Dialogue dialogue;
        private int page;
        private int left,top,panelWidth,panelHeight,bodyHeight,perPage;
        private final Map<String,Button> actionButtons=new LinkedHashMap<>();
        public DialogueScreen(Messages.Dialogue dialogue){super(Component.literal(dialogue.name()));this.dialogue=dialogue;}
        @Override protected void init(){
            panelWidth=Math.min(460,width-24);panelHeight=Math.min(height-20,340);left=(width-panelWidth)/2;top=(height-panelHeight)/2;
            bodyHeight=Math.min(86,Math.max(48,panelHeight/3));perPage=Math.max(2,(panelHeight-bodyHeight-91)/23);
            actionButtons.clear();int start=page*perPage;
            for(int i=start;i<Math.min(dialogue.options().size(),start+perPage);i++){
                Messages.Option option=dialogue.options().get(i);
                Button button=Button.builder(Component.literal(option.label()),b->Messages.act(dialogue.entity(),option.id()))
                    .bounds(left+14,top+56+bodyHeight+(i-start)*23,panelWidth-28,20).build();
                button.active=option.enabled();actionButtons.put(option.id(),button);addRenderableWidget(button);
            }
            if(dialogue.options().size()>perPage) {
                addRenderableWidget(Button.builder(Component.literal("上一页"),b->{page=Math.max(0,page-1);rebuildWidgets();}).bounds(left+14,top+panelHeight-27,65,20).build());
                addRenderableWidget(Button.builder(Component.literal("下一页"),b->{page=(page+1)%((dialogue.options().size()+perPage-1)/perPage);rebuildWidgets();}).bounds(left+84,top+panelHeight-27,65,20).build());
            }
            addRenderableWidget(Button.builder(Component.literal("告辞"),b->onClose()).bounds(left+panelWidth-79,top+panelHeight-27,65,20).build());
        }
        public boolean clickAction(String action){Button b=actionButtons.get(action);if(b!=null&&b.active){b.onPress();return true;}
            int index=-1;for(int i=0;i<dialogue.options().size();i++)if(dialogue.options().get(i).id().equals(action))index=i;
            if(index>=0&&dialogue.options().get(index).enabled()){page=index/perPage;rebuildWidgets();actionButtons.get(action).onPress();return true;}return false;}
        @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partial){
            renderBackground(g);g.fill(left,top,left+panelWidth,top+panelHeight,0xF0142028);
            g.fill(left,top,left+panelWidth,top+3,0xFFD2AD69);g.drawString(font,dialogue.name(),left+14,top+13,0xFFE8C884);
            g.drawString(font,dialogue.subtitle(),left+14,top+29,0xFFACBAC0);
            g.enableScissor(left+12,top+46,left+panelWidth-12,top+48+bodyHeight);
            g.drawWordWrap(font,Component.literal(dialogue.text()),left+14,top+47,panelWidth-28,0xFFE5E0D3);g.disableScissor();
            super.render(g,mouseX,mouseY,partial);
        }
        @Override public boolean isPauseScreen(){return false;}
        @Override public void onClose(){Messages.act(dialogue.entity(),"dialogue_close");super.onClose();}
    }
}
