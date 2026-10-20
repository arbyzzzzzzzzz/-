package com.example.villagertrader;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

/**
 * Автоторговля с жителями: в окне торговли выбираете номер сделки,
 * включаете «Авто» — мод сам повторяет сделку, пока хватает ресурсов
 * или пока сделка не заблокируется (житель исчерпал запас).
 */
public class VillagerTraderClient implements ClientModInitializer {

    /** Пауза между действиями в тиках (20 тиков = 1 секунда). */
    private static final int DELAY_TICKS = 4;

    private static boolean enabled = false;
    private static int tradeIndex = 0;
    private static int tickCounter = 0;
    private static int sessionTrades = 0;
    private static long totalTrades = 0;
    private static String status = "Выкл";

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(VillagerTraderClient::onTick);

        // Кнопки управления в окне торговли
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof MerchantScreen)) return;
            sessionTrades = 0;
            tickCounter = 0;
            enabled = false;
            status = "Выкл";

            ButtonWidget toggle = ButtonWidget.builder(Text.literal("Авто: ВЫКЛ"), b -> {
                enabled = !enabled;
                status = enabled ? "Работает" : "Выкл";
                b.setMessage(Text.literal(enabled ? "Авто: ВКЛ" : "Авто: ВЫКЛ"));
            }).dimensions(10, 10, 90, 20).build();

            ButtonWidget prev = ButtonWidget.builder(Text.literal("<"), b -> {
                if (tradeIndex > 0) tradeIndex--;
            }).dimensions(10, 34, 20, 20).build();

            ButtonWidget next = ButtonWidget.builder(Text.literal(">"), b -> {
                tradeIndex++; // верхняя граница проверяется в onTick
            }).dimensions(80, 34, 20, 20).build();

            Screens.getButtons(screen).add(toggle);
            Screens.getButtons(screen).add(prev);
            Screens.getButtons(screen).add(next);

            // Показываем номер выбранной сделки между кнопками < и >
            ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) ->
                    ctx.drawCenteredTextWithShadow(client.textRenderer,
                            Text.literal("#" + (tradeIndex + 1)), 55, 40, 0xFFFFFF));

            ScreenEvents.remove(screen).register(s -> {
                enabled = false;
                status = "Выкл";
            });
        });

        // HUD
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.of("villagertrader", "hud"),
                (ctx, tickDelta) -> renderHud(ctx));
    }

    private static void onTick(MinecraftClient client) {
        if (!enabled || client.player == null || client.interactionManager == null) return;
        if (!(client.currentScreen instanceof MerchantScreen)) return;
        if (!(client.player.currentScreenHandler instanceof MerchantScreenHandler handler)) return;

        if (++tickCounter < DELAY_TICKS) return;
        tickCounter = 0;

        TradeOfferList offers = handler.getRecipes();
        if (offers.isEmpty()) return;
        if (tradeIndex >= offers.size()) tradeIndex = offers.size() - 1;

        TradeOffer offer = offers.get(tradeIndex);
        if (offer.isDisabled()) {
            stop("Сделка заблокирована");
            return;
        }

        // Выбор сделки: сервер сам подкладывает ресурсы из инвентаря в слоты оплаты
        handler.setRecipeIndex(tradeIndex);
        handler.switchTo(tradeIndex);
        client.getNetworkHandler().sendPacket(
                new net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket(tradeIndex));

        // Слот 2 — результат. Если он пуст, значит ресурсов не хватает.
        if (!handler.getSlot(2).hasStack()) {
            stop("Не хватает ресурсов");
            return;
        }

        client.interactionManager.clickSlot(
                handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, client.player);
        sessionTrades++;
        totalTrades++;
    }

    private static void stop(String reason) {
        enabled = false;
        status = reason;
    }

    private static void renderHud(DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden) return;

        int x = 6;
        int y = 6;
        ctx.drawTextWithShadow(client.textRenderer, "Villager Trader", x, y, 0xFFFFAA00);
        ctx.drawTextWithShadow(client.textRenderer, "Статус: " + status, x, y + 11,
                enabled ? 0xFF55FF55 : 0xFFAAAAAA);
        ctx.drawTextWithShadow(client.textRenderer, "Сделка: #" + (tradeIndex + 1), x, y + 22, 0xFFFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, "За сессию: " + sessionTrades, x, y + 33, 0xFFFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, "Всего: " + totalTrades, x, y + 44, 0xFFFFFFFF);
    }
}
