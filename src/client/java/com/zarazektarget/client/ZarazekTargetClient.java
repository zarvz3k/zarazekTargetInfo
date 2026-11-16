package com.zarazektarget;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerModelPart;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
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

    // animacje
    private static final long IN_MS = 250;
    private static final long OUT_MS = 250;
    private static float progress = 0.0f; // 0..1, surowy postep animacji
    private static long lastFrame = 0;
    private static float alpha = 1.0f;

    public static final float SCALE_MIN = 0.6f;
    public static final float SCALE_MAX = 1.5f;

    // ustawienia (zmieniane w /zt config, zapisywane do pliku)
    public static int posX = 115;
    public static int posY = 70;
    public static float scale = 1.0f;
    public static int barColor = 0x666666;      // RGB
    public static boolean dynamicColor = true;  // kolor zalezny od HP
    public static int enchStyle = 1;            // 0 = S5, 1 = Sharp5, 2 = Sharpness 5

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
        p.setProperty("enchStyle", String.valueOf(enchStyle));
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
            enchStyle = Math.max(0, Math.min(2, Integer.parseInt(p.getProperty("enchStyle", String.valueOf(enchStyle)).trim())));
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

    private static int fade(int color) {
        int a = (int) (((color >>> 24) & 0xFF) * alpha);
        return (a << 24) | (color & 0xFFFFFF);
    }

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
        int ih = h - 2 * b;

        int[] outer = new int[h];
        int[] inner = new int[h];
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

    private static void roundedRect(DrawContext ctx, float x, float y, float w, float h, float r, int color) {
        ctx.getMatrices().push();
        ctx.getMatrices().scale(1.0f / K, 1.0f / K, 1.0f);
        fillSub(ctx, Math.round(x * K), Math.round(y * K), Math.round(w * K), Math.round(h * K),
                Math.round(r * K), fade(color));
        ctx.getMatrices().pop();
    }

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
        long dt = lastFrame == 0 ? 0 : Math.min(100, now - lastFrame);
        lastFrame = now;

        AbstractClientPlayerEntity found = findTarget(client);
        if (found != null) {
            lastTarget = found;
            lastSeen = now;
        }
        if (lastTarget == null) {
            progress = 0.0f;
            return;
        }
        if (lastTarget.isRemoved() || !lastTarget.isAlive()) {
            lastTarget = null;
            progress = 0.0f;
            return;
        }

        boolean active = now - lastSeen <= HOLD_MS;
        if (active) {
            progress = Math.min(1.0f, progress + dt / (float) IN_MS);
        } else {
            progress = Math.max(0.0f, progress - dt / (float) OUT_MS);
            if (progress <= 0.0f) {
                lastTarget = null;
                return;
            }
        }

        AbstractClientPlayerEntity target = lastTarget;

        float anim = 1.0f - (float) Math.pow(1.0f - progress, 3);
        alpha = anim;
        if (anim < 0.01f) return;

        float s = Math.max(SCALE_MIN, Math.min(SCALE_MAX, scale));
        int x = Math.max(0, Math.min(posX, ctx.getScaledWindowWidth() - Math.round(W * s)));
        int y = Math.max(0, Math.min(posY, ctx.getScaledWindowHeight() - Math.round(H * s)));

        float cx = x + W * s / 2.0f;
        float cy = y + H * s / 2.0f;
        float zs = s * anim;

        ctx.getMatrices().push();
        ctx.getMatrices().translate(cx, cy, 0);
        ctx.getMatrices().scale(zs, zs, 1.0f);
        ctx.getMatrices().translate(-W / 2.0f, -H / 2.0f, 0);

        roundedRect(ctx, 0, 0, W, H, PANEL_R, PANEL_COLOR);

        slot(ctx, client, 6, 5, target.getEquippedStack(EquipmentSlot.HEAD));
        slot(ctx, client, 26, 5, target.getEquippedStack(EquipmentSlot.CHEST));
        slot(ctx, client, 6, 25, target.getEquippedStack(EquipmentSlot.FEET));
        slot(ctx, client, 26, 25, target.getEquippedStack(EquipmentSlot.LEGS));

        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        drawFace(ctx, target, 48, 6, FACE);
        ctx.draw();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        slot(ctx, client, 88, 15, target.getMainHandStack());
        slot(ctx, client, 108, 15, target.getOffHandStack());

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

        if (alpha > 0.1f) {
            String label = String.format("%.1f", hp + absorption);
            ctx.drawCenteredTextWithShadow(client.textRenderer, Text.literal(label),
                    bx + bw / 2, by + 2, fade(0xFFFFFFFF));
        }

        ctx.getMatrices().pop();
    }

    private static void drawFace(DrawContext ctx, AbstractClientPlayerEntity target, int x, int y, int size) {
        Identifier tex = target.getSkinTextures().texture();
        ctx.drawTexture(RenderLayer::getGuiTextured, tex, x, y, 8.0f, 8.0f, size, size, 8, 8, 64, 64);

        if (target.isPartVisible(PlayerModelPart.HAT)) {
            int pad = 2;
            ctx.drawTexture(RenderLayer::getGuiTextured, tex, x - pad, y - pad, 40.0f, 8.0f,
                    size + 2 * pad, size + 2 * pad, 8, 8, 64, 64);
        }
    }

    // slot: ramka, item, ilosc/zuzycie (vanilla) + enchanty
    private static void slot(DrawContext ctx, MinecraftClient client, int x, int y, ItemStack stack) {
        roundedBorder(ctx, x, y, 18, 18, 4, 1.0f, SLOT_BORDER);
        if (stack.isEmpty()) return;
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        ctx.drawItem(stack, x + 1, y + 1);
        ctx.drawStackOverlay(client.textRenderer, stack, x + 1, y + 1);
        ctx.draw();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        // enchanty: male napisy w lewym gornym rogu slotu
        if (alpha > 0.1f) {
            ItemEnchantmentsComponent comp = stack.getOrDefault(DataComponentTypes.ENCHANTMENTS,
                    ItemEnchantmentsComponent.DEFAULT);
            if (comp.isEmpty()) return;
            String[] lines = {
                    enchLine(comp, Enchantments.SHARPNESS, "S", "Sharp", "Sharpness"),
                    enchLine(comp, Enchantments.EFFICIENCY, "E", "Eff", "Efficiency"),
                    enchLine(comp, Enchantments.PROTECTION, "P", "Prot", "Protection"),
                    enchLine(comp, Enchantments.UNBREAKING, "U", "Ub", "Unbreaking")
            };
            ctx.getMatrices().push();
            ctx.getMatrices().translate(x + 1, y + 1.5f, 200);
            ctx.getMatrices().scale(0.5f, 0.5f, 1.0f);
            int ly = 0;
            for (String line : lines) {
                if (line == null) continue;
                ctx.drawText(client.textRenderer, line, 0, ly, fade(0xFFE6B8FF), true);
                ly += 9;
            }
            ctx.getMatrices().pop();
        }
    }

    private static int levelOf(ItemEnchantmentsComponent comp, RegistryKey<Enchantment> key) {
        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> e : comp.getEnchantmentEntries()) {
            if (e.getKey().matchesKey(key)) return e.getIntValue();
        }
        return 0;
    }

    private static String enchLine(ItemEnchantmentsComponent comp, RegistryKey<Enchantment> key,
                                   String shortName, String midName, String longName) {
        int lvl = levelOf(comp, key);
        if (lvl <= 0) return null;
        return switch (enchStyle) {
            case 0 -> shortName + lvl;
            case 2 -> longName + " " + lvl;
            default -> midName + lvl;
        };
    }
}
