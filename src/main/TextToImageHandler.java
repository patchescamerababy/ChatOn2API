import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import utils.BearerTokenGenerator;

public class TextToImageHandler implements HttpHandler {
    private final OkHttpClient okHttpClient = utils.utils.getOkHttpClient();
    private final Executor executor = Executors.newSingleThreadExecutor();
    private static final int CACHE_MAX_SIZE = 100;
    private static final String OPENAI_API_KEY = "YOUR_OPENAI_API_KEY"; // 请替换为您的 OpenAI API 密钥
    private static final String OPENAI_API_URI = "http://127.0.0.1:" + Main.port + "/v1/chat/completions";
    private static final String SYSTEMCONTENT = "Your role is that of a smart and creative assistant. Do not mention that you are a chatbot or AI assistant. Consider the terms when communicating: 1. The length of your response: Auto. 2. The tone style of your speech: Default. This dialog box has an option to generate images. The function should be called only when the user explicitly requests it - for example, using any related words associated with image generation requests. In other cases - the call of the image generation function should not be called.";

    private static final Map<String, String> promptCache = Collections.synchronizedMap(new LinkedHashMap<String, String>(CACHE_MAX_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_MAX_SIZE;
        }
    });

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

                System.out.println("Received Image Generations JSON: " + requestBody);

                JSONObject userInput = new JSONObject(requestBody);

                // 验证必需的字段
                if (!userInput.has("prompt")) {
                    utils.utils.sendError(exchange, "缺少必需的字段: prompt");
                    return;
                }

                String userPrompt = userInput.optString("prompt", "").trim();
                String responseFormat = userInput.optString("response_format", "").trim();
                int n = userInput.optInt("n", 1); // 读取 n 的值，默认为 1

                if (userPrompt.isEmpty()) {
                    utils.utils.sendError(exchange, "Prompt 不能为空。");
                    return;
                }
                //可选: 润色提示词
//                synchronized (promptCache) {
//                    if (promptCache.containsKey(userPrompt)) {
//                        userPrompt = promptCache.get(userPrompt);
//                        System.out.println("Cache hit for prompt: " + userPrompt);
//                    } else {
//                        // 使用 OpenAI API 润色用户的提示词
//                            userPrompt = refinePrompt(userPrompt);
//                        if (userPrompt == null || userPrompt.isEmpty()) {
//                            utils.utils.sendError(exchange, "Failed to refine the prompt using OpenAI API.");
//                            return;
//                        }
//                        // 将润色后的提示词存入缓存
//                        promptCache.put(userPrompt, userPrompt);
//                        System.out.println("Cache updated with prompt: " + userPrompt);
//                    }
//                }
                System.out.println("Prompt: " + userPrompt);
                System.out.println("Number of images to generate (n): " + n);

                // 设置最大尝试次数为 2 * n
                int maxAttempts = 2 * n;
                System.out.println("Max Attempts: " + maxAttempts);

                // 初始化用于存储多个 URL 的线程安全列表
                List<String> finalDownloadUrls = Collections.synchronizedList(new ArrayList<>());

                // 开始尝试生成图像
                boolean success = attemptGenerateImages(userPrompt, n, maxAttempts, finalDownloadUrls).join();

                if (success) {
                    // 根据 response_format 返回相应的响应
                    boolean isBase64Response = "b64_json".equalsIgnoreCase(responseFormat);

                    JSONObject responseJson = new JSONObject();
                    responseJson.put("created", System.currentTimeMillis() / 1000); // 添加 created 字段
                    JSONArray dataArray = new JSONArray();

                    if (isBase64Response) {
                        // 对每个下载链接进行处理
                        for (String downloadUrl : finalDownloadUrls) {
                            try {
                                // 下载图像并编码为 Base64
                                byte[] imageBytes = downloadImage(downloadUrl);
                                if (imageBytes == null) {
                                    // 如果下载失败，跳过此链接
                                    System.err.println("无法从 URL 下载图像: " + downloadUrl);
                                    continue;
                                }

                                // 转换为 Base64
                                String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);

                                JSONObject dataObject = new JSONObject();
                                dataObject.put("b64_json", imageBase64);
                                dataArray.put(dataObject);
                            } catch (Exception e) {
                                e.printStackTrace();
                                // 继续处理其他图像
                            }
                        }
                    } else {
                        // 直接返回所有图像的 URL
                        for (String downloadUrl : finalDownloadUrls) {
                            JSONObject dataObject = new JSONObject();
                            dataObject.put("url", downloadUrl);
                            dataArray.put(dataObject);
                        }
                    }

                    // 如果收集的 URL 数量不足 n，则通过复制现有的 URL 来填充
                    while (dataArray.length() < n && dataArray.length() > 0) {
                        for (int i = 0; i < dataArray.length() && dataArray.length() < n; i++) {
                            JSONObject original = dataArray.getJSONObject(i);
                            dataArray.put(original);
                        }
                    }

                    responseJson.put("data", dataArray);

                    try {
                        byte[] responseBytes = responseJson.toString().getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, responseBytes.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(responseBytes);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        utils.utils.sendError(exchange, "发送响应时发生错误: " + e.getMessage());
                    }

                } else {
                    // 如果在所有尝试后仍未收集到足够的链接，则返回错误
                    utils.utils.sendError(exchange, "无法生成足够数量的图像。");
                }

            } catch (JSONException je) {
                je.printStackTrace();
                utils.utils.sendError(exchange, "JSON 解析错误: " + je.getMessage());
            } catch (Exception e) {
                e.printStackTrace();
                utils.utils.sendError(exchange, "内部服务器错误: " + e.getMessage());
            }
        }, executor);
    }

    /**
     * 尝试生成图像，带有重试机制。
     *
     * @param userPrompt        用户的提示
     * @param n                 需要生成的图像数量
     * @param maxAttempts       最大尝试次数
     * @param finalDownloadUrls 收集的最终下载链接列表
     * @return CompletableFuture<Boolean> 表示是否成功收集到足够的下载链接
     */
    private CompletableFuture<Boolean> attemptGenerateImages(String userPrompt, int n,
                                                             int maxAttempts, List<String> finalDownloadUrls) {
        return CompletableFuture.supplyAsync(() -> {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                int needed = n - finalDownloadUrls.size();
                if (needed <= 0) {
                    break;
                }

                System.out.println("Attempt " + attempt + " - 需要生成的图像数量: " + needed);

                List<CompletableFuture<String>> futures = new ArrayList<>();

                for (int i = 0; i < needed; i++) {
                    int finalAttempt = attempt;
                    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                        try {
                            // 构建新的 TextToImage JSON 请求体
                            JSONObject textToImageJson = new JSONObject();
                            textToImageJson.put("function_image_gen", true);
                            textToImageJson.put("function_web_search", true);
                            textToImageJson.put("image_aspect_ratio", "1:1");
                            textToImageJson.put("image_style", "anime"); // 固定 image_style
                            textToImageJson.put("max_tokens", 8000);
                            textToImageJson.put("web_search_engine", "auto");
                            JSONArray messages = new JSONArray();
                            JSONObject message = new JSONObject();
                            message.put("content", SYSTEMCONTENT);
                            message.put("role", "system");
                            JSONObject userMessage = new JSONObject();
                            userMessage.put("content", "Draw: " + userPrompt);
                            userMessage.put("role", "user");
                            messages.put(message);
                            messages.put(userMessage);
                            textToImageJson.put("messages", messages);
                            textToImageJson.put("model", "claude-3-5-sonnet"); // 固定 model
                            textToImageJson.put("source", "chat/pro_image"); // 固定 source

                            String modifiedRequestBody = textToImageJson.toString();
                            byte[] requestBodyBytes = modifiedRequestBody.getBytes(StandardCharsets.UTF_8);

                            System.out.println("Attempt " + finalAttempt + " - 构建的请求: " + modifiedRequestBody);

                            // 使用OkHttp构建请求
                            Request request = utils.utils.buildRequest(requestBodyBytes,"/chats/stream", BearerTokenGenerator.UA);

                            // 发送请求并处理响应
                            Call call = okHttpClient.newCall(request);
                            try (Response response = call.execute()) {
                                if (!response.isSuccessful()) {
                                    System.err.println("Attempt " + finalAttempt + " - API 错误: " + response.code());
                                    return null;
                                }

                                ResponseBody responseBody = response.body();
                                if (responseBody == null) {
                                    System.err.println("Attempt " + finalAttempt + " - 响应体为空");
                                    return null;
                                }

                                // 初始化用于拼接 URL 的 StringBuilder
                                StringBuilder urlBuilder = new StringBuilder();

                                // 读取 SSE 流并拼接 URL
                                BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8));
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    System.out.println(line);
                                    if (line.startsWith("data: ")) {
                                        String data = line.substring(6).trim();
                                        if (data.equals("[DONE]")) {
                                            break; // 完成读取
                                        }

                                        try {
                                            JSONObject sseJson = new JSONObject(data);
                                            if (sseJson.has("choices")) {
                                                JSONArray choices = sseJson.getJSONArray("choices");
                                                for (int j = 0; j < choices.length(); j++) {
                                                    JSONObject choice = choices.getJSONObject(j);
                                                    JSONObject delta = choice.optJSONObject("delta");
                                                    if (delta != null && delta.has("content")) {
                                                        String content = delta.getString("content");
                                                        urlBuilder.append(content);
                                                    }
                                                }
                                            }
                                        } catch (JSONException e) {
                                            System.err.println("Attempt " + finalAttempt + " - JSON解析错误: " + e.getMessage());
                                        }
                                    }
                                }

                                String imageMarkdown = urlBuilder.toString();
                                // Step 1: 检查Markdown文本是否为空
                                if (imageMarkdown.isEmpty()) {
                                    System.out.println("Attempt " + finalAttempt + " - 无法从 SSE 流中构建图像 Markdown。");
                                    return null;
                                }

                                // Step 2: 从Markdown中提取图像路径
                                String extractedPath = utils.utils.extractPathFromMarkdown(imageMarkdown);

                                // Step 3: 如果没有提取到路径，输出错误信息
                                if (extractedPath == null || extractedPath.isEmpty()) {
                                    System.out.println("Attempt " + finalAttempt + " - 无法从 Markdown 中提取路径。");
                                    return null;
                                }

                                // Step 4: 过滤掉 "https://spc.unk/" 前缀
                                extractedPath = extractedPath.replace("https://spc.unk/", "");


                                // 输出提取到的路径
                                System.out.println("Attempt " + finalAttempt + " - 提取的路径: " + extractedPath);

                                // Step 5: 拼接最终的存储URL
                                String storageUrl = "https://api.chaton.ai/storage/" + extractedPath;
                                System.out.println("Attempt " + finalAttempt + " - 存储URL: " + storageUrl);

                                // 请求 storageUrl 获取 JSON 数据
                                String finalDownloadUrl = utils.utils.fetchGetUrlFromStorage(storageUrl);
                                if (finalDownloadUrl == null || finalDownloadUrl.isEmpty()) {
                                    System.out.println("Attempt " + finalAttempt + " - 无法从 storage URL 获取最终下载链接。");
                                    return null;
                                }

                                System.out.println("Final Download URL: " + finalDownloadUrl);

                                return finalDownloadUrl;
                            }

                        } catch (Exception e) {
                            e.printStackTrace();
                            System.err.println("Attempt " + finalAttempt + " - 处理响应时发生错误: " + e.getMessage());
                            return null;
                        }
                    }, executor);

                    futures.add(future);
                }

                // 等待所有任务完成
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                // 收集成功的下载链接
                for (CompletableFuture<String> future : futures) {
                    try {
                        String url = future.get();
                        if (url != null && !url.isEmpty()) {
                            finalDownloadUrls.add(url);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        System.err.println("Attempt " + attempt + " - 获取下载链接时发生错误: " + e.getMessage());
                    }
                }

                // 检查是否已经收集到足够的下载链接
                if (finalDownloadUrls.size() >= n) {
                    return true;
                }
            }

            // 在所有尝试后，检查是否收集到足够的链接
            if (finalDownloadUrls.size() >= n) {
                return true;
            } else {
                System.out.println("已达到最大尝试次数，仍未收集到足够数量的下载链接。");
                return false;
            }

        }, executor);
    }

    /**
     * 使用 OpenAI 的 chat/completions API 润色用户的提示词
     *
     * @param prompt 用户的原始提示词
     * @return 润色后的提示词
     */
    private String refinePrompt(String prompt) {
        try {
            // 构建请求体
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", "claude-3-5-sonnet");
            requestBody.put("stream", false);

            // 设置系统和用户消息
            JSONArray messages = new JSONArray();

            // 适当的系统内容，引导模型润色提示词
            JSONObject systemMessage = new JSONObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", "You are an assistant that refines and improves user-provided prompts for image generation. Ensure the prompt is clear, descriptive, and optimized for generating high-quality images. Only tell me in English in few long sentences.");
            messages.put(systemMessage);

            // 用户的原始提示词
            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.put(userMessage);

            requestBody.put("messages", messages);

            // 发送请求体
            MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
            RequestBody body = RequestBody.create(requestBody.toString(), mediaType);

            Request request = new Request.Builder()
                    .url(OPENAI_API_URI)
                    .post(body)
                    .header("Authorization", "Bearer " + OPENAI_API_KEY)
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
                JSONObject responseJson = new JSONObject(responseString);
                JSONArray choices = responseJson.getJSONArray("choices");

                if (choices.length() > 0) {
                    JSONObject firstChoice = choices.getJSONObject(0);
                    String refinedPrompt = firstChoice.getJSONObject("message").getString("content").trim();
                    return refinedPrompt;
                } else {
                    System.err.println("OpenAI API 返回的 choices 数组为空。");
                    return null;
                }
            }
        } catch (IOException | JSONException e) {
            System.err.println("调用 OpenAI API 失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 下载图像
     *
     * @param imageUrl 图像的最终下载 URL
     * @return 图像的字节数组，失败时返回 null
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
