package com.zarazektarget;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;

public class ZarazekTargetScreen extends Screen {

    private static final int PANEL_W = 200;
    private static final int PANEL_H = 190;
    private static final long ANIM_MS = 500;
    private static final int TEXT = 0xFF404040;

    // animacja wjazdu od dolu
    private final long openTime = Util.getMeasuringTimeMs();
    private int yOffset = 0;
    private final Map<ClickableWidget, Integer> baseY = new HashMap<>();

    public ZarazekTargetScreen() {
        super(Text.literal("zarazekTarget"));
    }

    private <T extends ClickableWidget> T track(T widget) {
        baseY.put(widget, widget.getY());
        return this.addDrawableChild(widget);
    }

    // suwak rozmiaru 0.6x - 1.5x, co 0.05
    private static class ScaleSlider extends SliderWidget {
        ScaleSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Text.empty(),
                    (ZarazekTargetClient.scale - ZarazekTargetClient.SCALE_MIN)
                            / (ZarazekTargetClient.SCALE_MAX - ZarazekTargetClient.SCALE_MIN));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal("Rozmiar: " + String.format("%.2fx", ZarazekTargetClient.scale)));
        }

        @Override
        protected void applyValue() {
            float range = ZarazekTargetClient.SCALE_MAX - ZarazekTargetClient.SCALE_MIN;
            float raw = ZarazekTargetClient.SCALE_MIN + (float) this.value * range;
            float snapped = Math.round(raw / 0.05f) * 0.05f;
            ZarazekTargetClient.scale = Math.max(ZarazekTargetClient.SCALE_MIN,
                    Math.min(ZarazekTargetClient.SCALE_MAX, snapped));
            updateMessage();
        }
    }

    @Override
    protected void init() {
        baseY.clear();
        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        // pozycja X / Y
        addNumberField(left + 38, top + 38, ZarazekTargetClient.posX, v -> ZarazekTargetClient.posX = v);
        addNumberField(left + 122, top + 38, ZarazekTargetClient.posY, v -> ZarazekTargetClient.posY = v);

        // rozmiar
        track(new ScaleSlider(left + 10, top + 66, 180, 20));

        // kolor paska hp (hex)
        TextFieldWidget color = new TextFieldWidget(this.textRenderer, left + 65, top + 106, 70, 18,
                Text.literal("kolor"));
        color.setMaxLength(6);
        color.setTextPredicate(s -> s.matches("[0-9a-fA-F]{0,6}"));
        color.setText(String.format("%06X", ZarazekTargetClient.barColor));
        color.setChangedListener(s -> {
            if (s.length() == 6) {
                try {
                    ZarazekTargetClient.barColor = Integer.parseInt(s, 16);
                } catch (NumberFormatException ignored) {
                }
            }
        });
        track(color);

        // kolor zalezny od hp
        track(ButtonWidget.builder(dynamicText(), b -> {
            ZarazekTargetClient.dynamicColor = !ZarazekTargetClient.dynamicColor;
            b.setMessage(dynamicText());
        }).dimensions(left + 10, top + 132, 180, 20).build());

        track(ButtonWidget.builder(Text.literal("Gotowe"), b -> this.close())
                .dimensions(left + 50, top + 160, 100, 20).build());
    }

    private static Text dynamicText() {
        return Text.literal("Kolor wg HP: " + (ZarazekTargetClient.dynamicColor ? "ON" : "OFF"));
    }

    private void addNumberField(int x, int y, int current, IntConsumer setter) {
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y, 50, 18, Text.literal("pos"));
        field.setMaxLength(4);
        field.setTextPredicate(s -> s.isEmpty() || s.matches("\\d{1,4}"));
        field.setText(String.valueOf(current));
        field.setChangedListener(s -> {
            int v = 0;
            if (!s.isEmpty()) {
                try {
                    v = Integer.parseInt(s);
                } catch (NumberFormatException ignored) {
                }
            }
            setter.accept(v);
        });
        track(field);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // postep animacji 0..1 z wyhamowaniem (ease-out cubic)
        float t = Math.min(1.0f, (Util.getMeasuringTimeMs() - openTime) / (float) ANIM_MS);
        float eased = 1.0f - (float) Math.pow(1.0f - t, 3);
        int top = (this.height - PANEL_H) / 2;
        yOffset = (int) ((1.0f - eased) * (this.height - top));

        for (Map.Entry<ClickableWidget, Integer> e : baseY.entrySet()) {
            e.getKey().setY(e.getValue() + yOffset);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    // panel i napisy rysujemy tutaj, zeby widgety byly na wierzchu
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2 + yOffset;
        int right = left + PANEL_W;
        int bottom = top + PANEL_H;

        // ramka i panel w stylu vanilla
        context.fill(left, top, right, bottom, 0xFF000000);
        context.fill(left + 1, top + 1, right - 1, bottom - 1, 0xFFC6C6C6);
        context.fill(left + 1, top + 1, right - 1, top + 2, 0xFFFFFFFF);
        context.fill(left + 1, top + 1, left + 2, bottom - 1, 0xFFFFFFFF);
        context.fill(left + 1, bottom - 2, right - 1, bottom - 1, 0xFF555555);
        context.fill(right - 2, top + 1, right - 1, bottom - 1, 0xFF555555);

        int cx = this.width / 2;

        Text title = Text.literal("zarazekTarget");
        context.drawText(this.textRenderer, title, cx - this.textRenderer.getWidth(title) / 2,
                top + 10, TEXT, false);

        Text pos = Text.literal("Pozycja panelu");
        context.drawText(this.textRenderer, pos, cx - this.textRenderer.getWidth(pos) / 2,
                top + 24, TEXT, false);
        context.drawText(this.textRenderer, Text.literal("X"), left + 26, top + 43, TEXT, false);
        context.drawText(this.textRenderer, Text.literal("Y"), left + 110, top + 43, TEXT, false);

        Text hex = Text.literal("Kolor paska HP (hex)");
        context.drawText(this.textRenderer, hex, cx - this.textRenderer.getWidth(hex) / 2,
                top + 94, TEXT, false);
    }

    @Override
    public void removed() {
        ZarazekTargetClient.saveConfig();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
