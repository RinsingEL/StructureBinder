package com.rinsing.geomantia.systems.city.infrastructure.preview;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import java.util.concurrent.TimeUnit;

final class CityBlockMaterialPreviewClient {
    static byte[] render(String id) throws Exception {
        var mc=Minecraft.getInstance();
        if(mc.isSameThread())return renderNow(id);
        return mc.submit(()->{try{return renderNow(id);}catch(Exception ex){throw new IllegalStateException(ex);}}).get(5,TimeUnit.SECONDS);
    }
    private static byte[] renderNow(String id) throws Exception {
        var mc=Minecraft.getInstance();var block=BuiltInRegistries.BLOCK.get(new ResourceLocation(id));
        var texture=mc.getBlockRenderer().getBlockModelShaper().getParticleIcon(block.defaultBlockState()).contents().name();
        var resource=mc.getResourceManager().getResource(new ResourceLocation(texture.getNamespace(),"textures/"+texture.getPath()+".png"));
        if(resource.isEmpty())throw new IOException("Texture resource unavailable: "+texture);
        try(var input=resource.get().open();var output=new ByteArrayOutputStream()) {
            BufferedImage source=ImageIO.read(input);if(source==null)throw new IOException("Unsupported texture image");
            BufferedImage swatch=new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);var g=swatch.createGraphics();
            try {g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                int frame=Math.min(source.getWidth(),source.getHeight());g.drawImage(source,0,0,128,128,0,0,frame,frame,null);
            }finally{g.dispose();}
            ImageIO.write(swatch,"png",output);return output.toByteArray();
        }
    }
}
