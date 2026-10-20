package com.example.ollamabuilder.util;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Преобразование строкового идентификатора блока в {@link BlockState}.
 *
 * <p>Используется только ванильный реестр {@link Registries#BLOCK} —
 * никакой рефлексии и никаких сторонних справочников.
 *
 * <p>Имена даны в Yarn-маппингах 1.21.1 ({@code Identifier}, {@code Registries},
 * {@code BlockState}).
 */
public final class BlockIds {

    private static final Logger LOGGER = LoggerFactory.getLogger("OllamaBuilder/BlockIds");

    private BlockIds() {
        // Утилитарный класс, экземпляры не создаются.
    }

    /**
     * Разрешает идентификатор в состояние блока.
     *
     * @param id строка вида {@code minecraft:oak_planks}
     * @return {@link BlockState} или {@code null}, если блок неизвестен,
     *         является воздухом или идентификатор некорректен
     */
    public static BlockState resolve(String id) {
        if (id == null) {
            return null;
        }

        String normalized = id.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }

        // Пробелы, свойства блока и теги в идентификаторе недопустимы.
        if (normalized.indexOf(' ') >= 0 || normalized.indexOf('[') >= 0 || normalized.indexOf('#') >= 0) {
            LOGGER.warn("[OllamaBuilder] Некорректный идентификатор блока (пробелы/свойства/теги): {}", id);
            return null;
        }

        // Identifier.tryParse не бросает исключений: на мусоре вернёт null.
        Identifier identifier = Identifier.tryParse(normalized);
        if (identifier == null) {
            LOGGER.warn("[OllamaBuilder] Не удалось разобрать идентификатор блока: {}", id);
            return null;
        }

        // DefaultedRegistry для блоков при отсутствии id возвращает AIR.
        Block block = Registries.BLOCK.get(identifier);
        if (block == null || block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) {
            LOGGER.warn("[OllamaBuilder] Блок не найден в реестре или является воздухом: {}", id);
            return null;
        }

        return block.getDefaultState();
    }

    /**
     * Быстрая проверка пригодности идентификатора (без создания состояния).
     *
     * @param id строковый идентификатор блока
     * @return {@code true}, если блок существует в реестре и не является воздухом
     */
    public static boolean isValid(String id) {
        return resolve(id) != null;
    }
}
