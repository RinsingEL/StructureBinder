import java.nio.file.*;
import com.google.gson.*;
import com.rinsing.geomantia.systems.city.application.CityDesignFailureFeedback;
try {
var evidence = Path.of("dev_docs/systems/city/active/20260907_设计优先实测");
var request = JsonParser.parseString(Files.readString(evidence.resolve("10_submit_capital_irrigated.json"))).getAsJsonObject();
var blueprint = request.getAsJsonObject("params").getAsJsonObject("arguments").getAsJsonObject("cityBlueprint");
var trace = JsonParser.parseString(Files.readString(evidence.resolve("10_geometry_rejection_trace.json"))).getAsJsonObject();
var feedback = CityDesignFailureFeedback.summarize(blueprint, trace, trace.get("reasonCode").getAsString());
var failure = feedback.getAsJsonArray("failures").get(0).getAsJsonObject();
if (!failure.get("groupId").getAsString().equals("g_market_district") || !failure.get("structureRef").getAsString().endsWith("flower_shop_01") || !failure.get("fieldPath").getAsString().equals("$.groups[1]") || failure.get("attemptCount").getAsInt()!=144) throw new AssertionError(feedback);
Files.writeString(Path.of("dev_docs/systems/city/archive/4315d5f_v0.1.0_D4局部反馈与草稿修订/真实失败反馈回放.json"),new GsonBuilder().setPrettyPrinting().create().toJson(feedback));
System.out.println("CAPTURED_TRACE_REPLAY_OK: market flower shop, 144 attempts; no invented capacity limit.");
} catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
/exit
