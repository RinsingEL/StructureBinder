package com.rinsing.geomantia.client;

import com.rinsing.geomantia.platform.mcp.McpServerService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Local listener settings are available from the Mods screen before entering a world. */
@Mod.EventBusSubscriber(modid = "geomantia", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class McpSettingsScreen extends Screen {
    public static java.util.function.BiConsumer<Screen,com.rinsing.geomantia.systems.provider.application.PlanningRole> providerSettingsOpener;
    private final Screen parent;
    private EditBox port, flashPort;
    private boolean enabled, initialized, pending, embeddedAdvanced, embeddedFlash;
    private Button advancedToggle,flashToggle,advancedConfig,flashConfig;
    private String portValue = "5001", feedback = "";
    private String flashPortValue = "5002";
    private Button toggle, save, copy;
    public McpSettingsScreen(Screen parent) { super(Component.translatable("gui.geomantia.mcp.title")); this.parent = parent; }
    @SubscribeEvent public static void register(FMLClientSetupEvent event) {
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new McpSettingsScreen(parent)));
    }
    @Override protected void init() {
        var status = McpServerService.instance().snapshot();
        if (!initialized) { enabled = status.enabled(); portValue = Integer.toString(status.port()); flashPortValue=Integer.toString(McpServerService.instance().flashPort()); var config=McpServerService.instance().settings();embeddedAdvanced=config.embeddedAdvanced();embeddedFlash=config.embeddedFlash(); initialized = true; }
        int left = width / 2 - 150;
        toggle = addRenderableWidget(Button.builder(toggleLabel(), button -> {
            enabled = !enabled; button.setMessage(toggleLabel());
        }).bounds(left, 42, 300, 20).build());
        port = addRenderableWidget(new EditBox(font, left + 100, 68, 200, 20, Component.translatable("gui.geomantia.mcp.port")));
        port.setMaxLength(5); port.setFilter(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
        port.setValue(portValue); port.setResponder(value -> portValue = value);
        flashPort = addRenderableWidget(new EditBox(font,left+100,92,200,20,Component.literal("Flash MCP")));
        flashPort.setMaxLength(5);flashPort.setFilter(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
        flashPort.setValue(flashPortValue);flashPort.setResponder(value -> flashPortValue=value);
        copy = addRenderableWidget(Button.builder(Component.translatable("gui.geomantia.mcp.copy"), button -> {
            String url = McpServerService.instance().snapshot().url();
            if (!url.isBlank()) { minecraft.keyboardHandler.setClipboard(url); feedback = Component.translatable("gui.geomantia.mcp.copied").getString(); }
        }).bounds(left, 118, 146, 20).build());
        addRenderableWidget(Button.builder(Component.literal("复制 Flash URL"),button -> {
            String url=McpServerService.instance().flashUrl();if(!url.isBlank())minecraft.keyboardHandler.setClipboard(url);
        }).bounds(left+154,118,146,20).build());
        advancedToggle=addRenderableWidget(Button.builder(roleLabel(true),b->{embeddedAdvanced=!embeddedAdvanced;b.setMessage(roleLabel(true));}).bounds(left,144,196,20).build());
        flashToggle=addRenderableWidget(Button.builder(roleLabel(false),b->{embeddedFlash=!embeddedFlash;b.setMessage(roleLabel(false));}).bounds(left,168,196,20).build());
        advancedConfig=addRenderableWidget(Button.builder(Component.literal("高级 API 配置"),b->save(()->providerSettingsOpener.accept(new McpSettingsScreen(parent),com.rinsing.geomantia.systems.provider.application.PlanningRole.ADVANCED))).bounds(left+204,144,96,20).build());
        flashConfig=addRenderableWidget(Button.builder(Component.literal("Flash API 配置"),b->save(()->providerSettingsOpener.accept(new McpSettingsScreen(parent),com.rinsing.geomantia.systems.provider.application.PlanningRole.FLASH))).bounds(left+204,168,96,20).build());
        save = addRenderableWidget(Button.builder(Component.translatable("gui.geomantia.mcp.save"), button -> save())
                .bounds(left, height - 32, 196, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(left + 204, height - 32, 96, 20).build());
        updateControls();
    }
    private Component toggleLabel() { return Component.translatable(enabled ? "gui.geomantia.mcp.enabled" : "gui.geomantia.mcp.disabled"); }
    private Component roleLabel(boolean advanced) { return Component.literal((advanced ? "高级模型" : "Flash")+"：内置接管 "+((advanced ? embeddedAdvanced : embeddedFlash) ? "开" : "关")); }
    private void save() { save(null); }
    private void save(Runnable afterSave) {
        int number;
        try { number = Integer.parseInt(port.getValue()); new com.rinsing.geomantia.platform.mcp.McpServerConfig(enabled, number); }
        catch (IllegalArgumentException ex) { feedback = Component.translatable("gui.geomantia.mcp.invalid_port").getString(); return; }
        pending = true; feedback = ""; updateControls();
        int flashNumber;try { flashNumber=Integer.parseInt(flashPortValue); }catch(NumberFormatException ex){pending=false;feedback="Flash MCP 端口无效";updateControls();return;}
        McpServerService.instance().save(enabled, number,flashNumber,embeddedAdvanced,embeddedFlash).whenComplete((status, error) -> minecraft.execute(() -> {
            pending = false;
            feedback = error == null ? status.message() : Component.translatable("gui.geomantia.mcp.failed").getString();
            updateControls();
            if(afterSave!=null && error==null && !status.state().equals("error")) afterSave.run();
        }));
    }
    private void updateControls() {
        toggle.active = save.active = !pending;
        advancedToggle.active=flashToggle.active=!pending && providerSettingsOpener!=null;
        advancedConfig.active=flashConfig.active=!pending && providerSettingsOpener!=null; port.setEditable(!pending);flashPort.setEditable(!pending);
        copy.active = !McpServerService.instance().snapshot().url().isBlank();
    }
    @Override public void tick() { updateControls(); }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 22, 0xFFFFFF);
        graphics.drawString(font, Component.translatable("gui.geomantia.mcp.port"), width / 2 - 150, 74, 0xFFFFFF);
        graphics.drawString(font,Component.literal("Flash MCP 端口"),width/2-150,98,0xFFFFFF);
        var status = McpServerService.instance().snapshot();
        int y = 196;
        for (var line : font.split(Component.literal(status.url().isBlank() ? status.message() : status.url()), 300)) {
            if(y>=height-43) break;
            graphics.drawString(font, line, width / 2 - 150, y, status.state().equals("error") ? 0xFF7777 : 0xB8DDB8); y += 11;
        }
        for (var line : font.split(Component.translatable("gui.geomantia.mcp.hint"), 300)) {
            if(y+5>=height-43) break;
            graphics.drawString(font, line, width / 2 - 150, y + 5, 0xAAAAAA); y += 11;
        }
        if (!feedback.isBlank()) {
            for (var line : font.split(Component.literal(feedback), 300)) {
                if (y + 16 >= height - 40) break;
                graphics.drawString(font, line, width / 2 - 150, y + 10, 0xE5B95C); y += 11;
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
