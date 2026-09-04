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

        ProcessBuilder builder = new ProcessBuilder(
                installed.resolve("python/python.exe").toString(), "-m", "hermes_cli.main", "--version");
        builder.environment().put("PYTHONHOME", installed.resolve("python").toString());
        builder.environment().put("PYTHONPATH", HermesAgentClient.pythonPath(installed));
        builder.environment().put("PYTHONNOUSERSITE", "1");
        builder.environment().put("PATH", installed.resolve("node") + File.pathSeparator);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        assertTrue(process.waitFor(Duration.ofSeconds(20).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("Hermes Agent v0.18.2"), output);

        ProcessBuilder mcpImport = new ProcessBuilder(
                installed.resolve("python/python.exe").toString(), "-c",
                "import pywintypes; from mcp.client.stdio import stdio_client; "
                        + "import tools.mcp_tool as tool; assert tool._MCP_AVAILABLE; print('MCP_READY')");
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
