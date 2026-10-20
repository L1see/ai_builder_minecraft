package com.example.ollamabuilder;

import com.example.ollamabuilder.builder.BlockPlacer;
import com.example.ollamabuilder.chat.ChatInterceptor;
import com.example.ollamabuilder.config.ModConfig;
import com.example.ollamabuilder.ollama.OllamaClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа мода.
 *
 * <p>Один и тот же класс зарегистрирован как {@code main} (общая инициализация:
 * конфиг, серверные события, тик-обработчик размещения блоков) и как {@code client}
 * (перехват чата). Клиентский код изолирован в {@link ChatInterceptor}
 * с {@code @Environment(EnvType.CLIENT)}, поэтому на выделенном сервере
 * клиентские классы не загружаются.
 */
public final class OllamaBuilderMod implements ModInitializer, ClientModInitializer {

    /** Идентификатор мода (совпадает с {@code id} в fabric.mod.json). */
    public static final String MOD_ID = "ollama_builder";

    public static final Logger LOGGER = LoggerFactory.getLogger("OllamaBuilder");

    /** Флаг нужен, чтобы инициализация не выполнялась дважды (main + client). */
    private static volatile boolean commonInitialized = false;

    @Override
    public void onInitialize() {
        initCommon();
    }

    @Override
    public void onInitializeClient() {
        // На клиенте общая инициализация тоже должна быть выполнена.
        initCommon();

        // Клиентская часть: перехват сообщений чата.
        ChatInterceptor.register();
        LOGGER.info("[OllamaBuilder] Клиентская инициализация завершена");
    }

    /** Общая инициализация: конфиг, серверные события, shutdown-хук. */
    private static synchronized void initCommon() {
        if (commonInitialized) {
            return;
        }
        commonInitialized = true;

        // 1. Конфиг: создаётся при первом запуске со значениями по умолчанию.
        ModConfig config = ModConfig.load();
        LOGGER.info("[OllamaBuilder] Инициализация. Файл конфига: {}", ModConfig.file());
        LOGGER.info("[OllamaBuilder] Каталог построек: {}", FabricLoader.getInstance().getGameDir().resolve("ollama_builds"));
        LOGGER.info("[OllamaBuilder] host={} model={} blocksPerTick={} maxBlocks={} autoBuild={}",
                config.normalizedHost(), config.model(), config.blocksPerTick(), config.maxBlocks(), config.autoBuild());

        // 2. Тик-обработчик: размещение блоков строго на серверном потоке.
        ServerTickEvents.END_SERVER_TICK.register(BlockPlacer::onServerTick);

        // 3. Жизненный цикл сервера: запоминаем/забываем сервер.
        ServerLifecycleEvents.SERVER_STARTED.register(BlockPlacer::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(BlockPlacer::onServerStopping);

        // 4. Подключения игроков: чтобы знать, кому слать сообщения о прогрессе.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> BlockPlacer.updateActivePlayer(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> BlockPlacer.updateActivePlayer(null));

        // 5. Shutdown-хук: корректно гасим сетевой поток и отменяем активный запрос.
        Runtime.getRuntime().addShutdownHook(new Thread(OllamaBuilderMod::shutdown, "OllamaBuilder-Shutdown"));
    }

    /** Остановка мода: отменяем запрос и закрываем сетевой исполнитель. */
    private static void shutdown() {
        try {
            LOGGER.info("[OllamaBuilder] Остановка: отменяю активный запрос и закрываю сетевой поток");
            OllamaClient.shutdown();
        } catch (RuntimeException e) {
            LOGGER.warn("[OllamaBuilder] Ошибка при остановке", e);
        }
    }
}
