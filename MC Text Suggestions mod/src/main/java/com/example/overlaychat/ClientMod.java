package com.example.overlaychat;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public class ClientMod implements ClientModInitializer {

    private final NativeEditManager editManager = new NativeEditManager();
    private boolean editActive = false;
    private Thread pollThread;
    // debounce counter to avoid destroying overlay on transient screen changes
    private int missingChatCount = 0;
    private boolean wasF12Down = false;

    @Override
    public void onInitializeClient() {
        // Ensure config is loaded
        ModConfig.load();

        // F12 handling will be scheduled to run on the client thread from the poller below

        // Simple polling thread to detect when the chat screen opens/closes.
        pollThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client != null) {
                        // schedule a main-thread check for F12 (GLFW input must be read on the game thread)
                        try {
                            client.execute(() -> {
                                try {
                                    if (client.getWindow() == null) return;
                                    boolean f12Down = InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_F12);
                                    if (f12Down && !wasF12Down) {
                                        ModConfig.toggle();
                                        boolean enabled = ModConfig.isEnabled();
                                        if (client.player != null) client.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, enabled ? 1.2F : 0.8F);
                                        if (client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                                            client.inGameHud.getChatHud().addMessage(Text.literal("Text Suggestion: " + (enabled ? "ON" : "OFF")));
                                        }
                                        if (!enabled && editActive) {
                                            try { editManager.destroy(); } catch (Throwable ignored) {}
                                            editActive = false;
                                            missingChatCount = 0;
                                        }
                                    }
                                    wasF12Down = f12Down;
                                } catch (Throwable t) { t.printStackTrace(); }
                            });
                        } catch (Throwable t) { t.printStackTrace(); }

                        if (client.currentScreen instanceof ChatScreen) {
                            missingChatCount = 0;
                            if (!editActive && ModConfig.isEnabled()) {
                                editManager.createAndFocus();
                                editActive = editManager.isOverlayActive();
                            }
                        } else {
                            // increment missed count and only destroy after a short debounce
                            if (editActive) {
                                missingChatCount++;
                                if (missingChatCount >= 4) { // ~200ms
                                    editManager.destroy();
                                    editActive = false;
                                    missingChatCount = 0;
                                }
                            }
                        }
                    }
                    Thread.sleep(50);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }, "overlay-chat-poller");
        pollThread.setDaemon(true);
        pollThread.start();
    }
}
