package com.rinsing.geomantia.systems.city.application;

import java.nio.file.Files;
import java.nio.file.Path;
import com.rinsing.geomantia.systems.provider.application.ManagedCityPlanningSources;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityTemplateContentPackInstaller;

/** Runs production source resolution and installation against an isolated package. */
public class VerifyStudioConfig {
    public static void main(String[] args) throws Exception {
        Path instance = Path.of(args[0]).toAbsolutePath();
        int expected = Integer.parseInt(args[1]);
        var sources = new ManagedCityPlanningSources(instance).resolve();
        if (!sources.directory().startsWith(instance)) throw new IllegalStateException("Escaped package");
        var profiles = CityStructureProfileCatalog.importCatalog(sources.directory(), sources.terrasenseProfileSource());
        if (profiles.profiles().size() != expected || !profiles.needsReview().isEmpty())
            throw new IllegalStateException("Profile import mismatch");
        Path world = Files.createTempDirectory(Path.of("build"), "studio-multi-install-check-");
        var installer = new CityTemplateContentPackInstaller();
        var first = installer.install(sources.directory(), world);
        var second = installer.install(sources.directory(), world);
        if (first.installedCount() != expected || second.installedCount() != 0 || second.unchangedCount() != expected)
            throw new IllegalStateException("Installation mismatch");
        try (var files = Files.walk(world.resolve("generated"))) {
            if (files.filter(p -> p.toString().endsWith(".nbt")).count() != expected)
                throw new IllegalStateException("Installed NBT count mismatch");
        }
        System.out.println("PASS production resolution/profile import/installation/idempotence: " + expected);
        System.out.println(first);
        System.out.println(second);
        System.out.println("Isolated installation: " + world);
    }
}
