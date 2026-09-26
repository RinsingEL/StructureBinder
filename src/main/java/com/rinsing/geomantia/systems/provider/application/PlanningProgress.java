package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonObject;
import java.util.Set;

/** Player-facing progress: queue counts are not a prediction of the final world city roster. */
public record PlanningProgress(String title, float fraction, Tone tone, boolean visible, boolean complete) {
    public enum Tone { NORMAL, WAITING, ERROR, COMPLETE }
    public static PlanningProgress hidden() { return new PlanningProgress("",0,Tone.NORMAL,false,false); }
    public static PlanningProgress unavailable() {
        return new PlanningProgress("规划进度暂时无法读取 · 正在重试",0,Tone.ERROR,true,false);
    }
    public static PlanningProgress from(JsonObject state) {
        String stage=text(state,"stage"), status=text(state,"status");
        if (stage.isBlank() || stage.equals("W")) return hidden();
        if (status.equals("complete")) return new PlanningProgress("世界规划已完成",1,Tone.COMPLETE,true,true);
        String label=switch(stage) {
            case "T1" -> "规划国度设定";
            case "T2" -> "选择国度核心";
            case "T3" -> "划分国度领土";
            case "T4" -> "确定城市名册";
            case "QUEUE_REFRESH" -> "整理城市队列";
            case "CITY" -> text(state,"nextAction").equals("city_submit_d4_blueprint") ? "设计城市蓝图" : "城市选址与设计";
            case "EXTENSION" -> "规划附属内容";
            default -> "准备城市生成方案";
        };
        int done=count(state,"completedCityCount"), remaining=count(state,"remainingCityCount");
        long total=(long)done+remaining;
        float fraction=total>0 ? (float)((double)done/total) : 0;
        if(total>0) label+=" · 当前队列已准备 "+done+"/"+total;
        String role=text(state,"requiredRole");
        String local=text(state,role.equals("FLASH") ? "embeddedFlashStatus" : "embeddedAdvancedStatus");
        String queue=text(state,"cityQueueStatus");
        if(status.equals("blocked")) return new PlanningProgress(label+" · 规划出错，等待处理",fraction,Tone.ERROR,true,false);
        if(!status.equals("running") && text(state,"owner").isBlank()
                && Set.of("missing_key","missing_config","error").contains(local))
            return new PlanningProgress(label+" · AI 配置或运行异常，等待处理",fraction,Tone.ERROR,true,false);
        if(queue.equals("design_saved")) return new PlanningProgress(label+" · 设计已保存，等待继续",fraction,Tone.WAITING,true,false);
        boolean working=status.equals("running") || !text(state,"owner").isBlank()
                || Set.of("queued","running","post_d4_running").contains(queue);
        return new PlanningProgress(label+(working ? " · 进行中" : " · 等待继续"),fraction,
                working ? Tone.NORMAL : Tone.WAITING,true,false);
    }
    private static String text(JsonObject value,String key) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : "";
    }
    private static int count(JsonObject value,String key) {
        return value.has(key) ? Math.max(0,value.get(key).getAsInt()) : 0;
    }
}
