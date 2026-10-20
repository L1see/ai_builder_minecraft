package com.example.ollamabuilder.builder;

import com.example.ollamabuilder.chat.ChatFeedback;
import com.example.ollamabuilder.config.ModConfig;
import com.example.ollamabuilder.parse.BuildPlan;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Установка блоков из очереди: ровно {@code blocksPerTick} блоков за тик,
 * строго на серверном потоке.
 *
 * <p>Вызывается из {@code ServerTickEvents.END_SERVER_TICK} — то есть уже
 * внутри серверного тика. Для дополнительной страховки вся работа с миром
 * выполняется через {@code server.execute(...)}: если событие вдруг придёт
 * не с серверного потока, задача будет поставлена в очередь сервера.
 *
 * <p>Имена классов и методов даны в Yarn-маппингах 1.21.1:
 * {@code ServerWorld} (в Mojmap — ServerLevel), {@code World.setBlockState(pos, state, flags)}
 * (в Mojmap — Level.setBlock), {@code Block.NOTIFY_ALL} (в Mojmap — Block.UPDATE_ALL),
 * {@code ServerWorld.isChunkLoaded(ChunkPos.toLong(pos))} (в Mojmap — Level.isLoaded(pos)).
 *
 * <p>Пакет называется {@code builder}, а не {@code build}: каталоги с именем
 * {@code build} вырезаются архиваторами и фильтрами проектов как служебные
 * (имя выходной папки Gradle), из-за чего исходники могли теряться при выгрузке.
 */
public final class BlockPlacer {

    private static final Logger LOGGER = LoggerFactory.getLogger("OllamaBuilder/Placer");

    /** Сколько раз блок может «вернуться» в очередь из-за незагруженного чанка. */
    private static final int MAX_CHUNK_RETRIES = 3;

    /** Период выдачи прогресса в чат: примерно раз в 20 тиков (1 секунда). */
    private static final int PROGRESS_EVERY_TICKS = 20;

    /**
     * Сколько «пустых» тиков подряд (ни одного установленного или пропущенного блока)
     * мы готовы ждать загрузки чанков, прежде чем прервать сборку.
     * 200 тиков ≈ 10 секунд реального времени.
     */
    static final int MAX_IDLE_TICKS = 200;

    /**
     * Абсолютный потолок возвратов в очередь из-за незагруженных чанков на одну сборку.
     * В нормальной ситуации недостижим: блоку хватает {@value #MAX_CHUNK_RETRIES} возвратов,
     * после чего он пропускается. Нужен как страховка от патологического случая,
     * когда чанки постоянно выгружаются и очередь «крутится» без результата.
     */
    static final int MAX_TOTAL_REQUEUES = 20000;

    /** Ссылка на запущенный сервер (одиночная игра / встроенный сервер). */
    private static final AtomicReference<MinecraftServer> SERVER = new AtomicReference<>(null);

    /** Игрок, которому адресованы сообщения о прогрессе. */
    private static final AtomicReference<ServerPlayerEntity> ACTIVE_PLAYER = new AtomicReference<>(null);

    /** Текущая сборка; {@code null}, если ничего не строится. */
    private static final AtomicReference<BuildQueue> CURRENT = new AtomicReference<>(null);

    private BlockPlacer() {
        // Утилитарный класс, экземпляры не создаются.
    }

    /**
     * Минимальный интерфейс доступа к миру. Вынесен отдельно, чтобы логику
     * очереди можно было проверить без поднятия настоящего {@link ServerWorld}.
     */
    public interface WorldAccessOps {

        /** Загружен ли чанк с указанной позицией. */
        boolean isLoaded(BlockPos pos);

        /** Текущее состояние блока в позиции. */
        BlockState getBlockState(BlockPos pos);

        /** Устанавливает блок; возвращает {@code true} при успехе. */
        boolean setBlock(BlockPos pos, BlockState state);
    }

    /** Реализация {@link WorldAccessOps} поверх настоящего серверного мира. */
    private record ServerWorldOps(ServerWorld world) implements WorldAccessOps {

        @Override
        public boolean isLoaded(BlockPos pos) {
            // В Yarn 1.21.1 WorldView.isChunkLoaded(BlockPos) и isPosLoaded(int, int)
            // помечены как deprecated; актуальный способ — ServerWorld.isChunkLoaded(long),
            // куда передаётся упакованная позиция чанка (ChunkPos.toLong).
            return world.isChunkLoaded(ChunkPos.toLong(pos));
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return world.getBlockState(pos);
        }

        @Override
        public boolean setBlock(BlockPos pos, BlockState state) {
            // Block.NOTIFY_ALL = NOTIFY_NEIGHBOURS | NOTIFY_LISTENERS:
            // блок сразу виден клиенту и корректно обновляет соседей.
            return world.setBlockState(pos, state, Block.NOTIFY_ALL);
        }
    }

    /** Запоминает сервер (событие {@code SERVER_STARTED}). */
    public static void onServerStarted(MinecraftServer server) {
        SERVER.set(server);
        LOGGER.info("[OllamaBuilder] Сервер запущен, blocksPerTick={}", ModConfig.get().blocksPerTick());
    }

    /** Забывает сервер и сбрасывает сборку (событие {@code SERVER_STOPPING}). */
    public static void onServerStopping(MinecraftServer server) {
        SERVER.compareAndSet(server, null);
        BuildQueue queue = CURRENT.getAndSet(null);
        if (queue != null) {
            queue.cancel();
            LOGGER.info("[OllamaBuilder] Сервер останавливается — сборка сброшена");
        }
        ACTIVE_PLAYER.set(null);
    }

    /** Обновляет «активного» игрока при входе/выходе (JOIN/DISCONNECT). */
    public static void updateActivePlayer(ServerPlayerEntity player) {
        if (player != null && !player.isRemoved()) {
            ACTIVE_PLAYER.set(player);
        } else {
            ServerPlayerEntity current = ACTIVE_PLAYER.get();
            if (current != null && current.isRemoved()) {
                ACTIVE_PLAYER.set(null);
            }
        }
    }

    /** Сервер, на котором идёт (или должна идти) сборка; может быть {@code null}. */
    public static MinecraftServer server() {
        return SERVER.get();
    }

    /** Идёт ли сейчас сборка. */
    public static boolean isBuilding() {
        BuildQueue queue = CURRENT.get();
        return queue != null && queue.isRunning();
    }

    /** Текущая очередь сборки (для диагностики); может быть {@code null}. */
    public static BuildQueue current() {
        return CURRENT.get();
    }

    /**
     * Игрок, которому адресованы сообщения о ходе сборки; может быть {@code null}.
     * Если «активный» игрок вышел из мира, берётся первый подключённый игрок
     * (в одиночной игре он всегда один).
     */
    public static ServerPlayerEntity currentPlayer() {
        ServerPlayerEntity player = ACTIVE_PLAYER.get();
        if (player != null && !player.isRemoved()) {
            return player;
        }

        MinecraftServer server = SERVER.get();
        if (server == null) {
            return null;
        }

        try {
            List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
            if (players.isEmpty()) {
                return null;
            }
            ServerPlayerEntity first = players.get(0);
            ACTIVE_PLAYER.set(first);
            return first;
        } catch (RuntimeException e) {
            LOGGER.warn("[OllamaBuilder] Не удалось определить игрока для сообщений в чат", e);
            return null;
        }
    }

    /**
     * Запускает установку блоков. Вызывается из обработчика ответа Ollama,
     * фактическое размещение происходит на серверном потоке.
     *
     * @param server сервер мира
     * @param plan   разобранный план
     */
    public static void startBuild(MinecraftServer server, BuildPlan plan) {
        if (server == null) {
            LOGGER.warn("[OllamaBuilder] Сервер недоступен — сборка не запущена");
            return;
        }
        if (plan == null || plan.isEmpty()) {
            LOGGER.warn("[OllamaBuilder] План пуст — собирать нечего");
            return;
        }

        server.execute(() -> {
            // Повторная проверка уже на серверном потоке: ситуация могла измениться.
            if (isBuilding()) {
                ChatFeedback.server("Build in progress");
                return;
            }

            BuildQueue queue = new BuildQueue(plan);
            CURRENT.set(queue);

            int blocksPerTick = ModConfig.get().blocksPerTick();
            BlockPos anchor = queue.anchor();
            LOGGER.info("[OllamaBuilder] Старт сборки: {} блоков, {} блоков/тик, якорь {} {} {}",
                    queue.total(), blocksPerTick, anchor.getX(), anchor.getY(), anchor.getZ());
            ChatFeedback.server(String.format(Locale.ROOT,
                    "Building %d blocks (%d per tick) at %d %d %d",
                    queue.total(), blocksPerTick, anchor.getX(), anchor.getY(), anchor.getZ()));
        });
    }

    /**
     * Обработчик {@code ServerTickEvents.END_SERVER_TICK}.
     * Устанавливает ровно {@code blocksPerTick} блоков за тик.
     *
     * @param server сервер мира
     */
    public static void onServerTick(MinecraftServer server) {
        BuildQueue queue = CURRENT.get();
        if (queue == null || !queue.isRunning() || queue.isEmpty()) {
            return;
        }

        // Гарантируем, что размещение происходит на серверном потоке.
        if (!server.isOnThread()) {
            server.execute(() -> onServerTick(server));
            return;
        }

        ServerWorld world = pickWorld(server, queue.anchor());
        if (world == null) {
            LOGGER.warn("[OllamaBuilder] Не удалось определить мир для якоря — сборка остановлена");
            finish(queue, true);
            return;
        }

        boolean finished = processQueue(queue, new ServerWorldOps(world));
        if (finished) {
            return;
        }

        // Прогресс в чат примерно раз в 20 тиков.
        int tick = queue.tickIncrement();
        if (tick % PROGRESS_EVERY_TICKS == 0 && !queue.isEmpty()) {
            ChatFeedback.server(String.format(Locale.ROOT, "Placed %d/%d (%d%%)",
                    queue.placed(), queue.total(), queue.percent()));
        }

        if (queue.isEmpty()) {
            finish(queue, false);
        }
    }

    /**
     * Обрабатывает ровно {@code blocksPerTick} элементов очереди.
     *
     * <p>Правила:
     * <ul>
     *     <li>бедрок не заменяется никогда;</li>
     *     <li>блок в незагруженном чанке возвращается в конец очереди
     *         (не более одного возврата за тик, максимум {@value #MAX_CHUNK_RETRIES} попытки),
     *         затем пропускается;</li>
     *     <li>если прогресса нет слишком долго ({@value #MAX_IDLE_TICKS} «пустых» тиков)
     *         или исчерпан потолок возвратов в очередь ({@value #MAX_TOTAL_REQUEUES}),
     *         сборка прерывается — иначе очередь «крутилась» бы вечно;</li>
     *     <li>отмена проверяется перед каждым блоком.</li>
     * </ul>
     *
     * @param queue текущая очередь
     * @param ops   доступ к миру
     * @return {@code true}, если сборка завершилась (отменена или прервана) и
     *         дальнейшая обработка тика не нужна
     */
    static boolean processQueue(BuildQueue queue, WorldAccessOps ops) {
        // Уже завершённую или отменённую очередь не трогаем.
        if (!queue.isRunning()) {
            return true;
        }

        int blocksPerTick = Math.max(1, ModConfig.get().blocksPerTick());

        // Бюджет тика: ровно blocksPerTick блоков, но каждый элемент очереди
        // рассматривается за тик не более одного раза (иначе один незагруженный
        // чанк прокручивал бы очередь по кругу внутри одного тика).
        int examined = 0;
        int pollBudget = Math.min(blocksPerTick, queue.size());

        // Реально обработанные блоки (установлены или пропущены). Возврат в очередь
        // прогрессом не считается — иначе незагруженный чанк крутил бы очередь вечно.
        boolean progressed = false;

        // Позиции, уже возвращённые в очередь за этот тик: один ретрай на блок за тик,
        // иначе блок исчерпал бы все попытки внутри одного тика, не дав чанку загрузиться.
        Set<BlockPos> requeuedThisTick = new HashSet<>();

        while (examined < pollBudget) {
            if (queue.isCancelled()) {
                finish(queue, true);
                return true;
            }

            BuildQueue.Entry entry = queue.poll();
            if (entry == null) {
                // Очередь закончилась.
                break;
            }
            examined++;

            BlockPos pos = entry.pos();

            // Чанк не загружен — возвращаем блок в конец очереди (не более 3 попыток).
            if (!ops.isLoaded(pos)) {
                if (entry.attempts() >= MAX_CHUNK_RETRIES) {
                    LOGGER.warn("[OllamaBuilder] Чанк так и не загрузился ({}), блок {} пропущен", pos, entry.id());
                    queue.incrementSkipped();
                    progressed = true;
                } else if (requeuedThisTick.add(pos)) {
                    queue.requeue(entry.withAttempt());
                    LOGGER.debug("[OllamaBuilder] Чанк не загружен ({}), блок {} возвращён в очередь, попытка {}",
                            pos, entry.id(), entry.attempts() + 1);
                } else {
                    // В этом тике блок уже возвращали — просто сохраняем его до следующего тика.
                    queue.requeueUncounted(entry);
                }
                continue;
            }

            // Бедрок не заменяем никогда.
            BlockState existing = ops.getBlockState(pos);
            if (existing != null && existing.isOf(Blocks.BEDROCK)) {
                LOGGER.info("[OllamaBuilder] Пропущен блок на позиции {} {} {} — там бедрок",
                        pos.getX(), pos.getY(), pos.getZ());
                queue.incrementSkipped();
                progressed = true;
                continue;
            }

            try {
                boolean ok = ops.setBlock(pos, entry.state());
                if (ok) {
                    queue.incrementPlaced();
                    LOGGER.info("[OllamaBuilder] Установлен блок {} на {} {} {} (осталось в очереди: {})",
                            entry.id(), pos.getX(), pos.getY(), pos.getZ(), queue.size());
                } else {
                    LOGGER.warn("[OllamaBuilder] Мир отказался ставить блок {} на {}", entry.id(), pos);
                    queue.incrementSkipped();
                }
            } catch (RuntimeException e) {
                LOGGER.error("[OllamaBuilder] Ошибка установки блока {} на {}", entry.id(), pos, e);
                queue.incrementSkipped();
            }
            progressed = true;
        }

        // Защита от бесконечного «верчения» очереди: если за тик не установлен и не
        // пропущен ни один блок, считаем тик «пустым». Слишком длинная серия пустых
        // тиков (или аномально большое число возвратов в очередь) прерывает сборку.
        int idle = queue.recordTick(progressed);
        if (!progressed && !queue.isEmpty()
                && (idle >= MAX_IDLE_TICKS || queue.requeueCount() >= MAX_TOTAL_REQUEUES)) {
            LOGGER.warn("[OllamaBuilder] Прогресса нет (пустых тиков подряд: {}, возвратов в очередь: {}) — сборка остановлена",
                    idle, queue.requeueCount());
            finish(queue, false);
            return true;
        }

        return false;
    }

    /**
     * Немедленная отмена текущей сборки (команда {@code .stop}).
     *
     * @return {@code true}, если сборка действительно была остановлена
     */
    public static boolean stopCurrentBuild() {
        BuildQueue queue = CURRENT.get();
        if (queue == null || !queue.isRunning()) {
            return false;
        }

        queue.cancel();
        int placed = queue.placed();
        int skipped = queue.skipped();
        queue.clear();
        queue.markFinished();
        CURRENT.compareAndSet(queue, null);

        LOGGER.info("[OllamaBuilder] Сборка отменена игроком: установлено {}, пропущено {}", placed, skipped);
        ChatFeedback.server(String.format(Locale.ROOT, "Build cancelled: %d blocks placed, %d skipped", placed, skipped));
        return true;
    }

    /**
     * Завершает сборку: печатает итог в чат и в лог, сбрасывает состояние.
     *
     * @param cancelled отменена ли сборка (влияет только на сообщение в логе)
     */
    private static void finish(BuildQueue queue, boolean cancelled) {
        // Состояние очереди сбрасываем всегда — даже если активной сборкой
        // в этот момент считается другая очередь (например, после .stop).
        queue.markFinished();

        if (CURRENT.compareAndSet(queue, null)) {
            int placed = queue.placed();
            int skipped = queue.skipped();
            double seconds = queue.elapsedSeconds();

            LOGGER.info("[OllamaBuilder] Сборка завершена{}: установлено {}, пропущено {}, время {} с",
                    cancelled ? " (отменена)" : "", placed, skipped, String.format(Locale.ROOT, "%.1f", seconds));

            ChatFeedback.server(String.format(Locale.ROOT, "Done: %d blocks in %.1fs (skipped %d)",
                    placed, seconds, skipped));
        }
    }

    /**
     * Выбирает мир, в котором находится якорь: сначала мир игрока,
     * затем мир с загруженным чанком якоря, затем основной (overworld).
     */
    private static ServerWorld pickWorld(MinecraftServer server, BlockPos anchor) {
        ServerPlayerEntity player = currentPlayer();
        if (player != null && !player.isRemoved()) {
            return player.getServerWorld();
        }

        for (ServerWorld world : server.getWorlds()) {
            if (world != null && world.isChunkLoaded(ChunkPos.toLong(anchor))) {
                return world;
            }
        }

        ServerWorld overworld = server.getOverworld();
        if (overworld != null) {
            return overworld;
        }

        for (ServerWorld world : server.getWorlds()) {
            return world;
        }
        return null;
    }
}
