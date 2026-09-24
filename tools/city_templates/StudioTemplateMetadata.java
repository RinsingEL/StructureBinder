import com.google.gson.*;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.nbt.*;

/** Offline vanilla-template codec check; does not start a world or approve visuals. */
public class StudioTemplateMetadata {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (ExceptionInInitializerError error) {
            // Forge 47.4.0 initializes networking AFTER registries. Without ModLauncher,
            // EventBus cannot transform NetworkEvent. Only this known final-stage failure
            // is tolerated in this offline codec process; no network/gameplay is tested.
            Throwable root = error;
            while (root.getCause() != null) root = root.getCause();
            if (!(root instanceof NoSuchMethodException)
                    || !root.getMessage().contains("net.minecraftforge.network.NetworkEvent.<init>()")
                    || Arrays.stream(error.getStackTrace()).noneMatch(s ->
                        s.getClassName().equals("net.minecraftforge.network.NetworkHooks"))) throw error;
            System.err.println("Offline codec: registries initialized; Forge networking requires ModLauncher.");
        }
        if (BuiltInRegistries.BLOCK.keySet().size() < 1000) throw new IllegalStateException("Incomplete registry");
        JsonArray result = new JsonArray();
        for (var value : JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray()) {
            var input = value.getAsJsonObject();
            byte[] source = Files.readAllBytes(Path.of(input.get("sourceNbt").getAsString()));
            if (!hash(source).equals(input.get("sourceSha256").getAsString()))
                throw new IllegalStateException("Source changed: " + input);
            var raw = NbtIo.readCompressed(new ByteArrayInputStream(source));
            for (var state : raw.getList("palette", Tag.TAG_COMPOUND)) {
                CompoundTag authored = (CompoundTag) state;
                var decoded = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), authored);
                if (!authored.equals(NbtUtils.writeBlockState(decoded)))
                    throw new IllegalArgumentException("Palette lossy decode: " + authored);
            }
            var template = new StructureTemplate();
            template.load(BuiltInRegistries.BLOCK.asLookup(), raw);
            var saved = template.save(new CompoundTag());
            if (raw.getList("blocks", Tag.TAG_COMPOUND).size() != saved.getList("blocks", Tag.TAG_COMPOUND).size())
                throw new IllegalStateException("Block count changed");
            var bytes = new ByteArrayOutputStream();
            NbtIo.write(saved, new DataOutputStream(bytes));
            var row = new JsonObject();
            row.add("templateRef", input.get("templateRef"));
            row.addProperty("templateHash", hash(bytes.toByteArray()));
            var size = new JsonObject();
            size.addProperty("width", template.getSize().getX());
            size.addProperty("height", template.getSize().getY());
            size.addProperty("depth", template.getSize().getZ());
            row.add("rawSize", size);
            result.add(row);
        }
        Files.writeString(Path.of(args[1]), new GsonBuilder().setPrettyPrinting().create().toJson(result));
        System.out.println("Verified Minecraft codec and canonical hashes: " + result.size());
    }
    private static String hash(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
