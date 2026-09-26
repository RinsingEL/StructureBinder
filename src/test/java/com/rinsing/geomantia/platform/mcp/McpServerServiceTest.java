package com.rinsing.geomantia.platform.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class McpServerServiceTest {
    @TempDir Path root;
    @Test void configValidatesAndNeverOverwritesInvalidExistingSettings() throws Exception {
        assertTrue(McpServerConfig.load(root).enabled());
        assertEquals(5001,McpServerConfig.load(root).port());
        new McpServerConfig(false,6123).save(root);
        assertEquals(new McpServerConfig(false,6123),McpServerConfig.load(root));
        assertThrows(IllegalArgumentException.class,()->new McpServerConfig(true,5000));
        assertThrows(IllegalArgumentException.class,()->new McpServerConfig(true,65536));
        String invalid="{\"enabled\":true,\"port\":5001.5}";
        Files.writeString(McpServerConfig.path(root),invalid);
        assertThrows(java.io.IOException.class,()->McpServerConfig.load(root));
        assertEquals(invalid,Files.readString(McpServerConfig.path(root)));
    }
    @Test void bundledServiceStartsWithoutAWorldAcceptsMcpAndAppliesPortChanges() throws Exception {
        if (Boolean.getBoolean("geomantia.coreOnlyTest")) {
            assertNull(getClass().getResource("/com/rinsing/geomantia/harness/systems/provider/application/HarnessAgentClient.class"));
            assertNull(getClass().getResource("/com/rinsing/geomantia/map/client/AdventurerMapScreen.class"));
        }
        int first=freePort(),second=freePort();
        while(first==second) second=freePort();
        int flash=freePort();while(flash==first||flash==second)flash=freePort();
        new McpServerConfig(true,first,flash).save(root);
        try(var service=new McpServerService()) {
            service.start(root);
            for(int i=0;i<600 && service.snapshot().state().equals("starting");i++) Thread.sleep(100);
            assertEquals("ready",service.snapshot().state(),service.snapshot().message());
            assertEquals("http://127.0.0.1:"+first+"/mcp",service.snapshot().url());
            assertEquals(flash,McpServerConfig.load(root).flashPort());
            var client=HttpClient.newHttpClient();
            var response=client.send(HttpRequest.newBuilder(URI.create(service.snapshot().url()))
                    .header("Content-Type","application/json").header("Accept","application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"java-smoke\",\"version\":\"1\"}}}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode(),response.body());
            assertTrue(response.body().contains("geomantia_lobby"));
            assertTrue(response.headers().firstValue("mcp-session-id").isPresent());
            assertEquals("ready",service.save(true,second).get(40,TimeUnit.SECONDS).state());
            assertEquals(second,McpServerConfig.load(root).port());
            assertEquals(200,client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+second+"/health")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals("disabled",service.save(false,second).get(10,TimeUnit.SECONDS).state());
            assertTrue(service.snapshot().url().isEmpty());
        }
    }
    private static int freePort() throws Exception { try(var socket=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }
}
