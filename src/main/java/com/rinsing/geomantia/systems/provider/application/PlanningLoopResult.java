package com.rinsing.geomantia.systems.provider.application;
public record PlanningLoopResult(boolean success,String state,String errorCode,int toolCalls,String finalText) {
    public PlanningLoopResult {
        state=state==null?"":state; errorCode=errorCode==null?"":errorCode; finalText=finalText==null?"":finalText;
    }
    public static PlanningLoopResult failure(String code,int calls,String text) { return new PlanningLoopResult(false,"error",code,calls,text); }
}
