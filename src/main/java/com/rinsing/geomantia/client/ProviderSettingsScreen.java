package com.rinsing.geomantia.client;

import com.rinsing.geomantia.platform.network.ProviderNetwork;
import com.rinsing.geomantia.systems.provider.application.PlayerProviderConfig;
import com.rinsing.geomantia.systems.provider.application.ProviderSettingsSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

public final class ProviderSettingsScreen extends Screen {
    private static final int PANEL = 0xE0181D23;
    private static final int BORDER = 0xFF68717A;
    private static final int TEXT = 0xFFF0F0F0;
    private static final int MUTED = 0xFFAEB3B8;
    private static final int GOOD = 0xFF69C779;
    private static final int WARN = 0xFFE5B95C;
    private static final int ERROR = 0xFFE06B6B;

    private final Screen parent;
    private String providerKind = PlayerProviderConfig.DEEPSEEK;
    private boolean enabled;
    private boolean editable;
    private boolean hasApiKey;
    private String apiKeySource = "none";
    private boolean clearStoredApiKey;
    private boolean testAfterSave;
    private String connectionState = "loading";
    private String statusMessage = "";

    private EditBox baseUrl;
    private EditBox model;
    private EditBox apiKey;
    private EditBox timeout;
    private Button providerButton;
    private Button enabledButton;
    private Button saveButton;
    private Button testButton;
    private Button clearKeyButton;

    ProviderSettingsScreen(Screen parent) {
        super(Component.translatable("gui.geomantia.provider_settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(460, width - 32);
        int left = (width - panelWidth) / 2;
        int fieldLeft = left + 132;
        int fieldWidth = panelWidth - 152;
        int y = 58;

        providerButton = addRenderableWidget(Button.builder(providerLabel(), button -> toggleProvider())
                .bounds(fieldLeft, y, fieldWidth, 20).build());
        y += 32;
        baseUrl = addRenderableWidget(new EditBox(font, fieldLeft, y, fieldWidth, 20,
                Component.translatable("gui.geomantia.provider_settings.base_url")));
        baseUrl.setMaxLength(512);
        y += 32;
        model = addRenderableWidget(new EditBox(font, fieldLeft, y, fieldWidth, 20,
                Component.translatable("gui.geomantia.provider_settings.model")));
        model.setMaxLength(160);
        y += 32;
        apiKey = addRenderableWidget(new EditBox(font, fieldLeft, y, fieldWidth, 20,
                Component.translatable("gui.geomantia.provider_settings.api_key")));
        apiKey.setMaxLength(4096);
        apiKey.setFormatter((value, offset) -> FormattedCharSequence.forward("•".repeat(value.length()), Style.EMPTY));
        y += 32;
        timeout = addRenderableWidget(new EditBox(font, fieldLeft, y, 72, 20,
                Component.translatable("gui.geomantia.provider_settings.timeout")));
        timeout.setMaxLength(3);
        timeout.setFilter(value -> value.isBlank() || value.chars().allMatch(Character::isDigit));
        enabledButton = addRenderableWidget(Button.builder(enabledLabel(), button -> {
                    enabled = !enabled;
                    button.setMessage(enabledLabel());
                }).bounds(fieldLeft + 80, y, fieldWidth - 80, 20).build());
        y += 34;
        clearKeyButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.geomantia.provider_settings.clear_key"), button -> {
                            clearStoredApiKey = true;
                            hasApiKey = false;
                            apiKey.setValue("");
                            apiKey.setHint(Component.translatable("gui.geomantia.provider_settings.key_cleared"));
                        }).bounds(fieldLeft, y, fieldWidth, 20).build());

        int controlsY = Math.min(height - 30, y + 44);
        saveButton = addRenderableWidget(Button.builder(Component.translatable("gui.geomantia.provider_settings.save"),
                        button -> save(false)).bounds(left + 20, controlsY, 92, 20).build());
        testButton = addRenderableWidget(Button.builder(Component.translatable("gui.geomantia.provider_settings.test"),
                        button -> save(true)).bounds(left + 118, controlsY, 148, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.geomantia.provider_settings.back"),
                        button -> onClose()).bounds(left + panelWidth - 92, controlsY, 72, 20).build());
        setEditable(false);
        ProviderSettingsClient.request();
    }

    void receive(ProviderSettingsSnapshot snapshot) {
        if (snapshot == null) return;
        providerKind = snapshot.providerKind();
        enabled = snapshot.enabled();
        editable = snapshot.editable();
        hasApiKey = snapshot.hasApiKey();
        apiKeySource = snapshot.apiKeySource();
        connectionState = snapshot.connectionState();
        statusMessage = snapshot.message();
        baseUrl.setValue(snapshot.baseUrl());
        model.setValue(snapshot.model());
        timeout.setValue(Integer.toString(snapshot.timeoutSeconds()));
        apiKey.setValue("");
        apiKey.setHint(Component.translatable("environment".equals(apiKeySource)
                ? "gui.geomantia.provider_settings.key_environment"
                : hasApiKey ? "gui.geomantia.provider_settings.key_saved"
                : "gui.geomantia.provider_settings.key_missing"));
        clearStoredApiKey = false;
        providerButton.setMessage(providerLabel());
        enabledButton.setMessage(enabledLabel());
        setEditable(editable);
        updateProviderFieldState();
        if (testAfterSave && "saved".equals(connectionState)) {
            testAfterSave = false;
            connectionState = "testing";
            ProviderNetwork.testConnection();
        }
    }

    private void toggleProvider() {
        providerKind = PlayerProviderConfig.DEEPSEEK.equals(providerKind)
                ? PlayerProviderConfig.CUSTOM : PlayerProviderConfig.DEEPSEEK;
        if (PlayerProviderConfig.DEEPSEEK.equals(providerKind)) {
            baseUrl.setValue(PlayerProviderConfig.DEEPSEEK_BASE_URL);
            model.setValue(PlayerProviderConfig.DEEPSEEK_VISION_MODEL);
        }
        providerButton.setMessage(providerLabel());
        updateProviderFieldState();
    }

    private void save(boolean thenTest) {
        if (!editable) return;
        int timeoutSeconds;
        try {
            timeoutSeconds = Integer.parseInt(timeout.getValue());
        } catch (NumberFormatException exception) {
            connectionState = "error";
            statusMessage = "PROVIDER_TIMEOUT_INVALID";
            return;
        }
        testAfterSave = thenTest;
        connectionState = "saving";
        ProviderNetwork.saveSettings(providerKind, enabled, baseUrl.getValue(), model.getValue(),
                timeoutSeconds, apiKey.getValue(), clearStoredApiKey);
    }

    private void setEditable(boolean value) {
        if (providerButton == null) return;
        providerButton.active = value;
        enabledButton.active = value;
        saveButton.active = value;
        testButton.active = value;
        clearKeyButton.active = value && hasApiKey && "stored".equals(apiKeySource);
        apiKey.setEditable(value);
        timeout.setEditable(value);
        updateProviderFieldState();
    }

    private void updateProviderFieldState() {
        if (baseUrl == null) return;
        boolean custom = PlayerProviderConfig.CUSTOM.equals(providerKind);
        baseUrl.setEditable(editable && custom);
        model.setEditable(editable && custom);
    }

    private Component providerLabel() {
        return Component.translatable("gui.geomantia.provider_settings.provider_value." + providerKind);
    }

    private Component enabledLabel() {
        return Component.translatable(enabled
                ? "gui.geomantia.provider_settings.enabled"
                : "gui.geomantia.provider_settings.disabled");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int panelWidth = Math.min(460, width - 32);
        int left = (width - panelWidth) / 2;
        int right = left + panelWidth;
        graphics.fill(left, 24, right, Math.min(height - 8, 320), BORDER);
        graphics.fill(left + 1, 25, right - 1, Math.min(height - 9, 319), PANEL);
        graphics.drawCenteredString(font, title, width / 2, 34, TEXT);
        int labelX = left + 20;
        int y = 64;
        drawLabel(graphics, "gui.geomantia.provider_settings.provider", labelX, y);
        y += 32;
        drawLabel(graphics, "gui.geomantia.provider_settings.base_url", labelX, y);
        y += 32;
        drawLabel(graphics, "gui.geomantia.provider_settings.model", labelX, y);
        y += 32;
        drawLabel(graphics, "gui.geomantia.provider_settings.api_key", labelX, y);
        y += 32;
        drawLabel(graphics, "gui.geomantia.provider_settings.timeout", labelX, y);
        graphics.drawString(font, statusComponent(), labelX, 252, statusColor(), false);
        if (!editable && !"loading".equals(connectionState)) {
            graphics.drawString(font, Component.translatable("gui.geomantia.provider_settings.admin_only"),
                    labelX, 268, WARN, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawLabel(GuiGraphics graphics, String key, int x, int y) {
        graphics.drawString(font, Component.translatable(key), x, y, MUTED, false);
    }

    private Component statusComponent() {
        if ("loading".equals(connectionState)) {
            return Component.translatable("gui.geomantia.provider_settings.loading");
        }
        String key = switch (connectionState) {
            case "connected_multimodal" -> "connected_multimodal";
            case "connected_text_only" -> "connected_text_only";
            case "testing" -> "testing";
            case "saving" -> "saving";
            case "saved" -> "saved";
            case "forbidden" -> "forbidden";
            case "missing_key" -> "missing_key";
            case "model_missing" -> "model_missing";
            case "error" -> "error";
            default -> "not_tested";
        };
        Component base = Component.translatable("gui.geomantia.provider_settings.status." + key);
        Component detail = statusDetail(statusMessage);
        return detail == null ? base : base.copy().append(Component.literal(" · ")).append(detail);
    }

    private int statusColor() {
        return switch (connectionState) {
            case "connected_multimodal", "saved" -> GOOD;
            case "error", "forbidden", "missing_key", "model_missing" -> ERROR;
            default -> WARN;
        };
    }

    private static Component statusDetail(String message) {
        if (message.isBlank() || "PROVIDER_SETTINGS_SAVED".equals(message)
                || "PROVIDER_TESTING".equals(message) || "PROVIDER_MULTIMODAL_READY".equals(message)
                || "PROVIDER_API_KEY_MISSING".equals(message)
                || "PROVIDER_MODEL_NOT_AVAILABLE".equals(message)
                || "PROVIDER_ADMIN_REQUIRED".equals(message)) return null;
        if (message.startsWith("PROVIDER_HTTP_")) {
            return Component.literal("HTTP " + message.substring("PROVIDER_HTTP_".length()));
        }
        if (message.startsWith("PROVIDER_VISION_HTTP_")) {
            return Component.literal("Vision HTTP " + message.substring("PROVIDER_VISION_HTTP_".length()));
        }
        return Component.translatable("gui.geomantia.provider_settings.message." + message.toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
