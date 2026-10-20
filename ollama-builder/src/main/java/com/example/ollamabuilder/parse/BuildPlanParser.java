package com.example.ollamabuilder.parse;

import com.example.ollamabuilder.config.ModConfig;
import com.example.ollamabuilder.util.BlockIds;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Разбор и санитизация ответа модели.
 *
 * <p>Что делает парсер:
 * <ul>
 *     <li>вырезает JSON из текста (от первой <code>{</code> до последней <code>}</code>),
 *         предварительно снимая <code>```json</code>-ограждения;</li>
 *     <li>проверяет схему <code>{"blocks":[{"id":string,"x":int,"y":int,"z":int}]}</code>;</li>
 *     <li>приводит {@code id} к нижнему регистру и проверяет блок по реестру —
 *         неизвестные и {@code minecraft:air} пропускаются (счётчик skipped++);</li>
 *     <li>клампит координаты: {@code x,z} в [-32;32], {@code y} в [0;32];</li>
 *     <li>дедуплицирует по позиции (последняя запись выигрывает);</li>
 *     <li>обрезает результат до {@code maxBlocks} из конфига.</li>
 * </ul>
 *
 * <p>Побочно сохраняет файлы для отладки в {@code <gameDir>/ollama_builds/}:
 * {@code last_raw.txt} (сырой ответ модели), {@code <timestamp>.json} и
 * {@code latest.json} (нормализованный план).
 */
public final class BuildPlanParser {

    private static final Logger LOGGER = LoggerFactory.getLogger("OllamaBuilder/Parser");

    /** Ограничение по X и Z (в обе стороны от якоря). */
    public static final int XZ_LIMIT = 32;

    /** Ограничение по Y вверх от якоря. */
    public static final int Y_LIMIT = 32;

    /** Gson для сохранения нормализованного плана в человекочитаемом виде. */
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Формат имени файла с меткой времени. */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private BuildPlanParser() {
        // Утилитарный класс, экземпляры не создаются.
    }

    /**
     * Результат разбора.
     *
     * @param plan       готовый план (может быть пустым, но никогда не null)
     * @param skipped    сколько элементов модели было отброшено
     * @param parseError сообщение об ошибке разбора или {@code null}, если JSON разобран
     * @param planFile   путь к сохранённому {@code <timestamp>.json} или {@code null}
     * @param latestFile путь к сохранённому {@code latest.json} или {@code null}
     */
    public record Result(BuildPlan plan, int skipped, String parseError, Path planFile, Path latestFile) {
    }

    /**
     * Внутренний результат чистого разбора (без файловых операций).
     *
     * @param plan    план (возможно пустой)
     * @param skipped сколько элементов отброшено
     * @param error   сообщение об ошибке или {@code null}
     */
    public record Parsed(BuildPlan plan, int skipped, String error) {
    }

    /**
     * Разбирает сырой ответ модели и сохраняет отладочные файлы.
     * Вызывается на сетевом потоке — файловые операции игровой поток не блокируют.
     *
     * @param rawResponse текст из поля {@code response} ответа Ollama
     * @param anchor      мировая точка-якорь постройки
     * @return результат разбора
     */
    public static Result parseAndSave(String rawResponse, BlockPos anchor) {
        // Сырой ответ сохраняем всегда — даже если разобрать его не удалось.
        Path rawFile = saveRaw(rawResponse);
        LOGGER.info("[OllamaBuilder] Сырой ответ модели сохранён: {} ({} символов)",
                rawFile, rawResponse == null ? 0 : rawResponse.length());

        Parsed parsed = parse(rawResponse, anchor);
        BuildPlan plan = parsed.plan();
        int skipped = parsed.skipped();
        String parseError = parsed.error();

        // Нормализованный план сохраняем в любом случае (пустой — тоже, для отладки).
        Path planFile = null;
        Path latestFile = null;
        try {
            Path dir = buildsDir();
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(TIMESTAMP);
            planFile = dir.resolve(stamp + ".json");
            latestFile = dir.resolve("latest.json");

            JsonObject root = new JsonObject();
            root.addProperty("generatedAt", LocalDateTime.now().toString());
            root.addProperty("model", ModConfig.get().model());
            root.addProperty("anchorX", anchor.getX());
            root.addProperty("anchorY", anchor.getY());
            root.addProperty("anchorZ", anchor.getZ());
            root.addProperty("blockCount", plan.size());
            root.addProperty("skipped", skipped);
            if (parseError != null) {
                root.addProperty("parseError", parseError);
            }
            root.add("blocks", toJsonArray(plan));

            String text = PRETTY_GSON.toJson(root);
            Files.writeString(planFile, text, StandardCharsets.UTF_8);
            Files.copy(planFile, latestFile, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("[OllamaBuilder] План сохранён: {} ({} блоков, skipped={})", planFile, plan.size(), skipped);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[OllamaBuilder] Не удалось сохранить файл плана", e);
            planFile = null;
            latestFile = null;
        }

        return new Result(plan, skipped, parseError, planFile, latestFile);
    }

    /** Сериализует блоки плана в JsonArray. */
    private static JsonArray toJsonArray(BuildPlan plan) {
        JsonArray blocks = new JsonArray();
        for (BuildPlan.BuildBlock block : plan.blocks()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", block.id());
            item.addProperty("x", block.x());
            item.addProperty("y", block.y());
            item.addProperty("z", block.z());
            blocks.add(item);
        }
        return blocks;
    }

    /**
     * Чистый разбор текста в план (без файловых операций).
     *
     * @param rawResponse сырой ответ модели
     * @param anchor      якорь постройки
     * @return разобранный план, число пропусков и текст ошибки (если была)
     */
    public static Parsed parse(String rawResponse, BlockPos anchor) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return new Parsed(new BuildPlan(List.of(), anchor, false), 0, "Model returned empty response");
        }

        String json = extractJson(rawResponse);
        if (json == null) {
            return new Parsed(new BuildPlan(List.of(), anchor, false), 0, "No JSON object found in model response");
        }

        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return new Parsed(new BuildPlan(List.of(), anchor, false), 0,
                    "Invalid JSON from model: " + e.getClass().getSimpleName());
        }

        // Модель могла завернуть JSON в строку — делаем одну попытку развернуть её.
        if (rootElement != null && rootElement.isJsonPrimitive() && rootElement.getAsJsonPrimitive().isString()) {
            try {
                rootElement = JsonParser.parseString(rootElement.getAsString());
            } catch (RuntimeException e) {
                return new Parsed(new BuildPlan(List.of(), anchor, false), 0,
                        "Invalid JSON string from model: " + e.getClass().getSimpleName());
            }
        }

        if (rootElement == null || !rootElement.isJsonObject()) {
            return new Parsed(new BuildPlan(List.of(), anchor, false), 0, "Model response is not a JSON object");
        }

        JsonObject root = rootElement.getAsJsonObject();
        if (!root.has("blocks")) {
            return new Parsed(new BuildPlan(List.of(), anchor, false), 0, "JSON has no \"blocks\" field");
        }

        JsonElement blocksElement = root.get("blocks");
        if (blocksElement == null || !blocksElement.isJsonArray()) {
            return new Parsed(new BuildPlan(List.of(), anchor, false), 0, "\"blocks\" field is not an array");
        }

        JsonArray array = blocksElement.getAsJsonArray();
        int skipped = 0;

        // LinkedHashMap: порядок сохраняется, при дублях позиции «последний выигрывает».
        Map<BlockPos, BuildPlan.BuildBlock> byPosition = new LinkedHashMap<>();

        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                skipped++;
                LOGGER.warn("[OllamaBuilder] Пропущен элемент списка blocks: не объект ({})", element);
                continue;
            }

            JsonObject item = element.getAsJsonObject();

            String id = readString(item, "id");
            if (id == null || id.isBlank()) {
                skipped++;
                LOGGER.warn("[OllamaBuilder] Пропущен блок без поля \"id\": {}", item);
                continue;
            }
            id = id.trim().toLowerCase(Locale.ROOT);

            Integer x = readInt(item, "x");
            Integer y = readInt(item, "y");
            Integer z = readInt(item, "z");
            if (x == null || y == null || z == null) {
                skipped++;
                LOGGER.warn("[OllamaBuilder] Пропущен блок {} — координаты x/y/z должны быть целыми числами", id);
                continue;
            }

            // Клампы: y никогда не отрицательный, x/z/y в пределах лимитов.
            int cx = clamp(x, -XZ_LIMIT, XZ_LIMIT);
            int cy = clamp(y, 0, Y_LIMIT);
            int cz = clamp(z, -XZ_LIMIT, XZ_LIMIT);

            if (!BlockIds.isValid(id)) {
                skipped++;
                LOGGER.warn("[OllamaBuilder] Пропущен неизвестный или недопустимый блок: {} (позиция {}/{}/{})", id, cx, cy, cz);
                continue;
            }

            BlockPos position = new BlockPos(cx, cy, cz);
            byPosition.put(position, new BuildPlan.BuildBlock(id, cx, cy, cz));
        }

        List<BuildPlan.BuildBlock> blocks = new ArrayList<>(byPosition.values());

        // Обрезка до maxBlocks из конфига.
        int maxBlocks = ModConfig.get().maxBlocks();
        boolean truncated = false;
        if (blocks.size() > maxBlocks) {
            LOGGER.warn("[OllamaBuilder] План из {} блоков обрезан до maxBlocks={}", blocks.size(), maxBlocks);
            blocks = new ArrayList<>(blocks.subList(0, maxBlocks));
            truncated = true;
        }

        return new Parsed(new BuildPlan(blocks, anchor, truncated), skipped, null);
    }

    /**
     * Санитайз текста: снимаем markdown-ограждения, разворачиваем JSON,
     * если модель отдала его строкой в кавычках, и вырезаем подстроку
     * от первой <code>{</code> до последней <code>}</code>.
     *
     * @param raw сырой ответ модели
     * @return строка с JSON или {@code null}, если фигурных скобок нет
     */
    public static String extractJson(String raw) {
        if (raw == null) {
            return null;
        }

        String text = raw.trim();

        // Снимаем ```json ... ``` или ``` ... ```, если модель их всё же добавила.
        text = text.replace("```json", " ").replace("```JSON", " ").replace("```", " ").trim();

        // Вариант «ответ — это JSON-строка, внутри которой объект»:
        // {"blocks":...} приходит как "{\"blocks\":...}". Разворачиваем её.
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            try {
                JsonElement element = JsonParser.parseString(text);
                if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    text = element.getAsString().trim();
                }
            } catch (RuntimeException e) {
                LOGGER.debug("[OllamaBuilder] Не удалось развернуть JSON-строку, работаем с исходным текстом");
            }
        }

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    /** Читает строковое поле; возвращает {@code null}, если поля нет или это не строка. */
    private static String readString(JsonObject object, String key) {
        if (!object.has(key)) {
            return null;
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsString();
        } catch (RuntimeException e) {
            LOGGER.warn("[OllamaBuilder] Поле \"{}\" имеет неожиданный формат", key);
            return null;
        }
    }

    /**
     * Читает целочисленное поле. Терпим к тому, что модель могла написать
     * {@code 3.0} вместо {@code 3} — число просто округляется.
     */
    private static Integer readInt(JsonObject object, String key) {
        if (!object.has(key)) {
            return null;
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            // Пробуем разобрать как double (например "3.0" или 3.0).
            try {
                double asDouble = element.getAsDouble();
                if (Double.isFinite(asDouble)) {
                    return (int) Math.round(asDouble);
                }
            } catch (RuntimeException ignored) {
                // Ниже вернём null.
            }
            return null;
        }
    }

    /** Зажимает значение в диапазон [min; max]. */
    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    /** Каталог {@code <gameDir>/ollama_builds}. */
    public static Path buildsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("ollama_builds");
    }

    /** Сохраняет сырой ответ модели в {@code ollama_builds/last_raw.txt}. */
    public static Path saveRaw(String rawResponse) {
        Path file = buildsDir().resolve("last_raw.txt");
        try {
            Files.createDirectories(buildsDir());
            Files.writeString(file, rawResponse == null ? "" : rawResponse, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[OllamaBuilder] Не удалось сохранить {}", file, e);
        }
        return file;
    }
}
