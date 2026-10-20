package com.example.ollamabuilder.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Конфигурация мода. Хранится в {@code <gameDir>/config/ollama_builder.json}
 * и создаётся при первом запуске со значениями по умолчанию.
 *
 * <p>Класс — {@code record}, сериализуется обычным Gson (он уже есть в игре,
 * внешние библиотеки не подключаются). Если в файле не хватает какого-то поля,
 * Gson подставит значение по умолчанию для типа, а {@link #sanitized()}
 * заменит его корректным дефолтом, поэтому «поломанный» конфиг не уронит игру.
 */
public record ModConfig(
        String host,
        String model,
        double temperature,
        int numPredict,
        int timeoutSeconds,
        int blocksPerTick,
        int maxBlocks,
        String chatPrefix,
        boolean autoBuild
) {
    private static final Logger LOGGER = LoggerFactory.getLogger("OllamaBuilder/Config");

    /** Значения по умолчанию — ровно те, что описаны в документации мода. */
    public static final ModConfig DEFAULT = new ModConfig(
            "http://127.0.0.1:11434",
            "qwen2.5-14b",
            0.3,
            8000,
            180,
            10,
            600,
            ".print",
            true
    );

    /** Gson с красивым выводом: конфиг удобно править руками. */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Текущий загруженный конфиг. Читается из любого потока, поэтому volatile. */
    private static volatile ModConfig current = DEFAULT;

    /** Путь к файлу конфигурации. */
    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("ollama_builder.json");
    }

    /** Текущий конфиг (никогда не null). */
    public static ModConfig get() {
        return current;
    }

    /**
     * Загружает конфиг из файла. Если файла нет — создаёт его с дефолтами.
     * Если файл повреждён — оставляет дефолты и пишет предупреждение в лог.
     */
    public static ModConfig load() {
        Path path = file();
        try {
            if (!Files.exists(path)) {
                save(DEFAULT);
                LOGGER.info("[OllamaBuilder] Создан конфиг по умолчанию: {}", path);
                current = DEFAULT;
                return current;
            }

            String text = Files.readString(path, StandardCharsets.UTF_8);
            ModConfig parsed = GSON.fromJson(text, ModConfig.class);
            if (parsed == null) {
                // Файл пустой или содержит "null".
                LOGGER.warn("[OllamaBuilder] Конфиг {} пуст, использую значения по умолчанию", path);
                current = DEFAULT;
                return current;
            }

            current = parsed.sanitized();
            LOGGER.info("[OllamaBuilder] Конфиг загружен: host={}, model={}, blocksPerTick={}, maxBlocks={}, prefix={}",
                    current.host(), current.model(), current.blocksPerTick(), current.maxBlocks(), current.chatPrefix());
            return current;
        } catch (Exception e) {
            // Любая ошибка чтения/парсинга не должна мешать запуску игры.
            LOGGER.error("[OllamaBuilder] Не удалось прочитать конфиг {}, использую значения по умолчанию", path, e);
            current = DEFAULT;
            return current;
        }
    }

    /** Сохраняет конфиг на диск (создаёт родительские каталоги при необходимости). */
    public static void save(ModConfig config) {
        Path path = file();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[OllamaBuilder] Не удалось сохранить конфиг {}", path, e);
        }
    }

    /** Перезаписывает конфиг на диск текущими значениями. */
    public static void save() {
        save(current);
    }

    /** Хост без завершающего слэша — чтобы URL склеивался предсказуемо. */
    public String normalizedHost() {
        String h = host == null ? DEFAULT.host : host.trim();
        if (h.isEmpty()) {
            h = DEFAULT.host;
        }
        if (!h.startsWith("http://") && !h.startsWith("https://")) {
            h = "http://" + h;
        }
        while (h.endsWith("/")) {
            h = h.substring(0, h.length() - 1);
        }
        return h;
    }

    /**
     * Приводит конфиг к безопасному виду: null-поля заменяются дефолтами,
     * числа зажимаются в разумные диапазоны.
     */
    public ModConfig sanitized() {
        String safeHost = (host == null || host.isBlank()) ? DEFAULT.host : host.trim();
        String safeModel = (model == null || model.isBlank()) ? DEFAULT.model : model.trim();
        double safeTemperature = Double.isFinite(temperature) ? clampDouble(temperature, 0.0, 2.0) : DEFAULT.temperature;
        int safeNumPredict = clampInt(numPredict, 256, 32768, DEFAULT.numPredict);
        int safeTimeout = clampInt(timeoutSeconds, 5, 1800, DEFAULT.timeoutSeconds);
        int safeBlocksPerTick = clampInt(blocksPerTick, 1, 1000, DEFAULT.blocksPerTick);
        int safeMaxBlocks = clampInt(maxBlocks, 1, 100000, DEFAULT.maxBlocks);
        String safePrefix = (chatPrefix == null || chatPrefix.isBlank()) ? DEFAULT.chatPrefix : chatPrefix.trim();
        return new ModConfig(
                safeHost,
                safeModel,
                safeTemperature,
                safeNumPredict,
                safeTimeout,
                safeBlocksPerTick,
                safeMaxBlocks,
                safePrefix,
                autoBuild
        );
    }

    /** Зажимает целое в диапазон [min; max]; если значение «нулевое» — возвращает дефолт. */
    private static int clampInt(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.min(Math.max(value, min), max);
    }

    /** Зажимает double в диапазон [min; max]. */
    private static double clampDouble(double value, double min, double max) {
        return Math.min(Math.max(value, min), max);
    }
}
