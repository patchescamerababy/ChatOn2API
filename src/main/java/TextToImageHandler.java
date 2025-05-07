import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import okhttp3.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.Base64;

import utils.BearerTokenGenerator;

public class TextToImageHandler implements HttpHandler {
    private final OkHttpClient okHttpClient = utils.utils.getOkHttpClient();
    private final Executor executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private static final int CACHE_MAX_SIZE = 100;
    private static final String OPENAI_API_URI = "http://127.0.0.1:" + Main.port + "/v1/chat/completions";
    private static final String SYSTEMCONTENT = "Your role is that of a smart and creative assistant. Do not mention that you are a chatbot or AI assistant. Consider the terms when communicating: 1. The length of your response: Auto. 2. The tone style of your speech: Default. This dialog box has an option to generate images. The function should be called only when the user explicitly requests it - for example, using any related words associated with image generation requests. In other cases - the call of the image generation function should not be called.";

    private static final Map<String, String> promptCache = Collections.synchronizedMap(new LinkedHashMap<String, String>(CACHE_MAX_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_MAX_SIZE;
        }
    });

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // 设置 CORS 头
        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

        String requestMethod = exchange.getRequestMethod().toUpperCase();

        // 处理预检请求
        if (requestMethod.equals("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        // 只允许 POST 请求
        if (!"POST".equals(requestMethod)) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // 异步处理请求
        CompletableFuture.runAsync(() -> {
            try {
                // 读取请求体
                InputStream is = exchange.getRequestBody();
                String requestBody = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .lines()
                        .reduce("", (acc, line) -> acc + line);

                JsonNode userInput = mapper.readTree(requestBody);
                System.out.println("Received Image Generations JSON: " + userInput.toString());

                // 验证必需的字段
                if (!userInput.has("prompt")) {
                    utils.utils.sendError(exchange, "缺少必需的字段: prompt");
                    return;
                }

                String userPrompt = userInput.path("prompt").asText("").trim();
                String responseFormat = userInput.path("response_format").asText("").trim();
                int n = userInput.path("n").asInt(1); // 读取 n 的值，默认为 1

                if (userPrompt.isEmpty()) {
                    utils.utils.sendError(exchange, "Prompt 不能为空。");
                    return;
                }

                // 异步处理提示词润色和图像生成
                processPromptAndGenerateImages(exchange, userPrompt, responseFormat, n);

            } catch (Exception e) {
                e.printStackTrace();
                utils.utils.sendError(exchange, "JSON 解析错误: " + e.getMessage());
            }
        }, executor);
    }

    /**
     * 异步处理提示词润色和图像生成
     */
    private void processPromptAndGenerateImages(HttpExchange exchange, String userPrompt, String responseFormat, int n) {
        // 异步润色提示词
        CompletableFuture<String> refinedPromptFuture = getRefinedPrompt(userPrompt);

        refinedPromptFuture
                .thenCompose(refinedPrompt -> {
                    if (refinedPrompt == null || refinedPrompt.isEmpty()) {
                        utils.utils.sendError(exchange, "Failed to refine the prompt using OpenAI API.");
                        return CompletableFuture.completedFuture(null);
                    }

                    System.out.println("Prompt: " + refinedPrompt);
                    System.out.println("Number of images to generate (n): " + n);

                    // 设置最大尝试次数为 2 * n
                    int maxAttempts = 2 * n;
                    System.out.println("Max Attempts: " + maxAttempts);

                    // 初始化用于存储多个 URL 的线程安全列表
                    List<String> finalDownloadUrls = Collections.synchronizedList(new ArrayList<>());

                    // 开始尝试生成图像
                    return attemptGenerateImages(refinedPrompt, n, maxAttempts, finalDownloadUrls)
                            .thenApply(success -> {
                                if (success) {
                                    return finalDownloadUrls;
                                }
                                return null;
                            });
                })
                .thenAccept(finalDownloadUrls -> {
                    if (finalDownloadUrls == null) {
                        utils.utils.sendError(exchange, "无法生成足够数量的图像。");
                        return;
                    }

                    try {
                        // 根据 response_format 返回相应的响应
                        boolean isBase64Response = "b64_json".equalsIgnoreCase(responseFormat);

                        ObjectNode responseJson = mapper.createObjectNode();
                        responseJson.put("created", System.currentTimeMillis() / 1000); // 添加 created 字段
                        ArrayNode dataArray = mapper.createArrayNode();

                        if (isBase64Response) {
                            // 对每个下载链接进行处理
                            List<CompletableFuture<ObjectNode>> downloadFutures = new ArrayList<>();

                            for (String downloadUrl : finalDownloadUrls) {
                                CompletableFuture<ObjectNode> downloadFuture = CompletableFuture.supplyAsync(() -> {
                                    try {
                                        // 下载图像并编码为 Base64
                                        byte[] imageBytes = downloadImage(downloadUrl);
                                        if (imageBytes == null) {
                                            // 如果下载失败，返回null
                                            System.err.println("无法从 URL 下载图像: " + downloadUrl);
                                            return null;
                                        }

                                        // 转换为 Base64
                                        String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);

                                        ObjectNode dataObject = mapper.createObjectNode();
                                        dataObject.put("b64_json", imageBase64);
                                        return dataObject;
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                        return null;
                                    }
                                }, executor);

                                downloadFutures.add(downloadFuture);
                            }

                            // 等待所有下载完成，并收集结果
                            CompletableFuture<Void> allDownloads = CompletableFuture.allOf(
                                    downloadFutures.toArray(new CompletableFuture[0])
                            );

                            allDownloads.thenRun(() -> {
                                try {
                                    // 收集所有成功的下载结果
                                    for (CompletableFuture<ObjectNode> future : downloadFutures) {
                                        ObjectNode result = future.join();
                                        if (result != null) {
                                            dataArray.add(result);
                                        }
                                    }

                                    // 如果收集的 URL 数量不足 n，则通过复制现有的 URL 来填充
                                    fillDataArrayIfNeeded(dataArray, n);

                                    responseJson.set("data", dataArray);
                                    sendSuccessResponse(exchange, responseJson);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    utils.utils.sendError(exchange, "处理下载的图像时发生错误: " + e.getMessage());
                                }
                            });
                        } else {
                            // 直接返回所有图像的 URL
                            for (String downloadUrl : finalDownloadUrls) {
                                ObjectNode dataObject = mapper.createObjectNode();
                                dataObject.put("url", downloadUrl);
                                dataArray.add(dataObject);
                            }

                            // 如果收集的 URL 数量不足 n，则通过复制现有的 URL 来填充
                            fillDataArrayIfNeeded(dataArray, n);

                            responseJson.set("data", dataArray);
                            sendSuccessResponse(exchange, responseJson);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        utils.utils.sendError(exchange, "生成响应时发生错误: " + e.getMessage());
                    }
                })
                .exceptionally(e -> {
                    e.printStackTrace();
                    utils.utils.sendError(exchange, "处理请求时发生错误: " + e.getMessage());
                    return null;
                });
    }

    /**
     * 填充数据数组，如果不足所需数量则复制现有项
     */
    private void fillDataArrayIfNeeded(ArrayNode dataArray, int targetSize) {
        // 如果收集的数量不足 n，则通过复制现有的来填充
        while (dataArray.size() < targetSize && dataArray.size() > 0) {
            for (int i = 0; i < dataArray.size() && dataArray.size() < targetSize; i++) {
                JsonNode original = dataArray.get(i);
                dataArray.add(original.deepCopy());
            }
        }
    }

    /**
     * 发送成功响应
     */
    private void sendSuccessResponse(HttpExchange exchange, JsonNode responseJson) {
        try {
            byte[] responseBytes = mapper.writeValueAsBytes(responseJson);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        } catch (IOException e) {
            e.printStackTrace();
            utils.utils.sendError(exchange, "发送响应时发生错误: " + e.getMessage());
        }
    }

    /**
     * 获取润色后的提示词，优先从缓存中获取
     */
    private CompletableFuture<String> getRefinedPrompt(String userPrompt) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (promptCache) {
                if (promptCache.containsKey(userPrompt)) {
                    String cachedPrompt = promptCache.get(userPrompt);
                    System.out.println("Cache hit for prompt: " + cachedPrompt);
                    return cachedPrompt;
                }
            }
            return null;
        }, executor).thenCompose(cachedPrompt -> {
            if (cachedPrompt != null) {
                return CompletableFuture.completedFuture(cachedPrompt);
            } else {
                // 缓存未命中，使用 OpenAI API 润色提示词
                return CompletableFuture.supplyAsync(() -> {
                    String refinedPrompt = refinePrompt(userPrompt);
                    if (refinedPrompt != null && !refinedPrompt.isEmpty()) {
                        // 将润色后的提示词存入缓存
                        synchronized (promptCache) {
                            promptCache.put(userPrompt, refinedPrompt);
                        }
                        System.out.println("Cache updated with prompt: " + refinedPrompt);
                    }
                    return refinedPrompt;
                }, executor);
            }
        });
    }

    /**
     * 尝试生成图像，带有重试机制。
     */
    private CompletableFuture<Boolean> attemptGenerateImages(String userPrompt, int n,
                                                             int maxAttempts, List<String> finalDownloadUrls) {
        // 使用递归方式实现异步重试
        return attemptGenerateImagesRecursive(userPrompt, n, maxAttempts, 1, finalDownloadUrls);
    }

    /**
     * 递归实现的异步图像生成重试机制
     */
    private CompletableFuture<Boolean> attemptGenerateImagesRecursive(String userPrompt, int n,
                                                                      int maxAttempts, int currentAttempt,
                                                                      List<String> finalDownloadUrls) {
        // 基本情况：已收集足够的URLs或已达到最大尝试次数
        if (finalDownloadUrls.size() >= n) {
            return CompletableFuture.completedFuture(true);
        }

        if (currentAttempt > maxAttempts) {
            System.out.println("已达到最大尝试次数，仍未收集到足够数量的下载链接。");
            return CompletableFuture.completedFuture(finalDownloadUrls.size() >= n);
        }

        int needed = n - finalDownloadUrls.size();
        System.out.println("Attempt " + currentAttempt + " - 需要生成的图像数量: " + needed);

        // 创建当前批次的所有图像生成任务
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < needed; i++) {
            futures.add(generateSingleImage(userPrompt, currentAttempt));
        }

        // 转换为组合的CompletableFuture
        CompletableFuture<List<String>> allFutures = CompletableFuture.allOf(
                        futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<String> urls = new ArrayList<>();
                    for (CompletableFuture<String> future : futures) {
                        try {
                            String url = future.join();
                            if (url != null && !url.isEmpty()) {
                                urls.add(url);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    return urls;
                });

        // 收集成功生成的URL，然后递归尝试下一批
        return allFutures.thenCompose(urls -> {
            // 添加成功的URL到最终列表
            finalDownloadUrls.addAll(urls);

            // 递归调用下一次尝试
            return attemptGenerateImagesRecursive(userPrompt, n, maxAttempts, currentAttempt + 1, finalDownloadUrls);
        });
    }

    /**
     * 生成单张图像并返回下载URL
     */
    private CompletableFuture<String> generateSingleImage(String userPrompt, int attempt) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 构建新的 TextToImage JSON 请求体
                ObjectNode textToImageJson = mapper.createObjectNode();
                textToImageJson.put("function_image_gen", true);
                textToImageJson.put("function_web_search", true);
                textToImageJson.put("image_aspect_ratio", "1:1");
                textToImageJson.put("image_style", "anime"); // 固定 image_style
                textToImageJson.put("max_tokens", 8000);
                textToImageJson.put("web_search_engine", "auto");
                ArrayNode messages = mapper.createArrayNode();
                ObjectNode message = mapper.createObjectNode();
                message.put("content", SYSTEMCONTENT);
                message.put("role", "system");
                ObjectNode userMessage = mapper.createObjectNode();
                userMessage.put("content", "Draw: " + userPrompt);
                userMessage.put("role", "user");
                messages.add(message);
                messages.add(userMessage);
                textToImageJson.set("messages", messages);
                textToImageJson.put("model", "claude-3-5-sonnet"); // 固定 model
                textToImageJson.put("source", "chat/pro_image"); // 固定 source

                String modifiedRequestBody = mapper.writeValueAsString(textToImageJson);
                byte[] requestBodyBytes = modifiedRequestBody.getBytes(StandardCharsets.UTF_8);

                System.out.println("Attempt " + attempt + " - 构建的请求: " + modifiedRequestBody);

                // 使用OkHttp构建请求
                Request request = utils.utils.buildRequest(requestBodyBytes, "/chats/stream");

                // 发送请求并处理响应
                Call call = okHttpClient.newCall(request);
                try (Response response = call.execute()) {
                    if (!response.isSuccessful()) {
                        System.err.println("Attempt " + attempt + " - API 错误: " + response.code());
                        return null;
                    }

                    ResponseBody responseBody = response.body();
                    if (responseBody == null) {
                        System.err.println("Attempt " + attempt + " - 响应体为空");
                        return null;
                    }

                    // 初始化用于拼接 URL 的 StringBuilder
                    StringBuilder urlBuilder = new StringBuilder();

                    // 读取 SSE 流并拼接 URL
                    BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6).trim();
                            if (data.equals("[DONE]")) {
                                break; // 完成读取
                            }

                            try {
                                JsonNode sseJson = mapper.readTree(data);
                                if (sseJson.has("choices")) {
                                    ArrayNode choices = (ArrayNode) sseJson.get("choices");
                                    for (JsonNode choice : choices) {
                                        JsonNode delta = choice.path("delta");
                                        if (delta != null && delta.has("content")) {
                                            String content = delta.get("content").asText();
                                            urlBuilder.append(content);
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                System.err.println("Attempt " + attempt + " - JSON解析错误: " + e.getMessage());
                            }
                        }
                    }

                    String imageMarkdown = urlBuilder.toString();
                    // Step 1: 检查Markdown文本是否为空
                    if (imageMarkdown.isEmpty()) {
                        System.out.println("Attempt " + attempt + " - 无法从 SSE 流中构建图像 Markdown。");
                        return null;
                    }

                    // Step 2: 从Markdown中提取图像路径
                    String extractedPath = utils.utils.extractPathFromMarkdown(imageMarkdown);

                    // Step 3: 如果没有提取到路径，输出错误信息
                    if (extractedPath == null || extractedPath.isEmpty()) {
                        System.out.println("Attempt " + attempt + " - 无法从 Markdown 中提取路径。");
                        return null;
                    }

                    // Step 4: 过滤掉 "https://spc.unk/" 前缀
                    extractedPath = extractedPath.replace("https://spc.unk/", "");

                    // 输出提取到的路径
                    System.out.println("Attempt " + attempt + " - 提取的路径: " + extractedPath);

                    // Step 5: 拼接最终的存储URL
                    String storageUrl = "https://api.chaton.ai/storage/" + extractedPath;
                    System.out.println("Attempt " + attempt + " - 存储URL: " + storageUrl);

                    // 请求 storageUrl 获取 JSON 数据
                    String finalDownloadUrl = utils.utils.fetchGetUrlFromStorage(storageUrl);
                    if (finalDownloadUrl == null || finalDownloadUrl.isEmpty()) {
                        System.out.println("Attempt " + attempt + " - 无法从 storage URL 获取最终下载链接。");
                        return null;
                    }

                    System.out.println("Final Download URL: " + finalDownloadUrl);

                    return finalDownloadUrl;
                }

            } catch (Exception e) {
                e.printStackTrace();
                System.err.println("Attempt " + attempt + " - 处理响应时发生错误: " + e.getMessage());
                return null;
            }
        }, executor);
    }

    /**
     * 使用 OpenAI 的 chat/completions API 润色用户的提示词
     */
    private String refinePrompt(String prompt) {
        try {
            // 构建请求体
            ObjectNode requestBody = mapper.createObjectNode();
            requestBody.put("model", "claude-3-5-sonnet");
            requestBody.put("stream", false);

            // 设置系统和用户消息
            ArrayNode messages = mapper.createArrayNode();

            // 适当的系统内容，引导模型润色提示词
            ObjectNode systemMessage = mapper.createObjectNode();
            systemMessage.put("role", "system");
            systemMessage.put("content", "You are an assistant that refines and improves user-provided prompts for image generation. Ensure the prompt is clear, descriptive, and optimized for generating high-quality images. Only tell me in English in few long sentences.");
            messages.add(systemMessage);

            // 用户的原始提示词
            ObjectNode userMessage = mapper.createObjectNode();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.add(userMessage);

            requestBody.set("messages", messages);

            // 发送请求体
            MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
            RequestBody body = RequestBody.create(mapper.writeValueAsString(requestBody), mediaType);

            Request request = new Request.Builder()
                    .url(OPENAI_API_URI)
                    .post(body)
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    System.err.println("OpenAI API 返回错误 (" + response.code() + ")");
                    if (response.body() != null) {
                        System.err.println(response.body().string());
                    }
                    return null;
                }

                String responseString = response.body().string();
                JsonNode responseJson = mapper.readTree(responseString);
                JsonNode choices = responseJson.get("choices");

                if (choices != null && choices.isArray() && choices.size() > 0) {
                    JsonNode firstChoice = choices.get(0);
                    String refinedPrompt = firstChoice.path("message").path("content").asText().trim();
                    return refinedPrompt;
                } else {
                    System.err.println("OpenAI API 返回的 choices 数组为空。");
                    return null;
                }
            }
        } catch (IOException e) {
            System.err.println("调用 OpenAI API 失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 下载图像
     */
    private byte[] downloadImage(String imageUrl) {
        try {
            Request request = new Request.Builder()
                    .url(imageUrl)
                    .get()
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return response.body().bytes();
                } else {
                    System.err.println("下载图像失败，状态码: " + response.code());
                    return null;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
