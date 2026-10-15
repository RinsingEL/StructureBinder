package com.rinsing.realmfolk.entity;

import com.rinsing.realmfolk.data.SocietyData;
import com.rinsing.realmfolk.model.Society;
import com.rinsing.realmfolk.server.Dialogues;
import com.rinsing.realmfolk.server.SocietyRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.BlockPathTypes;

public final class CitizenEntity extends PathfinderMob implements net.minecraft.world.entity.npc.VillagerDataHolder {
    private static final EntityDataAccessor<String> ROLE=SynchedEntityData.defineId(CitizenEntity.class,EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> ACTIVITY=SynchedEntityData.defineId(CitizenEntity.class,EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> APPEARANCE=SynchedEntityData.defineId(CitizenEntity.class,EntityDataSerializers.STRING);
    private String citizenId="";
    private int appliedRevision=-1;
    public CitizenEntity(EntityType<? extends CitizenEntity> type, Level level) {
        super(type,level); setPersistenceRequired(); setPathfindingMalus(BlockPathTypes.DANGER_FIRE,16);
        if (getNavigation() instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation nav) nav.setCanOpenDoors(true);
    }
    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH,30).add(Attributes.MOVEMENT_SPEED,.29)
            .add(Attributes.ATTACK_DAMAGE,5).add(Attributes.FOLLOW_RANGE,80);
    }
    @Override protected void defineSynchedData() { super.defineSynchedData(); entityData.define(ROLE,"resident"); entityData.define(ACTIVITY,"闲逛");entityData.define(APPEARANCE,"resident"); }
    @Override protected void registerGoals() {
        goalSelector.addGoal(0,new FloatGoal(this));
        goalSelector.addGoal(1,new MeleeAttackGoal(this,1.15,true));
        goalSelector.addGoal(2,new OpenDoorGoal(this,true));
        goalSelector.addGoal(6,new LookAtPlayerGoal(this,Player.class,6));
        goalSelector.addGoal(7,new RandomLookAroundGoal(this));
    }
    public void bind(Society.Citizen citizen) {
        citizenId=citizen.id; entityData.set(ROLE,citizen.role);
        setCustomName(Component.literal(citizen.name)); setCustomNameVisible(true);
        applyProfile(citizen);setHealth((float)citizen.health);
    }
    public void applyProfile(Society.Citizen citizen){
        // Entity NBT from earlier builds retains the old 16-block navigation search range.
        getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(80);
        var profile=com.rinsing.realmfolk.server.DesignService.profile(level().getServer(),citizen);
        if(appliedRevision==citizen.designRevision)return;
        float ratio=getHealth()/getMaxHealth();
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(citizen.health);
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(citizen.attack);
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(citizen.speed);
        setHealth(Math.min(getMaxHealth(),ratio*getMaxHealth()));
        entityData.set(ROLE,citizen.role);entityData.set(APPEARANCE,profile.appearance);
        var item=net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(profile.equipment));
        setItemSlot(EquipmentSlot.MAINHAND,new net.minecraft.world.item.ItemStack(item==null?net.minecraft.world.item.Items.AIR:item));setDropChance(EquipmentSlot.MAINHAND,0);
        appliedRevision=citizen.designRevision;
    }
    public String citizenId() { return citizenId; }
    public String role() { return entityData.get(ROLE); }
    public String activity() { return entityData.get(ACTIVITY); }
    @Override public net.minecraft.world.entity.npc.VillagerData getVillagerData() {
        var profession=switch(entityData.get(APPEARANCE)) {
            case "smith" -> net.minecraft.world.entity.npc.VillagerProfession.TOOLSMITH;
            case "guard" -> net.minecraft.world.entity.npc.VillagerProfession.ARMORER;
            case "ranger" -> net.minecraft.world.entity.npc.VillagerProfession.FLETCHER;
            case "merchant" -> net.minecraft.world.entity.npc.VillagerProfession.CARTOGRAPHER;
            case "mayor" -> net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN;
            case "farmer" -> net.minecraft.world.entity.npc.VillagerProfession.FARMER;
            default -> net.minecraft.world.entity.npc.VillagerProfession.NONE;
        };
        return new net.minecraft.world.entity.npc.VillagerData(net.minecraft.world.entity.npc.VillagerType.PLAINS,profession,1);
    }
    @Override public void setVillagerData(net.minecraft.world.entity.npc.VillagerData data) { /* Occupation is authored in the citizen profile. */ }
    public void activity(String text) { entityData.set(ACTIVITY,text); }
    @Override public void addAdditionalSaveData(CompoundTag tag) { super.addAdditionalSaveData(tag); tag.putString("CitizenId",citizenId); tag.putString("Role",role()); }
    @Override public void readAdditionalSaveData(CompoundTag tag) { super.readAdditionalSaveData(tag); citizenId=tag.getString("CitizenId"); entityData.set(ROLE,tag.getString("Role")); }
    @Override public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand!=InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (player instanceof ServerPlayer serverPlayer) { com.rinsing.realmfolk.server.DailyLifeRuntime.interrupt(this); Dialogues.open(serverPlayer,this,""); }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }
    @Override public void tick() { super.tick(); if (!level().isClientSide && tickCount%20==0) SocietyRuntime.tickCitizen(this); }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public boolean canAttack(LivingEntity target) { return target.isAlive() && super.canAttack(target); }
    public Society.Citizen profile() { return level().getServer()==null?null:SocietyData.get(level().getServer()).society.citizens.get(citizenId); }
}
