package com.zarazektarget;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

public class ZarazekTargetClient implements ClientModInitializer {

    // wymiary panelu (przed skalowaniem)
    private static final int W = 132;
    private static final int H = 64;
    private static final int FACE = 36;

    // wyglad
    private static final int PANEL_R = 8;
    private static final int PANEL_COLOR = 0xB0101418;
    private static final int SLOT_BORDER = 0xFF555A60; // szara ramka slotow
    private static final int BAR_TRACK = 0xFF2A2F33;

    // zasieg celowania (4 kratki) i margines wokol hitboxa
    private static final double REACH = 4.0;
    private static final double MARGIN = 0.5;
    private static final long HOLD_MS = 1500;

    // animacja fade in (0.4 s)
    private static final long FADE_MS = 400;
    private static long fadeStart = 0;
    private static float alpha = 1.0f;

    public static final float SCALE_MIN = 0.6f;
    public static final float SCALE_MAX = 1.5f;

    // ustawienia (zmieniane w /zt config, zapisywane do pliku)
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
        loadConfig();

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

    // ---------------- config (plik) ----------------

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("zarazektarget.properties");
    }

    public static void saveConfig() {
        Properties p = new Properties();
        p.setProperty("posX", String.valueOf(posX));
        p.setProperty("posY", String.valueOf(posY));
        p.setProperty("scale", String.valueOf(scale));
        p.setProperty("barColor", String.format("%06X", barColor));
        p.setProperty("dynamicColor", String.valueOf(dynamicColor));
        try (OutputStream out = Files.newOutputStream(configPath())) {
            p.store(out, "zarazekTarget");
        } catch (IOException ignored) {
        }
    }

    private static void loadConfig() {
        Path path = configPath();
        if (!Files.exists(path)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
        } catch (IOException e) {
            return;
        }
        try {
            posX = Math.max(0, Integer.parseInt(p.getProperty("posX", String.valueOf(posX)).trim()));
            posY = Math.max(0, Integer.parseInt(p.getProperty("posY", String.valueOf(posY)).trim()));
            float sc = Float.parseFloat(p.getProperty("scale", String.valueOf(scale)).trim());
            scale = Math.max(SCALE_MIN, Math.min(SCALE_MAX, sc));
            barColor = Integer.parseInt(p.getProperty("barColor", String.format("%06X", barColor)).trim(), 16) & 0xFFFFFF;
            dynamicColor = Boolean.parseBoolean(p.getProperty("dynamicColor", String.valueOf(dynamicColor)).trim());
        } catch (NumberFormatException ignored) {
        }
    }

    // ---------------- celowanie ----------------

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

    // ---------------- gladkie ksztalty (siatka K razy gestsza) ----------------

    private static final int K = 4;

    // mnozy alfe koloru przez aktualny postep animacji
    private static int fade(int color) {
        int a = (int) (((color >>> 24) & 0xFF) * alpha);
        return (a << 24) | (color & 0xFFFFFF);
    }

    // przesuniecie krawedzi w wierszu j dla zaokraglonego prostokata (w pod-pikselach)
    private static int insetAt(int j, int h, int r) {
        if (r <= 0) return 0;
        int jj = (j >= h - r) ? h - 1 - j : j;
        if (jj >= r) return 0;
        double dy = r - jj - 0.5;
        return r - (int) Math.round(Math.sqrt((double) r * r - dy * dy));
    }

    private static void fillSub(DrawContext ctx, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        int j = 0;
        while (j < h) {
            int o = insetAt(j, h, r);
            int e = j + 1;
            while (e < h && insetAt(e, h, r) == o) e++;
            ctx.fill(x + o, y + j, x + w - o, y + e, color);
            j = e;
        }
    }

    private static void ringSub(DrawContext ctx, int x, int y, int w, int h, int r, int b, int color) {
        if (w <= 2 * b || h <= 2 * b) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        int ri = Math.max(0, r - b);
        int iw = w - 2 * b;
        int ih = h - 2 * b;

        int[] outer = new int[h];
        int[] inner = new int[h]; // -1 = brak wnetrza w tym wierszu
        for (int j = 0; j < h; j++) {
            outer[j] = insetAt(j, h, r);
            int jj = j - b;
            inner[j] = (jj < 0 || jj >= ih) ? -1 : b + insetAt(jj, ih, ri);
        }

        int j = 0;
        while (j < h) {
            int e = j + 1;
            while (e < h && outer[e] == outer[j] && inner[e] == inner[j]) e++;
            int o = outer[j];
            int in = inner[j];
            if (in < 0) {
                ctx.fill(x + o, y + j, x + w - o, y + e, color);
            } else {
                ctx.fill(x + o, y + j, x + in, y + e, color);
                ctx.fill(x + w - in, y + j, x + w - o, y + e, color);
            }
            j = e;
        }
    }

    // wypelniony zaokraglony prostokat; wspolrzedne w jednostkach GUI
    private static void roundedRect(DrawContext ctx, float x, float y, float w, float h, float r, int color) {
        ctx.getMatrices().push();
        ctx.getMatrices().scale(1.0f / K, 1.0f / K, 1.0f);
        fillSub(ctx, Math.round(x * K), Math.round(y * K), Math.round(w * K), Math.round(h * K),
                Math.round(r * K), fade(color));
        ctx.getMatrices().pop();
    }

    // sama ramka (srodek przezroczysty)
    private static void roundedBorder(DrawContext ctx, float x, float y, float w, float h, float r, float bw, int color) {
        ctx.getMatrices().push();
        ctx.getMatrices().scale(1.0f / K, 1.0f / K, 1.0f);
        ringSub(ctx, Math.round(x * K), Math.round(y * K), Math.round(w * K), Math.round(h * K),
                Math.round(r * K), Math.max(1, Math.round(bw * K)), fade(color));
        ctx.getMatrices().pop();
    }

    // ---------------- render ----------------

    private static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.options.hudHidden) return;

        long now = Util.getMeasuringTimeMs();
        AbstractClientPlayerEntity found = findTarget(client);
        if (found != null) {
            // panel pojawia sie od zera -> start animacji
            if (lastTarget == null) fadeStart = now;
            lastTarget = found;
            lastSeen = now;
        }
        if (lastTarget == null) return;
        if (now - lastSeen > HOLD_MS || lastTarget.isRemoved() || !lastTarget.isAlive()) {
            lastTarget = null;
            return;
        }

        AbstractClientPlayerEntity target = lastTarget;

        // postep fade in 0..1 (ease-out)
        float t = Math.min(1.0f, (now - fadeStart) / (float) FADE_MS);
        alpha = 1.0f - (1.0f - t) * (1.0f - t);

        float s = Math.max(SCALE_MIN, Math.min(SCALE_MAX, scale));
        int x = Math.max(0, Math.min(posX, ctx.getScaledWindowWidth() - Math.round(W * s)));
        int y = Math.max(0, Math.min(posY, ctx.getScaledWindowHeight() - Math.round(H * s)));

        ctx.getMatrices().push();
        ctx.getMatrices().translate(x, y, 0);
        ctx.getMatrices().scale(s, s, 1.0f);

        // tlo bez ramki, mocno zaokraglone rogi
        roundedRect(ctx, 0, 0, W, H, PANEL_R, PANEL_COLOR);

        // zbroja po lewej (2x2), odstep 2 px
        slot(ctx, client, 6, 5, target.getEquippedStack(EquipmentSlot.HEAD));
        slot(ctx, client, 26, 5, target.getEquippedStack(EquipmentSlot.CHEST));
        slot(ctx, client, 6, 25, target.getEquippedStack(EquipmentSlot.FEET));
        slot(ctx, client, 26, 25, target.getEquippedStack(EquipmentSlot.LEGS));

        // twarz (z przezroczystoscia animacji)
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        PlayerSkinDrawer.draw(ctx, target.getSkinTextures(), 48, 6, FACE);
        ctx.draw();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        // mainhand i offhand, odstep 2 px
        slot(ctx, client, 88, 15, target.getMainHandStack());
        slot(ctx, client, 108, 15, target.getOffHandStack());

        // pasek hp
        float hp = target.getHealth();
        float max = Math.max(1.0f, target.getMaxHealth());
        float absorption = target.getAbsorptionAmount();
        float frac = Math.min(1.0f, Math.max(0.0f, hp / max));

        int bx = 6;
        int by = 46;
        int bw = W - 12;
        int bh = 12;
        float rad = 3.0f;
        roundedRect(ctx, bx, by, bw, bh, rad, BAR_TRACK);

        int rgb;
        if (dynamicColor) {
            rgb = frac > 0.5f ? barColor : (frac > 0.25f ? 0xF5C542 : 0xE5484D);
        } else {
            rgb = barColor;
        }
        float fillW = bw * frac;
        if (fillW > 0.25f) {
            roundedRect(ctx, bx, by, fillW, bh, Math.min(rad, fillW / 2.0f), 0xFF000000 | rgb);
        }

        // tekst: vanilla traktuje alfe <= 3 jako pelna, wiec pomijamy go na samym poczatku animacji
        if (alpha > 0.1f) {
            String label = String.format("%.1f", hp + absorption);
            ctx.drawCenteredTextWithShadow(client.textRenderer, Text.literal(label),
                    bx + bw / 2, by + 2, fade(0xFFFFFFFF));
        }

        ctx.getMatrices().pop();
    }

    // slot: szara ramka, srodek w kolorze tla panelu
    private static void slot(DrawContext ctx, MinecraftClient client, int x, int y, ItemStack stack) {
        roundedBorder(ctx, x, y, 18, 18, 4, 1.0f, SLOT_BORDER);
        if (stack.isEmpty()) return;
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        ctx.drawItem(stack, x + 1, y + 1);
        ctx.drawStackOverlay(client.textRenderer, stack, x + 1, y + 1);
        ctx.draw();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }
}
