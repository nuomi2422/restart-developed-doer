package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** In-game read-only eye for the external monitoring station's Numen JSONL stream. */
public final class MonitoringScreen extends Screen {
    private final List<String> lines = new ArrayList<>();
    private int scroll;
    private long nextRead;

    public MonitoringScreen() {
        super(Component.literal("RDD Monitoring Station"));
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new MonitoringScreen());
    }

    @Override
    protected void init() {
        reload();
    }

    @Override
    public void tick() {
        if (System.currentTimeMillis() >= nextRead) {
            reload();
        }
    }

    private void reload() {
        nextRead = System.currentTimeMillis() + 1000L;
        lines.clear();
        Path dir = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.CONFIG_ROOT).resolve("monitor");
        lines.add("source: " + dir);
        lines.add("mode: IN-GAME READ ONLY");
        lines.add("");
        readTail(dir.resolve("events.jsonl"), "EVENTS");
        readTail(dir.resolve("state.jsonl"), "STATE");
        readTail(dir.resolve("tools.jsonl"), "TOOLS");
    }

    private void readTail(Path file, String title) {
        lines.add("[" + title + "]");
        try {
            if (!Files.exists(file)) {
                lines.add("waiting for Numen: " + file.getFileName());
                return;
            }
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - 8);
            for (int i = from; i < all.size(); i++) {
                String text = all.get(i);
                if (text.length() > 118) text = text.substring(0, 118) + "...";
                lines.add(text);
            }
        } catch (IOException ex) {
            lines.add("read failed: " + ex.getMessage());
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = Math.max(12, (width - 760) / 2);
        int top = 18;
        int right = Math.min(width - 12, left + 760);
        graphics.fill(left, top, right, height - 18, 0xE8121620);
        graphics.fill(left, top, right, top + 25, 0xFF202A36);
        graphics.drawString(font, "RDD MONITORING STATION · AUI / NUMEN", left + 10, top + 8, 0xFFF0B92D, false);
        graphics.drawString(font, "M = toggle · ESC = close · refresh 1s", right - 190, top + 8, 0xFF9BA8B5, false);

        int y = top + 37;
        int max = Math.max(0, lines.size() - 1);
        int start = Math.min(scroll, max);
        for (int i = start; i < lines.size() && y < height - 32; i++) {
            String line = lines.get(i);
            int color = line.startsWith("[") ? 0xFF69AD45 : line.startsWith("waiting") ? 0xFFF0B92D : 0xFFD4D9E0;
            graphics.drawString(font, line, left + 10, y, color, false);
            y += 10;
        }
        graphics.drawString(font, "JSONL 分区日志 · 外部浏览器与游戏内读取同一实例目录", left + 10, height - 28, 0xFF7E8996, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - (int) scrollY * 3);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
