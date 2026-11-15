package com.zarazektarget;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;

public class ZarazekTargetScreen extends Screen {

    public ZarazekTargetScreen() {
        super(Text.literal("zarazekTarget"));
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
        int cx = this.width / 2;
        int cy = this.height / 2;

        // pozycja X / Y
        addNumberField(cx - 60, cy - 40, ZarazekTargetClient.posX, v -> ZarazekTargetClient.posX = v);
        addNumberField(cx + 10, cy - 40, ZarazekTargetClient.posY, v -> ZarazekTargetClient.posY = v);

        // rozmiar
        this.addDrawableChild(new ScaleSlider(cx - 70, cy - 12, 140, 20));

        // kolor paska hp (hex)
        TextFieldWidget color = new TextFieldWidget(this.textRenderer, cx - 35, cy + 24, 70, 18,
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
        this.addDrawableChild(color);

        // kolor zalezny od hp
        this.addDrawableChild(ButtonWidget.builder(dynamicText(), b -> {
            ZarazekTargetClient.dynamicColor = !ZarazekTargetClient.dynamicColor;
            b.setMessage(dynamicText());
        }).dimensions(cx - 70, cy + 48, 140, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Gotowe"), b -> this.close())
                .dimensions(cx - 50, cy + 74, 100, 20).build());
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
        this.addDrawableChild(field);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int cy = this.height / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("zarazekTarget"), cx, cy - 75, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Pozycja panelu"), cx, cy - 62, 0xFFAAAAAA);
        context.drawTextWithShadow(this.textRenderer, Text.literal("X"), cx - 60, cy - 50, 0xFFFFFFFF);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Y"), cx + 10, cy - 50, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Kolor paska HP (hex)"), cx, cy + 12, 0xFFAAAAAA);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
