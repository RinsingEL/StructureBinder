package com.rinsing.realmfolk.client;

import com.rinsing.realmfolk.entity.CitizenEntity;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelPart;

/** Keep familiar villager appearance while making seated, working and eating poses visible. */
public final class LivingCitizenModel extends VillagerModel<CitizenEntity> {
    private final ModelPart right,left,arms,body,jacket;
    public LivingCitizenModel(ModelPart root){super(root);right=root.getChild("right_leg");left=root.getChild("left_leg");arms=root.getChild("arms");body=root.getChild("body");jacket=body.getChild("jacket");}
    @Override public void setupAnim(CitizenEntity npc,float limbSwing,float amount,float age,float yaw,float pitch){
        super.setupAnim(npc,limbSwing,amount,age,yaw,pitch);arms.xRot=-.75f;body.xRot=0;
        // The full-length robe otherwise hides the bent legs and intersects the chair.
        jacket.yScale=npc.isPassenger()?.6f:1f;
        if(npc.isPassenger()){right.xRot=left.xRot=-1.4f;right.yRot=.12f;left.yRot=-.12f;}
        if(npc.activity().equals("工作")){arms.xRot=-.9f+(float)Math.sin(age*.35f)*.18f;body.xRot=.04f;}
        if(npc.isUsingItem()){arms.xRot=-1.4f+(float)Math.sin(age*.5f)*.08f;getHead().xRot=.25f;}
    }
}
