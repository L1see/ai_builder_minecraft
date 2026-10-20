package com.example.ollamabuilder.ollama;

import com.example.ollamabuilder.config.ModConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Единственное место в моде, которое ходит в сеть.
 *
 * <p>Используется встроенный в JDK {@link HttpClient} — внешних библиотек нет.
 * Вся работа выполняется в {@link ExecutorService} из одного daemon-потока,
 * поэтому клиентский и серверный потоки игры никогда не блокируются:
 * запрос собирается через {@code sendAsync} + {@code thenCompose}, без единого
 * блокирующего вызова на пути.
 *
 * <p>Ретраи: максимум 2 повторные попытки (всего 3 запроса), экспоненциальная
 * задержка 1 с и 3 с. Новый запрос отменяет предыдущий ({@code cancel(true)}).
 */
public final class OllamaClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("OllamaBuilder/Ollama");

    /** Gson для тела запроса и для разбора ответа. */
    private static final Gson GSON = new Gson();

    /** Тип ответа Ollama для TypeToken (record + TypeToken, как требует ТЗ). */
    private static final Type RESPONSE_TYPE = new TypeToken<OllamaDto.GenerateResponse>() {
    }.getType();

    /** Количество повторных попыток после первой неудачи. */
    private static final int MAX_RETRIES = 2;

    /** Экспоненциальные задержки перед повторными попытками: 1 с, 3 с. */
    private static final long[] RETRY_DELAYS_MS = {1000L, 3000L};

    /** Фабрика daemon-потоков: такие потоки не мешают завершению JVM. */
    private static final ThreadFactory THREAD_FACTORY = runnable -> {
        Thread thread = new Thread(runnable, "OllamaBuilder-Net");
        thread.setDaemon(true);
        return thread;
    };

    /** Один поток на всю сеть мода. */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(THREAD_FACTORY);

    /** Разделяемый HTTP-клиент; его колбэки тоже живут в нашем одном потоке. */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(EXECUTOR)
            .build();

    /** Порядковый номер запроса: нужен для логов и для игнорирования устаревших ответов. */
    private static final AtomicLong REQUEST_ID = new AtomicLong(0);

    /** Текущий незавершённый запрос — для отмены из {@link #cancelCurrent()}. */
    private static final AtomicReference<CompletableFuture<String>> IN_FLIGHT = new AtomicReference<>(null);

    private OllamaClient() {
        // Утилитарный класс, экземпляры не создаются.
    }

    /**
     * Асинхронно отправляет запрос в Ollama и возвращает будущий текст ответа
     * (поле {@code response} из JSON). Метод не блокирует вызывающий поток.
     *
     * @param prompt полная строка промпта (системная часть + пользовательская)
     * @return CompletableFuture с сырым текстом ответа модели
     */
    public static CompletableFuture<String> generateAsync(String prompt) {
        ModConfig config = ModConfig.get();

        // Новый запрос отменяет предыдущий (требование ТЗ).
        long id = REQUEST_ID.incrementAndGet();
        cancelCurrent();

        final String model = config.model();
        final String url = config.normalizedHost() + "/api/generate";
        final int timeoutSeconds = config.timeoutSeconds();

        OllamaDto.GenerateRequest body = OllamaDto.GenerateRequest.of(
                model,
                prompt,
                config.temperature(),
                config.numPredict()
        );
        final String json = GSON.toJson(body);

        LOGGER.info("[OllamaBuilder] Запрос #{} -> {} model={} timeout={}s temperature={} numPredict={}",
                id, url, model, timeoutSeconds, config.temperature(), config.numPredict());

        final HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            // Некорректный host в конфиге — сообщаем сразу, без сетевых вызовов.
            LOGGER.error("[OllamaBuilder] Некорректный host в конфиге: {}", config.host(), e);
            return CompletableFuture.failedFuture(
                    new IOException("Некорректный host в конфиге: " + config.host(), e));
        }

        // Страховочный «жёсткий» таймаут: чуть больше HTTP-таймаута запроса,
        // чтобы future не завис навсегда, если соединение «залипло».
        CompletableFuture<String> future = attempt(id, request, timeoutSeconds, 0)
                .orTimeout(timeoutSeconds * (long) (MAX_RETRIES + 1) + RETRY_DELAYS_MS[0] + RETRY_DELAYS_MS[1] + 15L,
                        TimeUnit.SECONDS);

        IN_FLIGHT.set(future);

        // Чистим ссылку, когда запрос завершился (успех, ошибка или отмена).
        future.whenComplete((text, error) -> IN_FLIGHT.compareAndSet(future, null));

        return future;
    }

    /**
     * Одна попытка запроса; при ошибке планирует повтор с экспоненциальной задержкой.
     * Всё выполняется на сетевом потоке, блокирующих вызовов в игровых потоках нет.
     *
     * <p>Ретраятся и транспортные ошибки (нет связи, таймаут), и ошибки ответа
     * (HTTP != 2xx, поле {@code error}, неразобранный JSON тела). Не ретраится
     * только отмена запроса пользователем.
     *
     * @param attempt номер попытки: 0 — первая, MAX_RETRIES — последняя
     */
    private static CompletableFuture<String> attempt(long id, HttpRequest request, int timeoutSeconds, int attempt) {
        CompletableFuture<HttpResponse<String>> http = HTTP_CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .orTimeout(timeoutSeconds + 5L, TimeUnit.SECONDS);

        return http.handle((response, error) -> {
            if (error != null) {
                // Транспортный уровень: нет соединения, таймаут, отмена.
                return fail(id, request, timeoutSeconds, attempt, unwrap(error));
            }

            try {
                return CompletableFuture.completedFuture(parse(id, response));
            } catch (RuntimeException responseError) {
                // Ответ получен, но непригоден: HTTP 4xx/5xx, error в теле, битый JSON.
                return fail(id, request, timeoutSeconds, attempt, responseError);
            }
        }).thenCompose(identity -> identity);
    }

    /**
     * Обрабатывает провал попытки: отмена — завершаем, иначе повтор через
     * экспоненциальную задержку (1 с, 3 с) либо окончательная ошибка.
     */
    private static CompletableFuture<String> fail(long id, HttpRequest request, int timeoutSeconds,
                                                  int attempt, Throwable cause) {
        if (isCancellation(cause)) {
            LOGGER.info("[OllamaBuilder] Запрос #{} отменён", id);
            return failedFuture(cause);
        }

        if (attempt >= MAX_RETRIES) {
            LOGGER.error("[OllamaBuilder] Запрос #{} окончательно провален (попыток: {}): {}",
                    id, attempt + 1, describe(cause));
            return failedFuture(cause);
        }

        long delay = RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length - 1)];
        LOGGER.warn("[OllamaBuilder] Запрос #{}: попытка {} из {} провалена ({}) — повтор через {} мс",
                id, attempt + 1, MAX_RETRIES + 1, describe(cause), delay);

        CompletableFuture<String> retry = new CompletableFuture<>();
        EXECUTOR.execute(() -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                retry.completeExceptionally(new CancellationException("Поток запроса прерван"));
                return;
            }
            attempt(id, request, timeoutSeconds, attempt + 1)
                    .whenComplete((text, retryError) -> {
                        if (retryError != null) {
                            retry.completeExceptionally(retryError);
                        } else {
                            retry.complete(text);
                        }
                    });
        });
        return retry;
    }

    /** Уже завершённый ошибкой future. */
    private static CompletableFuture<String> failedFuture(Throwable cause) {
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(cause);
        return failed;
    }

    /**
     * Проверяет HTTP-ответ: статус, тело, поле {@code error}, парсинг JSON.
     *
     * @return текст поля {@code response}
     */
    private static String parse(long id, HttpResponse<String> response) {
        int status = response.statusCode();
        String rawBody = response.body() == null ? "" : response.body();

        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Ollama вернула HTTP " + status + ": " + snippet(rawBody));
        }

        OllamaDto.GenerateResponse parsed;
        try {
            parsed = GSON.fromJson(rawBody, RESPONSE_TYPE);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Не удалось разобрать JSON ответа Ollama: " + snippet(rawBody), e);
        }

        if (parsed == null) {
            throw new IllegalStateException("Ollama вернула пустое тело ответа");
        }

        // Ollama умеет отвечать HTTP 200, но с текстом ошибки внутри JSON.
        if (parsed.error() != null && !parsed.error().isBlank()) {
            throw new IllegalStateException("Ollama сообщила об ошибке: " + parsed.error());
        }

        String text = parsed.responseOrEmpty();
        if (text.isBlank()) {
            throw new IllegalStateException("Ollama вернула пустой текст ответа (поле \"response\")");
        }

        LOGGER.info("[OllamaBuilder] Запрос #{} выполнен: {} символов, eval_count={}",
                id, text.length(), parsed.evalCount());
        return text;
    }

    /** Обрезает длинный текст для логов/чата. */
    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() > 500 ? flat.substring(0, 500) + "..." : flat;
    }

    /** Достает истинную причину из обёрток CompletionException/ExecutionException. */
    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** Проверяет, является ли ошибка отменой запроса пользователем. */
    private static boolean isCancellation(Throwable cause) {
        return cause instanceof CancellationException;
    }

    /** Превращает исключение в короткое человекочитаемое сообщение для чата и логов. */
    public static String describeError(Throwable error) {
        if (error == null) {
            return "неизвестная ошибка";
        }
        return describe(unwrap(error));
    }

    /** Внутренняя реализация {@link #describeError(Throwable)}. */
    private static String describe(Throwable cause) {
        if (cause == null) {
            return "неизвестная ошибка";
        }
        if (cause instanceof CancellationException) {
            return "запрос отменён";
        }
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
            return "таймаут ответа Ollama (увеличьте timeoutSeconds в конфиге)";
        }
        if (cause instanceof ConnectException) {
            return "не удалось подключиться к Ollama (сервер запущен? host верный?)";
        }
        if (cause instanceof IOException) {
            String message = cause.getMessage();
            return message == null || message.isBlank() ? "сетевая ошибка" : "сетевая ошибка: " + message;
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /** Отменяет текущий незавершённый запрос, если он есть. */
    public static void cancelCurrent() {
        CompletableFuture<String> current = IN_FLIGHT.getAndSet(null);
        if (current != null && !current.isDone()) {
            LOGGER.info("[OllamaBuilder] Отменяю предыдущий запрос");
            current.cancel(true);
        }
    }

    /** Завершает работу сетевого потока. Вызывается из shutdown-хука JVM. */
    public static void shutdown() {
        try {
            cancelCurrent();
            EXECUTOR.shutdown();
            if (!EXECUTOR.awaitTermination(2, TimeUnit.SECONDS)) {
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOGGER.warn("[OllamaBuilder] Ошибка при остановке сетевого потока", e);
        }
    }
}
