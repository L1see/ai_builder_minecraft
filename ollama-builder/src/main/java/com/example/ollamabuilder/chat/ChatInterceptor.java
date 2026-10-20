package com.example.ollamabuilder.chat;

import com.example.ollamabuilder.build.BlockPlacer;
import com.example.ollamabuilder.config.ModConfig;
import com.example.ollamabuilder.ollama.OllamaClient;
import com.example.ollamabuilder.parse.BuildPlan;
import com.example.ollamabuilder.parse.BuildPlanParser;
import com.example.ollamabuilder.prompt.PromptBuilder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Перехват клиентского чата.
 *
 * <p>Работает через {@link ClientSendMessageEvents#ALLOW_CHAT}: если строка
 * начинается с {@code chatPrefix + " "} (по умолчанию {@code ".print "}),
 * событие отменяется — сообщение не попадает в чат и не уходит на сервер.
 * Всё, что не начинается с префикса, проходит как обычный чат.
 *
 * <p>Здесь же обрабатывается команда {@code .stop} — немедленная отмена
 * текущей сборки.
 *
 * <p>Сетевые запросы выполняются в {@link OllamaClient} на отдельном потоке,
 * поэтому клиентский поток не блокируется и игра не фризит.
 */
@Environment(EnvType.CLIENT)
public final class ChatInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger("OllamaBuilder/Chat");

    /** Префикс сообщений мода в клиентском чате. */
    private static final String FEEDBACK_PREFIX = "[OllamaBuilder] ";

    /** Команда остановки сборки. */
    private static final String STOP_COMMAND = ".stop";

    /**
     * Счётчик клиентских запросов: ответ с устаревшим токеном игнорируется
     * (запрос мог быть отменён более поздней командой).
     */
    private static final AtomicLong REQUEST_TOKEN = new AtomicLong(0);

    private ChatInterceptor() {
        // Утилитарный класс, экземпляры не создаются.
    }

    /** Регистрирует обработчики клиентских событий отправки сообщений. */
    public static void register() {
        ClientSendMessageEvents.ALLOW_CHAT.register(ChatInterceptor::onAllowChat);
        LOGGER.info("[OllamaBuilder] Перехват чата зарегистрирован (префикс: \"{}\", отмена сборки: \"{}\")",
                ModConfig.get().chatPrefix(), STOP_COMMAND);
    }

    /**
     * Обработчик {@code ALLOW_CHAT}. Возврат {@code false} отменяет отправку
     * сообщения в чат.
     *
     * @param message текст, который игрок ввёл в чат
     * @return {@code true} — отправить как обычно, {@code false} — подавить
     */
    private static boolean onAllowChat(String message) {
        if (message == null) {
            return true;
        }

        String trimmed = message.trim();
        String prefix = ModConfig.get().chatPrefix();

        // Команда остановки сборки.
        if (trimmed.equalsIgnoreCase(STOP_COMMAND)) {
            handleStop();
            return false;
        }

        // Наша команда: либо ровно префикс, либо префикс и пробел перед текстом.
        boolean isCommand = trimmed.equals(prefix) || trimmed.startsWith(prefix + " ");
        if (!isCommand) {
            // Всё остальное проходит как обычный чат — ничего не ломаем.
            return true;
        }

        String request = trimmed.length() > prefix.length()
                ? trimmed.substring(prefix.length()).trim()
                : "";
        if (request.isEmpty()) {
            client("Usage: " + prefix + " <building description>, e.g. " + prefix + " two storey house with a gable roof");
            return false;
        }

        handlePrint(request);
        return false;
    }

    /** Обрабатывает команду {@code .stop}. */
    private static void handleStop() {
        // Отменяем незавершённый HTTP-запрос, если он есть.
        OllamaClient.cancelCurrent();

        MinecraftServer server = BlockPlacer.server();
        if (server == null) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                server = client.getServer();
            }
        }
        if (server == null) {
            client("No server available");
            return;
        }

        final MinecraftServer target = server;
        target.execute(() -> {
            boolean stopped = BlockPlacer.stopCurrentBuild();
            if (!stopped) {
                ChatFeedback.server("No active build");
            }
        });
    }

    /**
     * Обрабатывает команду {@code .print}: считает якорь, отправляет запрос
     * в Ollama и (если включён {@code autoBuild}) запускает установку блоков.
     *
     * @param request текст после префикса команды
     */
    private static void handlePrint(String request) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.player == null) {
            client("You must be in a world to use this command");
            return;
        }

        MinecraftServer server = BlockPlacer.server();
        if (server == null) {
            // Встроенный сервер одиночной игры.
            server = client.getServer();
        }
        if (server == null) {
            client("This command works in singleplayer / integrated server only");
            return;
        }

        // Если сборка уже идёт — новый запрос отклоняется.
        if (BlockPlacer.isBuilding()) {
            client("Build in progress");
            return;
        }

        // Якорь: блок на уровне ног игрока, в 2 блоках по направлению взгляда.
        final BlockPos anchor = anchorOf(client.player);
        final MinecraftServer targetServer = server;
        final ModConfig config = ModConfig.get();
        final long token = REQUEST_TOKEN.incrementAndGet();

        // Новый запрос отменяет предыдущий (требование ТЗ).
        OllamaClient.cancelCurrent();

        String prompt = PromptBuilder.build(request);
        LOGGER.info("[OllamaBuilder] Команда принята: \"{}\", якорь {} {} {}",
                request, anchor.getX(), anchor.getY(), anchor.getZ());
        client(String.format(Locale.ROOT, "Request sent to %s (model: %s)...", config.normalizedHost(), config.model()));

        final CompletableFuture<String> future;
        try {
            future = OllamaClient.generateAsync(prompt);
        } catch (RuntimeException e) {
            LOGGER.error("[OllamaBuilder] Не удалось отправить запрос в Ollama", e);
            client("Failed to send request: " + OllamaClient.describeError(e));
            return;
        }

        future.whenComplete((rawResponse, error) -> {
            // Ответ устаревшего запроса игнорируем.
            if (token != REQUEST_TOKEN.get()) {
                LOGGER.info("[OllamaBuilder] Ответ устаревшего запроса #{} проигнорирован", token);
                return;
            }

            if (error != null) {
                String description = OllamaClient.describeError(error);
                LOGGER.error("[OllamaBuilder] Запрос #{} завершился ошибкой: {}", token, description, error);
                client("Ollama error: " + description);
                return;
            }

            try {
                handleResponse(targetServer, rawResponse, anchor, token);
            } catch (RuntimeException e) {
                LOGGER.error("[OllamaBuilder] Ошибка обработки ответа модели", e);
                client("Failed to process model response: " + e.getMessage());
            }
        });
    }

    /**
     * Разбирает ответ модели, сохраняет файлы и запускает сборку.
     *
     * <p>Выполняется на сетевом потоке, поэтому всё, что касается мира,
     * передаётся на серверный поток через {@link BlockPlacer#startBuild}.
     */
    private static void handleResponse(MinecraftServer server, String rawResponse, BlockPos anchor, long token) {
        BuildPlanParser.Result result = BuildPlanParser.parseAndSave(rawResponse, anchor);

        if (result.parseError() != null) {
            LOGGER.warn("[OllamaBuilder] Запрос #{}: ошибка разбора: {}", token, result.parseError());
            client("Model response parse error: " + result.parseError() + " (raw saved to ollama_builds/last_raw.txt)");
        }

        if (result.skipped() > 0) {
            client(String.format(Locale.ROOT, "Skipped %d invalid block(s) from model output", result.skipped()));
        }

        BuildPlan plan = result.plan();
        if (plan.isEmpty()) {
            // Файлы всё равно сохранены — их можно изучить вручную.
            client("Model returned no blocks");
            return;
        }

        if (plan.truncated()) {
            client(String.format(Locale.ROOT, "Plan truncated to maxBlocks=%d", ModConfig.get().maxBlocks()));
        }

        Path planFile = result.planFile();
        if (planFile != null) {
            client("Plan saved: " + planFile.getFileName());
        }

        if (!ModConfig.get().autoBuild()) {
            client("autoBuild is disabled in config - plan saved, nothing placed");
            return;
        }

        BlockPlacer.startBuild(server, plan);
    }

    /**
     * Вычисляет якорь постройки: позиция ног игрока + 2 блока по направлению взгляда.
     * Эквивалент {@code player.getBlockPos().offset(player.getHorizontalFacing(), 2)}
     * с уточнением по вектору взгляда для диагональных направлений.
     *
     * @param player клиентский игрок
     * @return мировая позиция якоря (по Y — уровень ног)
     */
    private static BlockPos anchorOf(PlayerEntity player) {
        BlockPos feet = player.getBlockPos();
        Vec3d look = player.getRotationVector();
        int dx = horizontalStep(look.x);
        int dz = horizontalStep(look.z);

        // Если игрок смотрит строго вертикально — берём горизонтальное направление.
        if (dx == 0 && dz == 0) {
            Direction direction = player.getHorizontalFacing();
            dx = direction.getOffsetX();
            dz = direction.getOffsetZ();
        }

        return feet.add(dx * 2, 0, dz * 2);
    }

    /** Округляет компонент вектора взгляда до шага по сетке блоков (-1, 0, 1). */
    private static int horizontalStep(double value) {
        if (value > 0.35d) {
            return 1;
        }
        if (value < -0.35d) {
            return -1;
        }
        return 0;
    }

    /**
     * Выводит сообщение в клиентский чат (HUD). Вызов из любого потока
     * автоматически переносится на клиентский поток.
     *
     * @param message текст сообщения
     */
    private static void client(String message) {
        if (message == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            LOGGER.info("[OllamaBuilder] {}", message);
            return;
        }
        if (client.isOnThread()) {
            print(client, message);
        } else {
            client.execute(() -> print(client, message));
        }
    }

    /** Непосредственный вывод сообщения в чат-HUD. */
    private static void print(MinecraftClient client, String message) {
        try {
            ChatHud chatHud = client.inGameHud != null ? client.inGameHud.getChatHud() : null;
            if (chatHud != null) {
                chatHud.addMessage(Text.literal(FEEDBACK_PREFIX + message));
            } else {
                LOGGER.info("[OllamaBuilder] {}", message);
            }
        } catch (RuntimeException e) {
            LOGGER.error("[OllamaBuilder] Не удалось вывести сообщение в чат", e);
        }
    }
}
