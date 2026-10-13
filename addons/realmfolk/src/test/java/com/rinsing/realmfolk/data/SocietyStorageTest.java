package com.rinsing.realmfolk.data;

import com.rinsing.realmfolk.design.*;
import com.rinsing.realmfolk.model.Society.*;
import net.minecraft.nbt.*;
import java.io.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SocietyStorageTest {
    @Test void largerThanNbtStringLimitSurvivesCompressedDiskFormat()throws Exception{
        SocietyData data=new SocietyData();data.society.design.active=DesignCodec.defaults();
        for(int i=0;i<256;i++){
            Citizen c=new Citizen("id"+i,"中文姓名"+i,"town","house","resident",new Point(i,65,0),new Point(i,65,20));
            c.bio="居民背景介绍。".repeat(60);c.profession=DesignCodec.defaults().professions.get("resident");data.society.citizens.put(c.id,c);
        }
        CompoundTag nbt=data.save(new CompoundTag());assertTrue(nbt.getByteArray("society_utf8").length>65535);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();NbtIo.writeCompressed(nbt,bytes);
        SocietyData restored=SocietyData.load(NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(DesignCodec.hash(data.society),DesignCodec.hash(restored.society));
    }
    @Test void migratesLegacyStringWithoutChangingIdentities(){
        CompoundTag legacy=new CompoundTag();legacy.putString("society","{\"version\":1,\"citizens\":{\"old\":{\"id\":\"old\",\"name\":\"旧人物\",\"role\":\"smith\"}}}");
        SocietyData loaded=SocietyData.load(legacy);assertEquals(2,loaded.society.version);assertEquals("旧人物",loaded.society.citizens.get("old").name);
        CompoundTag saved=loaded.save(legacy);assertFalse(saved.contains("society"));assertTrue(saved.contains("society_utf8",Tag.TAG_BYTE_ARRAY));
    }
}
