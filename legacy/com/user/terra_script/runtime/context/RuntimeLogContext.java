package com.user.terra_script.runtime.context;

public final class RuntimeLogContext {
    public final String source;
    public final String domain;
    public final String scope;
    public final String taskId;
    public final String stageId;
    public final String cityId;
    public final String territoryId;

    private RuntimeLogContext(Builder builder) {
        this.source = valueOrDefault(builder.source, "java_mod");
        this.domain = valueOrDefault(builder.domain, "runtime");
        this.scope = valueOrDefault(builder.scope, "general");
        this.taskId = normalize(builder.taskId);
        this.stageId = normalize(builder.stageId);
        this.cityId = normalize(builder.cityId);
        this.territoryId = normalize(builder.territoryId);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .source(source)
                .domain(domain)
                .scope(scope)
                .taskId(taskId)
                .stageId(stageId)
                .cityId(cityId)
                .territoryId(territoryId);
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public static final class Builder {
        private String source;
        private String domain;
        private String scope;
        private String taskId;
        private String stageId;
        private String cityId;
        private String territoryId;

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder domain(String domain) {
            this.domain = domain;
            return this;
        }

        public Builder scope(String scope) {
            this.scope = scope;
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder stageId(String stageId) {
            this.stageId = stageId;
            return this;
        }

        public Builder cityId(String cityId) {
            this.cityId = cityId;
            return this;
        }

        public Builder territoryId(String territoryId) {
            this.territoryId = territoryId;
            return this;
        }

        public RuntimeLogContext build() {
            return new RuntimeLogContext(this);
        }
    }
}
