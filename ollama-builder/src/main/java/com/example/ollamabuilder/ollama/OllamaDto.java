package com.example.ollamabuilder.ollama;

import com.google.gson.annotations.SerializedName;

/**
 * DTO (record'ы) для обмена с Ollama по HTTP.
 *
 * <p>Сериализация и десериализация выполняется Gson'ом, который уже присутствует
 * в игре. Имена полей record совпадают с именами полей JSON API Ollama,
 * а там, где в JSON используется snake_case, стоит {@link SerializedName}.
 */
public final class OllamaDto {

    private OllamaDto() {
        // Утилитарный класс, экземпляры не создаются.
    }

    /**
     * Тело запроса {@code POST {host}/api/generate}.
     *
     * @param model   имя модели, например {@code qwen2.5-14b}
     * @param prompt  полный промпт (системная часть + пользовательская)
     * @param stream  всегда {@code false} — нам нужен один цельный ответ
     * @param format  всегда {@code "json"} — просим модель вернуть строгий JSON
     * @param options параметры генерации
     */
    public record GenerateRequest(
            String model,
            String prompt,
            boolean stream,
            String format,
            GenerateOptions options
    ) {
        /** Фабричный метод: собирает запрос с фиксированными stream/format. */
        public static GenerateRequest of(String model, String prompt, double temperature, int numPredict) {
            return new GenerateRequest(
                    model,
                    prompt,
                    false,
                    "json",
                    new GenerateOptions(temperature, numPredict)
            );
        }
    }

    /**
     * Секция {@code options} запроса.
     *
     * @param temperature температура генерации
     * @param numPredict  ограничение длины ответа в токенах
     */
    public record GenerateOptions(
            double temperature,
            @SerializedName("num_predict") int numPredict
    ) {
    }

    /**
     * Ответ {@code POST {host}/api/generate} при {@code stream:false}.
     * Лишние поля Ollama (context, total_duration и т. д.) Gson просто игнорирует.
     *
     * @param model           имя модели, ответившей на запрос
     * @param createdAt       время создания ответа
     * @param response        текст ответа модели — именно в нём лежит JSON со списком блоков
     * @param done            признак завершения генерации
     * @param doneReason      причина завершения
     * @param totalDuration   полное время выполнения в наносекундах
     * @param loadDuration    время загрузки модели в наносекундах
     * @param promptEvalCount количество токенов в промпте
     * @param evalCount       количество сгенерированных токенов
     * @param error           текст ошибки, если Ollama вернул её в теле ответа
     */
    public record GenerateResponse(
            String model,
            @SerializedName("created_at") String createdAt,
            String response,
            boolean done,
            @SerializedName("done_reason") String doneReason,
            @SerializedName("total_duration") long totalDuration,
            @SerializedName("load_duration") long loadDuration,
            @SerializedName("prompt_eval_count") long promptEvalCount,
            @SerializedName("eval_count") long evalCount,
            String error
    ) {
        /** Текст ответа модели или пустая строка, если поля нет. */
        public String responseOrEmpty() {
            return response == null ? "" : response;
        }
    }
}
