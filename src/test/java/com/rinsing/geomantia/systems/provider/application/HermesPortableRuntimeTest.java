package com.rinsing.geomantia.systems.provider.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HermesPortableRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void installsAndRunsWithoutSystemPythonOrNode() throws Exception {
        HermesPortableRuntime runtime = new HermesPortableRuntime();
        Path installed = runtime.ensureInstalled(temporaryDirectory, ignored -> { });

        assertTrue(Files.isRegularFile(installed.resolve("python/python.exe")));
        assertTrue(Files.isRegularFile(installed.resolve("site-packages/hermes_cli/main.py")));
        assertTrue(Files.isRegularFile(installed.resolve("site-packages/win32/lib/pywintypes.py")));
        assertTrue(Files.isRegularFile(installed.resolve("site-packages/pywin32_system32/pywintypes312.dll")));
        assertTrue(Files.isRegularFile(installed.resolve("node/node.exe")));
        assertEquals(installed, runtime.ensureInstalled(temporaryDirectory, ignored -> { }));

        Path bootstrap = temporaryDirectory.resolve("geomantia_hermes_bootstrap.py");
        try (var input = HermesAgentClient.class.getResourceAsStream("/geomantia/sidecar/geomantia_hermes_bootstrap.py")) {
            java.util.Objects.requireNonNull(input, "Bundled cancellation adapter");
            Files.copy(input, bootstrap);
        }
        ProcessBuilder builder = new ProcessBuilder(
                installed.resolve("python/python.exe").toString(), bootstrap.toString(), "--version");
        builder.environment().put("PYTHONHOME", installed.resolve("python").toString());
        builder.environment().put("PYTHONPATH", HermesAgentClient.pythonPath(installed));
        builder.environment().put("PYTHONNOUSERSITE", "1");
        builder.environment().put("HERMES_HOME", temporaryDirectory.resolve("isolated-profile").toString());
        builder.environment().put("PATH", installed.resolve("node") + File.pathSeparator);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        assertTrue(process.waitFor(Duration.ofSeconds(20).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("Hermes Agent v0.18.2"), output);

        Path configPath = temporaryDirectory.resolve("test-config.yaml");
        Files.writeString(configPath, new HermesAgentClient().profileConfig(
                new PlayerProviderConfig(PlayerProviderConfig.CUSTOM, true, "https://open.bigmodel.cn/api/paas/v4",
                        "glm-5.3-flash", PlayerProviderConfig.CHAT_COMPLETIONS, 120),
                temporaryDirectory.resolve("mcp.mjs"), java.util.List.of("city_submit_d4_blueprint")));
        ProcessBuilder mcpImport = new ProcessBuilder(
                installed.resolve("python/python.exe").toString(), "-c",
                "import sys; sys.path.insert(0, sys.argv[1]); import geomantia_hermes_bootstrap as b; "
                        + "from gateway.platforms import api_server as a; b.install_input_adapter(a); "
                        + "s='x'*300000+'contextId:tail'; "
                        + "parts=[{'type':'text','text':s},{'type':'image_url','image_url':{'url':'data:image/png;base64,AA=='}}]; "
                        + "assert a._normalize_multimodal_content(parts)==parts; assert a._normalize_chat_content(s)==s; "
                        + "assert a.MAX_NORMALIZED_TEXT_LENGTH==sys.maxsize; "
                        + "import yaml; cfg=yaml.safe_load(open(sys.argv[2],encoding='utf-8')); "
                        + "from agent.image_routing import _lookup_supports_vision; "
                        + "assert _lookup_supports_vision('custom','glm-5.3-flash',cfg) is True; "
                        + "from agent.agent_init import _custom_provider_extra_body_for_agent as extras; "
                        + "assert extras(provider='custom',model='glm-5.3-flash',base_url='https://open.bigmodel.cn/api/paas/v4',"
                        + "custom_providers=cfg['custom_providers'])=={'reasoning_effort':'low'}; "
                        + "import pywintypes; from mcp.client.stdio import stdio_client; "
                        + "import tools.mcp_tool as tool; assert tool._MCP_AVAILABLE; print('MCP_READY')",
                temporaryDirectory.toString(), configPath.toString());
        mcpImport.environment().put("PYTHONHOME", installed.resolve("python").toString());
        mcpImport.environment().put("PYTHONPATH", HermesAgentClient.pythonPath(installed));
        mcpImport.environment().put("PYTHONNOUSERSITE", "1");
        mcpImport.environment().put("PATH", installed.resolve("node") + File.pathSeparator);
        mcpImport.redirectErrorStream(true);
        Process mcpProcess = mcpImport.start();
        assertTrue(mcpProcess.waitFor(Duration.ofSeconds(20).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));
        String mcpOutput = new String(mcpProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, mcpProcess.exitValue(), mcpOutput);
        assertTrue(mcpOutput.contains("MCP_READY"), mcpOutput);
    }
}
