package com.example.ollamabuilder.builder;

import com.example.ollamabuilder.parse.BuildPlan;
import com.example.ollamabuilder.util.BlockIds;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Очередь установки блоков: курсор, счётчики, состояние и отмена.
 *
 * <p>Объект создаётся на серверном потоке в момент старта сборки и далее
 * читается/изменяется только там же, поэтому внутренние структуры
 * ({@link ArrayDeque}) не требуют синхронизации. Флаги {@code cancelled}
 * и {@code finished} — атомарные, потому что {@code .stop} может прийти
 * с клиентского потока.
 *
 * <p>Пакет называется {@code builder}, а не {@code build}: каталоги с именем
 * {@code build} вырезаются архиваторами и фильтрами проектов как служебные
 * (имя выходной папки Gradle), из-за чего исходники могли теряться при выгрузке.
 */
public final class BuildQueue {

    /** Состояние жизненного цикла сборки. */
    public enum State {
        /** Сборка не идёт. */
        IDLE,
        /** Очередь заполнена, идёт установка блоков. */
        RUNNING,
        /** Очередь пуста, сборка завершена (успех или отмена). */
        FINISHED
    }

    /**
     * Один элемент очереди.
     *
     * @param pos      мировая позиция блока
     * @param state    состояние блока для установки
     * @param id       исходный строковый идентификатор (для логов)
     * @param attempts сколько раз блок уже возвращался в очередь из-за незагруженного чанка
     */
    public record Entry(BlockPos pos, BlockState state, String id, int attempts) {

        /** Копия записи с увеличенным счётчиком попыток. */
        public Entry withAttempt() {
            return new Entry(pos, state, id, attempts + 1);
        }
    }

    /** Очередь блоков к установке. */
    private final Deque<Entry> queue = new ArrayDeque<>();

    /** Якорь постройки (для логов и отладки). */
    private final BlockPos anchor;

    /** Итоговое число блоков в плане. */
    private final int total;

    /** Сколько блоков уже установлено. */
    private final AtomicInteger placed = new AtomicInteger(0);

    /** Сколько блоков пропущено (бедрок, незагруженный чанк, ошибка установки). */
    private final AtomicInteger skipped = new AtomicInteger(0);

    /** Счётчик тиков для темпа выдачи прогресса в чат. */
    private final AtomicInteger ticks = new AtomicInteger(0);

    /** Подряд идущие тики без результата (ни одного установленного или пропущенного блока). */
    private final AtomicInteger idleTicks = new AtomicInteger(0);

    /** Сколько возвратов в очередь из-за незагруженного чанка уже сделано. */
    private final AtomicInteger requeues = new AtomicInteger(0);

    /** Момент старта в наносекундах (для итоговой строки с временем). */
    private final AtomicLong startedAtNanos = new AtomicLong(System.nanoTime());

    /** Флаг отмены. */
    private final AtomicReference<Boolean> cancelled = new AtomicReference<>(Boolean.FALSE);

    /** Текущее состояние. */
    private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);

    /**
     * Создаёт очередь из плана: позиции сразу переводятся в мировые.
     *
     * @param plan план постройки
     */
    public BuildQueue(BuildPlan plan) {
        this.anchor = plan.anchor();
        for (BuildPlan.BuildBlock block : plan.blocks()) {
            BlockState blockState = BlockIds.resolve(block.id());
            if (blockState == null) {
                // На этапе парсинга такие блоки уже отсеяны, но подстрахуемся.
                skipped.incrementAndGet();
                continue;
            }
            queue.addLast(new Entry(block.toWorld(plan.anchor()), blockState, block.id(), 0));
        }
        this.total = queue.size() + skipped.get();
    }

    /** Якорь постройки. */
    public BlockPos anchor() {
        return anchor;
    }

    /** Общее количество блоков в плане. */
    public int total() {
        return total;
    }

    /** Сколько блоков установлено. */
    public int placed() {
        return placed.get();
    }

    /** Сколько блоков пропущено. */
    public int skipped() {
        return skipped.get();
    }

    /** Пуста ли очередь. */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /** Размер очереди. */
    public int size() {
        return queue.size();
    }

    /** Забирает следующий элемент очереди или {@code null}, если она пуста. */
    public Entry poll() {
        return queue.pollFirst();
    }

    /**
     * Возвращает элемент в конец очереди из-за незагруженного чанка
     * и учитывает это в счётчике возвратов.
     */
    public void requeue(Entry entry) {
        queue.addLast(entry);
        requeues.incrementAndGet();
    }

    /**
     * Кладёт элемент обратно в очередь без увеличения счётчика возвратов:
     * используется, когда блок уже возвращался в текущем тике и его нужно
     * просто сохранить до следующего тика.
     */
    public void requeueUncounted(Entry entry) {
        queue.addLast(entry);
    }

    /** Сколько раз блоки возвращались в очередь из-за незагруженных чанков. */
    public int requeueCount() {
        return requeues.get();
    }

    /** Увеличивает счётчик установленных блоков. */
    public void incrementPlaced() {
        placed.incrementAndGet();
    }

    /** Увеличивает счётчик пропущенных блоков. */
    public void incrementSkipped() {
        skipped.incrementAndGet();
    }

    /** Увеличивает счётчик тиков и возвращает новое значение. */
    public int tickIncrement() {
        return ticks.incrementAndGet();
    }

    /**
     * Фиксирует результат тика.
     *
     * @param progressed был ли за тик установлен или пропущен хотя бы один блок
     * @return текущее число подряд идущих «пустых» тиков
     */
    public int recordTick(boolean progressed) {
        if (progressed) {
            idleTicks.set(0);
        } else {
            return idleTicks.incrementAndGet();
        }
        return 0;
    }

    /** Сбрасывает счётчик «пустых» тиков. */
    public void resetIdleTicks() {
        idleTicks.set(0);
    }

    /** Текущее состояние. */
    public State state() {
        return state.get();
    }

    /** Переводит состояние в {@link State#FINISHED}. */
    public void markFinished() {
        state.set(State.FINISHED);
    }

    /**
     * Идёт ли сборка прямо сейчас.
     * Пустая очередь сборкой не считается: её завершение оформляет
     * {@code BlockPlacer.onServerTick} (итог в чат и сброс состояния).
     */
    public boolean isRunning() {
        return state.get() == State.RUNNING && !isCancelled() && !queue.isEmpty();
    }

    /** Запрашивает отмену сборки. */
    public void cancel() {
        cancelled.set(Boolean.TRUE);
    }

    /** Отменена ли сборка. */
    public boolean isCancelled() {
        return Boolean.TRUE.equals(cancelled.get());
    }

    /** Полностью очищает очередь (при отмене). */
    public void clear() {
        queue.clear();
    }

    /** Время с момента старта в секундах. */
    public double elapsedSeconds() {
        return (System.nanoTime() - startedAtNanos.get()) / 1_000_000_000.0d;
    }

    /** Процент выполнения (0..100). */
    public int percent() {
        if (total <= 0) {
            return 100;
        }
        int processed = placed.get() + skipped.get();
        return Math.min(100, (int) Math.round(processed * 100.0d / total));
    }
}
