package com.user.terra_script.server.mcp.protocol;

public final class ErrorResponse {
    public final String error;

    public ErrorResponse(String error) {
        this.error = error == null ? "unknown_error" : error;
    }
}
