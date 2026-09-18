package com.rinsing.geomantia.systems.city.application;
import com.google.gson.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class CityBoundaryCapturedReplayTest {
 @TempDir Path root;
 @Test void originalProposalCompilesWithoutMovingAuthoredGroups() throws Exception {
  String fixture = System.getenv("GEOMANTIA_CITY_BOUNDARY_REPLAY_RUN");
  Assumptions.assumeTrue(fixture != null);
  Path run = Path.of(fixture);
  String city = "city_realm_northmark_capital";
  Path source = run.resolve("city_test_runs").resolve(city);
  Path target = root.resolve(run.getFileName()).resolve("city_test_runs").resolve(city);
  for (String step : new String[]{"blueprint", "d3", "land_use"}) {
   try(var files = Files.list(source.resolve("steps").resolve(step))) {
    for(Path file : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).toList()) {
     Path destination = target.resolve(source.relativize(file));
     Files.createDirectories(destination.getParent()); Files.copy(file,destination);
    }
   }
  }
  JsonObject proposal = JsonParser.parseString(Files.readString(target.resolve("steps/blueprint/city_blueprint_blocked_proposal.json"))).getAsJsonObject();
  JsonObject original = proposal.deepCopy();
  var result = new CityBlueprintCompilerService().compileProposal(root, run.getFileName().toString(), city, proposal);
  System.out.println("BOUNDARY_REPLAY ok=" + result.ok() + " reason=" + result.reasonCode() + " message=" + result.message());
  assertTrue(result.ok(), result.reasonCode() + ": " + result.message());
  assertEquals(original, proposal);
 }
}
