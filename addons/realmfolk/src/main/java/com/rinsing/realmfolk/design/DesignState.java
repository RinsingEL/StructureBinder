package com.rinsing.realmfolk.design;

import java.util.*;

/** Stored with the world. Drafts and receipts survive restarts; publication is one server-thread transaction. */
public final class DesignState {
    public int revision;
    public Design active;
    public Map<String,Draft> drafts = new LinkedHashMap<>();
    public Map<String,Receipt> receipts = new LinkedHashMap<>();
    public Map<String,PlanningReceipt> planningReceipts = new LinkedHashMap<>();
    public Map<String,CityWorksheet.Work> worksheets = new LinkedHashMap<>();
    public Map<String,String> worksheetReviewTokens = new LinkedHashMap<>();
    public static class Draft {
        public String id, scope;
        public int baseRevision;
        public Design candidate;
        public List<String> towns;
        public String planningKey, taskRevision;
        public com.google.gson.JsonObject worksheetReview;
    }
    public record Receipt(String draftId, int revision, int created, int refreshed, String previewToken) {}
    public record PlanningReceipt(String taskRevision, String draftId, int revision) {}
}
