package com.example.ollamabuilder.prompt;

/**
 * Сборка промпта для модели.
 *
 * <p>Системная часть задаёт жёсткую схему JSON-ответа и ограничения на координаты.
 * Пользовательская часть — это ровно текст, написанный игроком после префикса
 * команды: он передаётся модели без изменений и без перевода (запрос может быть
 * на русском).
 */
public final class PromptBuilder {

    /**
     * Системный промпт. Текст зафиксирован спецификацией мода и передаётся
     * модели буквально, без изменений.
     */
    public static final String SYSTEM_PROMPT = """
            You are a Minecraft structure generator. You receive a building request in natural language (it may be in Russian). Reply with ONLY a raw JSON object. No markdown, no code fences, no explanations, no trailing text.

            Required output schema:
            {"blocks":[{"id":"minecraft:stone_bricks","x":0,"y":0,"z":0}]}

            Hard rules:
            1. "id" must be a valid block registry id of Minecraft 1.21.1: lowercase "namespace:path", for example "minecraft:oak_planks", "minecraft:stone_bricks", "minecraft:glass". Never include block states, properties, tags or whitespace. Never use "minecraft:air".
            2. "x","y","z" are INTEGERS, relative offsets from an anchor point. The anchor (0,0,0) is the block at the player's feet level, 2 blocks in front of the player.
            3. Axis meaning: x = to the right of the player, z = forward from the player, y = up. Minimum y is 0 (ground level of the structure). Never output negative y.
            4. Limits: x and z within -32..32, y within 0..32.
            5. The structure must be connected and physically sensible: no floating blocks unless the design truly requires them. Walls must enclose an interior; roofs must be closed.
            6. Use a small palette: 3 to 6 distinct block types.
            7. Total number of blocks must not exceed 400. Prefer simple, recognizable, readable shapes over detail.
            8. Doors and windows: place a single block ("minecraft:oak_door", "minecraft:glass"); do not model hinges or multi-block halves.
            9. Output strict JSON only: double quotes, no comments, no trailing commas, no newlines outside of string values.
            10. If the request is unclear, build a small simple house instead of returning an empty list.

            Example:
            Request: "a 3x3 stone platform"
            {"blocks":[{"id":"minecraft:stone","x":0,"y":0,"z":0},{"id":"minecraft:stone","x":1,"y":0,"z":0},{"id":"minecraft:stone","x":2,"y":0,"z":0},{"id":"minecraft:stone","x":0,"y":0,"z":1},{"id":"minecraft:stone","x":1,"y":0,"z":1},{"id":"minecraft:stone","x":2,"y":0,"z":1},{"id":"minecraft:stone","x":0,"y":0,"z":2},{"id":"minecraft:stone","x":1,"y":0,"z":2},{"id":"minecraft:stone","x":2,"y":0,"z":2}]}
            """;

    private PromptBuilder() {
        // Утилитарный класс, экземпляры не создаются.
    }

    /**
     * Склеивает системный промпт и запрос игрока в одну строку для поля
     * {@code prompt} в {@code POST /api/generate}.
     *
     * @param userRequest текст после префикса команды (без изменений)
     * @return полный промпт
     */
    public static String build(String userRequest) {
        String request = userRequest == null ? "" : userRequest.trim();
        return SYSTEM_PROMPT + "\nRequest: \"" + request + "\"\n";
    }
}
