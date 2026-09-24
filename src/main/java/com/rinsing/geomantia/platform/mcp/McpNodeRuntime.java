package com.rinsing.geomantia.platform.mcp;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import java.util.function.Consumer;

/** MCP owns its Node runtime; no Harness classes or archives are required. */
public final class McpNodeRuntime {
    private static final String SHA = "0d0f5e39f9f3d9587bc19f73eab3c2c9c4903fd02d6dbf9c853dd81b3d95fad4";
    private McpNodeRuntime() {}
    public static synchronized Path ensureInstalled(Path directory, Consumer<String> progress) throws IOException {
        if (!System.getProperty("os.name").toLowerCase().contains("windows")
                || !java.util.Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")))
            throw new IOException("当前 MCP 内置运行环境支持 Windows x64");
        Path root=directory.resolve("config/geomantia/runtime/mcp-node-22.23.2");
        Files.createDirectories(root);
        Path executable=root.resolve("node.exe");
        if (Files.isRegularFile(executable) && SHA.equals(hash(executable))) return root;
        progress.accept("正在准备 MCP 运行环境…");
        Path temporary=Files.createTempFile(root,"node-",".tmp");
        try (var input=McpNodeRuntime.class.getResourceAsStream("/geomantia/sidecar/node.exe")) {
            if(input==null) throw new IOException("MCP Node runtime missing");
            Files.copy(input,temporary,StandardCopyOption.REPLACE_EXISTING);
            if(!SHA.equals(hash(temporary))) throw new IOException("MCP Node runtime checksum mismatch");
            Files.move(temporary,executable,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        return root;
    }
    private static String hash(Path file) throws IOException {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(file)) { byte[] buffer=new byte[65536]; int n; while((n=input.read(buffer))!=-1) digest.update(buffer,0,n); }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
