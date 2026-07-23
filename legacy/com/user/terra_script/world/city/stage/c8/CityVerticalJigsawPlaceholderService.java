package com.user.terra_script.world.city.stage.c8;

public final class CityVerticalJigsawPlaceholderService {
    private static final String PENDING_REASON = "vertical_jigsaw_solver_pending";

    private CityVerticalJigsawPlaceholderService() {}

    public static CityVanillaJigsawAdapterService.SolveResult pending(
            CityVanillaJigsawAdapterService.SolveResult result,
            String summaryZh
    ) {
        CityVanillaJigsawAdapterService.SolveResult out = result != null
                ? result
                : new CityVanillaJigsawAdapterService.SolveResult();
        if (out.debug == null) out.debug = new CityVanillaJigsawAdapterService.DebugDetails();
        out.reject_reason = PENDING_REASON;
        out.ok = false;
        out.warnings.add("vertical_jigsaw_routed_to_placeholder_solver");
        if (out.debug.manual_attach_summary == null) {
            out.debug.manual_attach_summary = new CityVanillaJigsawAdapterService.ManualAttachSummary();
        }
        out.debug.manual_attach_summary.truth_source = "runtime_template";
        out.debug.manual_attach_summary.first_blocker_stage = "vertical_solver_pending";
        out.debug.manual_attach_summary.summary_zh = summaryZh != null && !summaryZh.isBlank()
                ? summaryZh
                : "当前 solver 仅支持水平 jigsaw，垂直 jigsaw 将由独立求解器处理。";
        out.debug.first_blocker_stage = "vertical_solver_pending";
        out.debug.vanilla_stub_generated = false;
        out.debug.piece_generated = false;
        return out;
    }
}
