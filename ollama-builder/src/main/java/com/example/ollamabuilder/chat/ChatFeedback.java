package com.example.ollamabuilder.chat;

import com.example.ollamabuilder.build.BlockPlacer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Отправка сообщений обратной связи игроку.
 *
 * <p>Класс намеренно живёт в общем (common) source set'е и работает только
 * с серверной стороной: сообщения уходят локальному игроку через
 * {@link ServerPlayerEntity#sendMessage(Text)}. Клиентская обратная связь
 * (мгновенная реакция на ввод команды) реализована в {@link ChatInterceptor},
 * чтобы на выделенном сервере клиентские классы никогда не загружались.
 *
 * <p>Все методы безопасны к вызову из любого потока: при необходимости
 * доставка переносится на серверный поток через {@code server.execute(...)}.
 */
public final class ChatFeedback {

    private static final Logger LOGGER = LoggerFactory.getLogger("OllamaBuilder/Chat");

    /** Префикс сообщений мода в чате. */
    public static final String PREFIX = "[OllamaBuilder] ";

    private ChatFeedback() {
        // Утилитарный класс, экземпляры не создаются.
    }

    /**
     * Отправляет сообщение текущему игроку. Можно вызывать из любого потока.
     *
     * @param message текст сообщения
     */
    public static void server(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }

        final Text text = Text.literal(PREFIX + message);
        final ServerPlayerEntity player = BlockPlacer.currentPlayer();

        if (player != null && !player.isRemoved()) {
            final MinecraftServer server = player.getServer();
            if (server != null && !server.isOnThread()) {
                // Переносим доставку на серверный поток.
                server.execute(() -> send(player, text));
            } else {
                send(player, text);
            }
            return;
        }

        // Игрока нет (например, он вышел из мира) — пишем хотя бы в лог.
        LOGGER.info("[OllamaBuilder] {}", message);
    }

    /** Непосредственная отправка системного сообщения игроку. */
    private static void send(ServerPlayerEntity player, Text text) {
        try {
            player.sendMessage(text);
        } catch (RuntimeException e) {
            LOGGER.error("[OllamaBuilder] Не удалось отправить сообщение игроку", e);
        }
    }
}
