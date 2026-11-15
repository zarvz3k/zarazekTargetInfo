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

    // wymiary panelu
    private static final int W = 132;
    private static final int H = 72;
    private static final int FACE = 36;

    // zasieg celowania i margines wokol hitboxa (w kratkach)
    private static final double REACH = 6.0;
    private static final double MARGIN = 0.5;
    // jak dlugo panel zostaje po odejsciu celownika od gracza
    private static final long HOLD_MS = 1500;

    // pozycja panelu (lewy gorny rog), zmieniana w /zt config
    public static int posX = 5;
    public static int posY = 5;

    private static boolean openConfig = false;
    private static AbstractClientPlayerEntity lastTarget;
    private static long lastSeen;

    @Override
    public void onInitializeClient() {
        HudRenderCallback.EVENT.register(ZarazekTargetClient::render);

        // komenda /zt config
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

    // znajduje najblizszego gracza, przez ktorego hitbox (powiekszony o MARGIN) przechodzi promien z celownika
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

        // pozycja z configu, przycieta do okna
        int x = Math.max(0, Math.min(posX, ctx.getScaledWindowWidth() - W));
        int y = Math.max(0, Math.min(posY, ctx.getScaledWindowHeight() - H));

        // tlo
        ctx.fill(x, y, x + W, y + H, 0xB0101418);
        ctx.fill(x, y, x + W, y + 1, 0x60FFFFFF);
        ctx.fill(x, y + H - 1, x + W, y + H, 0x60FFFFFF);
        ctx.fill(x, y, x + 1, y + H, 0x60FFFFFF);
        ctx.fill(x + W - 1, y, x + W, y + H, 0x60FFFFFF);

        // zbroja po lewej (2x2): helm | napiersnik / buty | spodnie
        slot(ctx, client, x + 6, y + 6, target.getEquippedStack(EquipmentSlot.HEAD));
        slot(ctx, client, x + 24, y + 6, target.getEquippedStack(EquipmentSlot.CHEST));
        slot(ctx, client, x + 6, y + 24, target.getEquippedStack(EquipmentSlot.FEET));
        slot(ctx, client, x + 24, y + 24, target.getEquippedStack(EquipmentSlot.LEGS));

        // twarz na srodku
        PlayerSkinDrawer.draw(ctx, target.getSkinTextures(), x + 48, y + 6, FACE);

        // po prawej: mainhand (lewy slot), offhand (prawy slot)
        slot(ctx, client, x + 90, y + 15, target.getMainHandStack());
        slot(ctx, client, x + 108, y + 15, target.getOffHandStack());

        // pasek hp
        float hp = target.getHealth();
        float max = Math.max(1.0f, target.getMaxHealth());
        float absorption = target.getAbsorptionAmount();
        float frac = Math.min(1.0f, Math.max(0.0f, hp / max));

        int bx = x + 6;
        int by = y + 54;
        int bw = W - 12;
        int bh = 12;
        ctx.fill(bx, by, bx + bw, by + bh, 0xFF000000);
        ctx.fill(bx + 1, by + 1, bx + bw - 1, by + bh - 1, 0xFF2A2F33);
        int fillW = (int) ((bw - 2) * frac);
        int color = frac > 0.5f ? 0xFF2DD4BF : (frac > 0.25f ? 0xFFF5C542 : 0xFFE5484D);
        ctx.fill(bx + 1, by + 1, bx + 1 + fillW, by + bh - 1, color);

        String label = String.format("%.1f", hp + absorption);
        ctx.drawCenteredTextWithShadow(client.textRenderer, Text.literal(label),
                bx + bw / 2, by + 2, 0xFFFFFFFF);
    }

    private static void slot(DrawContext ctx, MinecraftClient client, int x, int y, ItemStack stack) {
        ctx.fill(x, y, x + 18, y + 18, 0x55000000);
        ctx.fill(x, y, x + 18, y + 1, 0x30FFFFFF);
        ctx.fill(x, y, x + 1, y + 18, 0x30FFFFFF);
        if (stack.isEmpty()) return;
        ctx.drawItem(stack, x + 1, y + 1);
        ctx.drawStackOverlay(client.textRenderer, stack, x + 1, y + 1);
    }
}
