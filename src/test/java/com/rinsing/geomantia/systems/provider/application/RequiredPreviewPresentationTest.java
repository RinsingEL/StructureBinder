package com.rinsing.geomantia.systems.provider.application;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RequiredPreviewPresentationTest {
 @TempDir Path root;
 private String picture(String name) throws Exception {
  Path file=root.resolve(name);Files.write(file,new byte[]{1,2,3});return file.toString();
 }
 @Test void revisedDesignReturnsOverviewAndLeavesOptionalImagesDiscoverable() throws Exception {
  var source=new JsonObject();var revision=new JsonObject();
  revision.addProperty("compiledPreview",picture("overview.png"));
  revision.addProperty("compiledGroupPreview",picture("detail.png"));source.add("revisionEvidence",revision);
  source.addProperty("terrainPreview",picture("terrain.png"));
  var result=PlanningToolPresentation.present(source,root);
  assertEquals(1,result.getAsJsonArray("imageEvidence").size());
  assertEquals(revision.get("compiledPreview"),result.getAsJsonArray("imageEvidence").get(0).getAsJsonObject().get("path"));
  assertEquals(revision,result.get("revisionEvidence"));
  assertTrue(result.has("terrainPreview"));
 }
 @Test void initialTerrainAndExplicitLocalViewsStillReturnMultipleImages() throws Exception {
  var source=new JsonObject();source.addProperty("terrain",picture("terrain.png"));source.addProperty("candidates",picture("candidates.png"));
  assertEquals(2,PlanningToolPresentation.present(source,root).getAsJsonArray("imageEvidence").size());
 }
}
