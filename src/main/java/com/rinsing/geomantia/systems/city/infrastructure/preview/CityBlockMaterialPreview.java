package com.rinsing.geomantia.systems.city.infrastructure.preview;

import com.google.gson.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

/** Optional client resource-pack swatch; a dedicated server cannot invent a texture preview. */
public final class CityBlockMaterialPreview {
    private CityBlockMaterialPreview() { }
    public static void attach(JsonObject result,String blockId){
        try {
            byte[] png=DistExecutor.unsafeCallWhenOn(Dist.CLIENT,()->()->CityBlockMaterialPreviewClient.render(blockId));
            if(png==null){result.addProperty("previewUnavailable","No client texture resources on this server");return;}
            JsonObject image=new JsonObject();image.addProperty("type","image");image.addProperty("mimeType","image/png");
            image.addProperty("data",java.util.Base64.getEncoder().encodeToString(png));JsonArray images=new JsonArray();images.add(image);
            result.add("imageEvidence",images);result.addProperty("previewKind","Current resource pack particle texture swatch; not a 3D or placed-block preview");
        }catch(Exception error){result.addProperty("previewUnavailable",error.getClass().getSimpleName()+": "+error.getMessage());}
    }
}
