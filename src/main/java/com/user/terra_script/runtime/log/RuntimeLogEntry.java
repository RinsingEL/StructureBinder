package com.user.terra_script.runtime.log;

import com.google.gson.JsonObject;

public final class RuntimeLogEntry {
    public long ts_epoch_ms;
    public long tick;
    public String level;
    public String source;
    public String domain;
    public String scope;
    public String event;
    public String message;
    public String task_id;
    public String stage_id;
    public String city_id;
    public String territory_id;
    public String thread;
    public JsonObject details;
}
