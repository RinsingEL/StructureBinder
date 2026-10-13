package com.rinsing.realmfolk.client;

import com.rinsing.realmfolk.net.Messages;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Item grid backed by a server quote, not client-side inventory or price calculation. */
public final class ShopScreen extends Screen {
    public final Messages.Shop shop;
    private boolean selling,pending;
    private int selected=-1,quantity=1,page,left,top,pw,ph,cols,pageSize;
    private final Map<String,Button> actions=new HashMap<>();
    private final List<Button> slots=new ArrayList<>();
    private final long opened=System.currentTimeMillis();
    public ShopScreen(Messages.Shop shop,ShopScreen previous){
        super(Component.literal(shop.name()));this.shop=shop;
        if(previous!=null&&previous.shop.entity()==shop.entity()){
            selling=previous.selling;quantity=previous.quantity;page=previous.page;selected=previous.selected;
            if(selected>=size())selected=-1;
        }
    }
    @Override protected void init(){
        pw=Math.min(530,width-16);ph=Math.min(330,height-12);left=(width-pw)/2;top=(height-ph)/2;
        cols=Math.max(4,Math.min(9,(pw/2-18)/26));pageSize=cols*3;page=Math.min(page,Math.max(0,(size()-1)/pageSize));
        actions.clear();slots.clear();
        button("stock",selling?"查看持有物":"持有物品",left+12,top+49,100,()->{selling=false;page=0;selected=-1;rebuildWidgets();});
        button("sales","出售物品",left+116,top+49,100,()->{selling=true;page=0;selected=-1;rebuildWidgets();}).active=shop.buyback();
        for(int i=page*pageSize;i<Math.min(size(),(page+1)*pageSize);i++){
            int index=i;var b=button("slot:"+i,"",left+12+(i%pageSize%cols)*26,top+77+(i%pageSize/cols)*26,24,()->{selected=index;rebuildWidgets();});slots.add(b);
        }
        button("previous","<",left+12,top+158,22,()->{page--;rebuildWidgets();}).active=page>0;
        button("next",">",left+cols*26-10,top+158,22,()->{page++;rebuildWidgets();}).active=(page+1)*pageSize<size();
        int x=left+12;for(int n:new int[]{1,16,64,4096}){int choice=n;button("quantity:"+n,n==4096?"全部":String.valueOf(n),x,top+184,48,()->{quantity=choice;rebuildWidgets();});x+=50;}
        int right=left+pw/2+8,rw=pw/2-20;
        int count=count();boolean chosen=selected>=0&&selected<size()&&count>0;
        button(selling?"sell":"buy",selling?"出售所选":"购买所选",right,top+184,rw,()->send(selling?"sell":"buy"))
            .active=chosen&&!pending&&(selling?shop.buyback()&&total()<=shop.buybackBudget():shop.trade()&&total()<=shop.balance());
        button("gift","请求赠送 · 掷骰",right,top+208,rw,()->send("gift"))
            .active=chosen&&!selling&&shop.gift()&&shop.stock().get(selected).gift()&&shop.requestIn()==0&&total()<=shop.giftBudget()&&chance()>0&&!pending;
        button("back","返回对话",left+12,top+ph-27,100,()->Messages.act(shop.entity(),"talk"));
        button("close","告辞",left+pw-77,top+ph-27,65,this::onClose);
    }
    private Button button(String id,String label,int x,int y,int w,Runnable run){var b=Button.builder(Component.literal(label),v->run.run()).bounds(x,y,w,20).build();addRenderableWidget(b);actions.put(id,b);return b;}
    private int size(){return selling?shop.sales().size():shop.stock().size();}
    private String item(int index){return selling?shop.sales().get(index).item():shop.stock().get(index).item();}
    private int available(int index){return selling?shop.sales().get(index).count():shop.stock().get(index).count();}
    private int price(){return selected<0||selected>=size()?0:selling?shop.sales().get(selected).price():shop.stock().get(selected).price();}
    private int count(){return selected<0||selected>=size()?0:Math.min(quantity,available(selected));}
    private long total(){return (long)count()*price();}
    private int chance(){if(selected<0||selling)return 0;var line=shop.stock().get(selected);return (int)Math.floor(Math.max(0,Math.min(95,line.baseChance()*Math.max(0,1+shop.affection()*shop.affectionFactor())/Math.sqrt(Math.max(1,count())))));}
    private void send(String kind){if(pending)return;pending=true;Messages.shop(shop,kind,selected,count());rebuildWidgets();}
    /** Playtest automation uses the same visible enabled controls as a player. */
    public boolean clickAction(String action){var button=actions.get(action);if(button==null||!button.active)return false;button.onPress();return true;}
    public boolean selectItem(boolean sale,int index,int count){if(index<0||index>=(sale?shop.sales().size():shop.stock().size()))return false;selling=sale;selected=index;quantity=Math.max(1,Math.min(4096,count));page=index/pageSize;rebuildWidgets();return true;}
    private ItemStack icon(String item){return new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation(item)));}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        renderBackground(g);g.fill(left,top,left+pw,top+ph,0xF0142028);g.fill(left,top,left+pw,top+3,0xFFD2AD69);
        g.drawString(font,shop.name()+" · "+shop.town(),left+12,top+12,0xFFE8C884);
        g.drawString(font,"余额 "+shop.balance()+" "+shop.currency()+"    好感 "+shop.affection(),left+12,top+30,0xFFDCDACE);
        super.render(g,mx,my,partial);
        for(int i=0;i<slots.size();i++){
            int index=page*pageSize+i;var b=slots.get(i);var icon=icon(item(index));
            if(index==selected)g.fill(b.getX(),b.getY()+20,b.getX()+24,b.getY()+22,0xFFFFD580);
            g.renderItem(icon,b.getX()+4,b.getY()+2);g.renderItemDecorations(font,icon,b.getX()+4,b.getY()+2,String.valueOf(available(index)));
            if(b.isHovered())g.renderTooltip(font,Component.literal(icon.getHoverName().getString()+" × "+available(index)),mx,my);
        }
        g.drawCenteredString(font,(page+1)+" / "+Math.max(1,(size()+pageSize-1)/pageSize),left+12+cols*13,top+164,0xFFBFCBCD);
        int rx=left+pw/2+8,rw=pw/2-20;
        if(selected>=0&&selected<size()){
            g.drawString(font,font.plainSubstrByWidth(icon(item(selected)).getHoverName().getString(),rw),rx,top+79,0xFFE8C884);
            g.drawString(font,"数量 "+count()+" / "+available(selected),rx,top+96,0xFFDCDACE);
            g.drawString(font,"单价 "+price()+" · 合计 "+total()+" "+shop.currency(),rx,top+112,0xFFDCDACE);
            if(!selling&&shop.gift()){
                g.drawString(font,"赠礼成功率 "+chance()+"% · d100 ≤ "+chance(),rx,top+133,0xFFB9D6C0);
                g.drawString(font,shop.requestIn()>0?"请求间隔还剩 "+(shop.requestIn()+19)/20+" 秒":"可请求额度 "+shop.giftBudget()+" "+shop.currency(),rx,top+150,0xFFACBAC0);
            }else if(selling)g.drawString(font,"收购预算 "+shop.buybackBudget()+" "+shop.currency(),rx,top+137,0xFFACBAC0);
        }else g.drawWordWrap(font,Component.literal(selling?"选择背包里可出售的普通物品。附魔、改名或带额外数据的物品不会被收走。":"选择物品，购买或请求赠送。"),rx,top+80,rw,0xFFDCDACE);
        g.drawString(font,"补货约 "+(shop.restockIn()+1199)/1200+" 分钟后",left+12,top+216,0xFFACBAC0);
        String feedback=shop.feedback();
        if(shop.roll()!=null){var roll=shop.roll();feedback="d100："+(System.currentTimeMillis()-opened<650?"掷骰中…":roll.dice()+" / ≤ "+roll.chance())+"  "+shop.feedback();}
        g.enableScissor(left+10,top+235,left+pw-10,top+ph-32);g.drawWordWrap(font,Component.literal(feedback),left+12,top+237,pw-24,0xFFE9D4A3);g.disableScissor();
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){Messages.act(shop.entity(),"dialogue_close");super.onClose();}
}
