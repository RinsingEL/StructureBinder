package com.rinsing.realmfolk.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rinsing.realmfolk.model.Society;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.nio.charset.StandardCharsets;

public final class SocietyData extends SavedData {
    public static final Gson JSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    public Society society = new Society();
    public static SocietyData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(SocietyData::load,SocietyData::new,"realmfolk_society");
    }
    public static SocietyData load(CompoundTag tag) {
        SocietyData data=new SocietyData();
        String json=tag.contains("society_utf8",net.minecraft.nbt.Tag.TAG_BYTE_ARRAY)?new String(tag.getByteArray("society_utf8"),StandardCharsets.UTF_8):tag.getString("society");
        data.society=JSON.fromJson(json,Society.class);
        if (data.society==null || data.society.version<1 || data.society.version>2) throw new IllegalStateException("不支持的 Realmfolk 存档版本");
        if(data.society.version==1){data.society.version=2;data.setDirty();}
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag) { tag.putByteArray("society_utf8",JSON.toJson(society).getBytes(StandardCharsets.UTF_8));tag.remove("society");return tag; }
    private static java.nio.file.Path path(MinecraftServer server){return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/realmfolk_society.dat");}
    /** Atomic publication before the host records completion, including all related world state. */
    public static void flush(MinecraftServer server)throws java.io.IOException{
        var data=get(server);var target=path(server);java.nio.file.Files.createDirectories(target.getParent());
        var root=new CompoundTag();root.put("data",data.save(new CompoundTag()));
        root.putInt("DataVersion",net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        var temp=java.nio.file.Files.createTempFile(target.getParent(),"realmfolk-",".tmp");
        try{net.minecraft.nbt.NbtIo.writeCompressed(root,temp.toFile());
            try{java.nio.file.Files.move(temp,target,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            catch(java.nio.file.AtomicMoveNotSupportedException e){java.nio.file.Files.move(temp,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            data.setDirty(false);
        }finally{java.nio.file.Files.deleteIfExists(temp);}
    }
    public static boolean planningPersisted(MinecraftServer server,String key,String revision)throws java.io.IOException{
        var file=path(server);if(!java.nio.file.Files.exists(file))return false;
        var saved=load(net.minecraft.nbt.NbtIo.readCompressed(file.toFile()).getCompound("data"));
        var receipt=saved.society.design.planningReceipts.get(key);return receipt!=null&&receipt.taskRevision().equals(revision);
    }
}
