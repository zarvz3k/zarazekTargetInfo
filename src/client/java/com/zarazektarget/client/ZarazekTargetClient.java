package com.zarazektarget;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

public class ZarazekTargetClient implements ClientModInitializer {

    // wymiary panelu (przed skalowaniem)
    private static final int W = 132;
    private static final int H = 72;
    private static final int FACE = 36;

    // zasieg celowania (4 kratki) i margines wokol hitboxa
    private static final double REACH = 4.0;
    private static final double MARGIN = 0.5;
    private static final long HOLD_MS = 1500;

    public static final float SCALE_MIN = 0.6f;
    public static final float SCALE_MAX = 1.5f;

    // ustawienia (zmieniane w /zt config)
    public static int posX = 5;
    public static int posY = 5;
    public static float scale = 1.0f;
    public static int barColor = 0x2DD4BF;      // RGB
    public static boolean dynamicColor = false; // kolor zalezny od HP

    private static boolean openConfig = false;
    private static AbstractClientPlayerEntity lastTarget;
    private static long lastSeen;

    @Override
    public void onInitializeClient() {
        HudRenderCallback.EVENT.register(ZarazekTargetClient::render);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("zt")
                        .then(ClientCommandManager.literal("config")
                                .executes(ctx -> {
                                    openConfig = true;
                                    return 1;
                                }))));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (openConfig) {
                openConfig = false;
                client.setScreen(new ZarazekTargetScreen());
            }
        });
    }

    private static AbstractClientPlayerEntity findTarget(MinecraftClient client) {
        Vec3d eye = client.player.getCameraPosVec(1.0f);
        Vec3d end = eye.add(client.player.getRotationVec(1.0f).multiply(REACH));

        AbstractClientPlayerEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
            if (p == client.player || p.isSpectator() || !p.isAlive()) continue;
            Optional<Vec3d> hit = p.getBoundingBox().expand(MARGIN).raycast(eye, end);
            if (hit.isEmpty()) continue;
            double d = eye.squaredDistanceTo(hit.get());
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    // zaokraglony prostokat bez nakladania sie pikseli (dziala z polprzezroczystymi kolorami)
    static void roundedRect(DrawContext ctx, int x, int y, int w, int h, int r, int color) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(r * r - dy * dy));
            ctx.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            ctx.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
        if (h - 2 * r > 0) {
            ctx.fill(x, y + r, x + w, y + h - r, color);
        }
    }

    private static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.options.hudHidden) return;

        long now = Util.getMeasuringTimeMs();
        AbstractClientPlayerEntity found = findTarget(client);
        if (found != null) {
            lastTarget = found;
            lastSeen = now;
        }
        if (lastTarget == null) return;
        if (now - lastSeen > HOLD_MS || lastTarget.isRemoved() || !lastTarget.isAlive()) {
            lastTarget = null;
            return;
        }

        AbstractClientPlayerEntity target = lastTarget;

        float s = Math.max(SCALE_MIN, Math.min(SCALE_MAX, scale));
        int x = Math.max(0, Math.min(posX, ctx.getScaledWindowWidth() - Math.round(W * s)));
        int y = Math.max(0, Math.min(posY, ctx.getScaledWindowHeight() - Math.round(H * s)));

        ctx.getMatrices().push();
        ctx.getMatrices().translate(x, y, 0);
        ctx.getMatrices().scale(s, s, 1.0f);

        // tlo bez ramki, zaokraglone rogi
        roundedRect(ctx, 0, 0, W, H, 6, 0xB0101418);

        // zbroja po lewej (2x2)
        slot(ctx, client, 6, 6, target.getEquippedStack(EquipmentSlot.HEAD));
        slot(ctx, client, 24, 6, target.getEquippedStack(EquipmentSlot.CHEST));
        slot(ctx, client, 6, 24, target.getEquippedStack(EquipmentSlot.FEET));
        slot(ctx, client, 24, 24, target.getEquippedStack(EquipmentSlot.LEGS));

        // twarz
        PlayerSkinDrawer.draw(ctx, target.getSkinTextures(), 48, 6, FACE);

        // mainhand i offhand
        slot(ctx, client, 90, 15, target.getMainHandStack());
        slot(ctx, client, 108, 15, target.getOffHandStack());

        // pasek hp
        float hp = target.getHealth();
        float max = Math.max(1.0f, target.getMaxHealth());
        float absorption = target.getAbsorptionAmount();
        float frac = Math.min(1.0f, Math.max(0.0f, hp / max));

        int bx = 6;
        int by = 54;
        int bw = W - 12;
        int bh = 12;
        roundedRect(ctx, bx, by, bw, bh, 4, 0xFF2A2F33);

        int fillW = (int) (bw * frac);
        int rgb;
        if (dynamicColor) {
            rgb = frac > 0.5f ? barColor : (frac > 0.25f ? 0xF5C542 : 0xE5484D);
        } else {
            rgb = barColor;
        }
        if (fillW > 0) {
            roundedRect(ctx, bx, by, fillW, bh, Math.min(4, fillW / 2), 0xFF000000 | rgb);
        }

        String label = String.format("%.1f", hp + absorption);
        ctx.drawCenteredTextWithShadow(client.textRenderer, Text.literal(label),
                bx + bw / 2, by + 2, 0xFFFFFFFF);

        ctx.getMatrices().pop();
    }

    private static void slot(DrawContext ctx, MinecraftClient client, int x, int y, ItemStack stack) {
        roundedRect(ctx, x, y, 18, 18, 3, 0x55000000);
        if (stack.isEmpty()) return;
        ctx.drawItem(stack, x + 1, y + 1);
        ctx.drawStackOverlay(client.textRenderer, stack, x + 1, y + 1);
    }
}
