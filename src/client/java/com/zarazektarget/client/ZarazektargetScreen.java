package com.zarazektarget;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;

public class ZarazekTargetScreen extends Screen {

    public ZarazekTargetScreen() {
        super(Text.literal("zarazekTarget"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        addField(cx - 60, cy - 10, ZarazekTargetClient.posX, v -> ZarazekTargetClient.posX = v);
        addField(cx + 10, cy - 10, ZarazekTargetClient.posY, v -> ZarazekTargetClient.posY = v);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Gotowe"), b -> this.close())
                .dimensions(cx - 50, cy + 20, 100, 20).build());
    }

    private void addField(int x, int y, int current, IntConsumer setter) {
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
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("zarazekTarget"), cx, cy - 45, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Pozycja panelu"), cx, cy - 30, 0xFFAAAAAA);
        context.drawTextWithShadow(this.textRenderer, Text.literal("X"), cx - 60, cy - 22, 0xFFFFFFFF);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Y"), cx + 10, cy - 22, 0xFFFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
