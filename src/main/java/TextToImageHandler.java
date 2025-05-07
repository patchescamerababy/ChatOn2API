import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import utils.BearerTokenGenerator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class TextToImageHandler implements HttpHandler {
    // 非阻塞计算/调度线程池
    private static final ExecutorService computeExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    // 阻塞 I/O 线程池（有界队列 + CallerRunsPolicy 做反压）
    private static final ThreadPoolExecutor blockingExecutor = new ThreadPoolExecutor(
            20,                                // corePoolSize
            40,                                // maximumPoolSize
            60L, TimeUnit.SECONDS,             // keepAliveTime
            new LinkedBlockingQueue<>(100),    // 有界队列长度
            Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

//    private final OkHttpClient okHttpClient = utils.utils.getOkHttpClient();
    private static final int CACHE_MAX_SIZE = 100;
    private static final String OPENAI_API_URI = "http://127.0.0.1:" + Main.port + "/v1/chat/completions";
    private static final String SYSTEMCONTENT =
            "Your role is that of a smart and creative assistant. " +
                    "Do not mention that you are a chatbot or AI assistant. " +
                    "Consider the terms when communicating: 1. The length of your response: Auto. " +
                    "2. The tone style of your speech: Default. " +
                    "This dialog box has an option to generate images. " +
                    "The function should be called only when the user explicitly requests it - " +
                    "for example, using any related words associated with image generation requests. " +
                    "In other cases - the call of the image generation function should not be called.";

    private static final Map<String, String> promptCache = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(CACHE_MAX_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_MAX_SIZE;
                }
            }
    );

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // CORS 支持
        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

        // 预检请求直接返回
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        // 仅允许 POST
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // 异步在 computeExecutor 上处理请求
        CompletableFuture.runAsync(() -> {
            try {
                String body = readAll(exchange.getRequestBody());
                JSONObject userInput = new JSONObject(body);

                // 必填项校验
                if (!userInput.has("prompt")) {
                    utils.utils.sendError(exchange, "缺少必需的字段: prompt");
                    return;
                }
                String userPrompt = userInput.optString("prompt", "").trim();
                if (userPrompt.isEmpty()) {
                    utils.utils.sendError(exchange, "Prompt 不能为空。");
                    return;
                }
                int n = userInput.optInt("n", 1);
                String responseFormat = userInput.optString("response_format", "");

                // （可选）缓存 & 润色逻辑省略...

                // 重试生成图片
                List<String> finalDownloadUrls = Collections.synchronizedList(new ArrayList<>());
                boolean success = attemptGenerateImages(userPrompt, n, 2 * n, finalDownloadUrls).join();

                if (success) {
                    // 构造返回 JSON
                    boolean isBase64 = "b64_json".equalsIgnoreCase(responseFormat);
                    JSONObject resp = new JSONObject();
                    resp.put("created", System.currentTimeMillis() / 1000);
                    JSONArray dataArr = new JSONArray();

                    for (String url : finalDownloadUrls) {
                        if (isBase64) {
                            byte[] bytes = downloadImage(url);
                            if (bytes == null) continue;
                            String b64 = Base64.getEncoder().encodeToString(bytes);
                            JSONObject obj = new JSONObject();
                            obj.put("b64_json", b64);
                            dataArr.put(obj);
                        } else {
                            JSONObject obj = new JSONObject();
                            obj.put("url", url);
                            dataArr.put(obj);
                        }
                    }
                    // 如果不足 n，则复制已有项
                    while (dataArr.length() < n && dataArr.length() > 0) {
                        for (int i = 0; i < dataArr.length() && dataArr.length() < n; i++) {
                            dataArr.put(dataArr.getJSONObject(i));
                        }
                    }
                    resp.put("data", dataArr);

                    byte[] respBytes = resp.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, respBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(respBytes);
                    }
                } else {
                    utils.utils.sendError(exchange, "无法生成足够数量的图像。");
                }
            } catch (JSONException je) {
                utils.utils.sendError(exchange, "JSON 解析错误: " + je.getMessage());
            } catch (Exception e) {
                utils.utils.sendError(exchange, "内部服务器错误: " + e.getMessage());
            }
        }, computeExecutor);
    }

    private CompletableFuture<Boolean> attemptGenerateImages(
            String prompt, int n, int maxAttempts, List<String> finalDownloadUrls) {
        return CompletableFuture.supplyAsync(() -> {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                int needed = n - finalDownloadUrls.size();
                if (needed <= 0) break;

                List<CompletableFuture<String>> futures = new ArrayList<>();
                for (int i = 0; i < needed; i++) {
                    // 阻塞操作跑在 blockingExecutor
                    CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
                        try {
                            return doSingleImageCall(prompt);
                        } catch (Exception e) {
                            e.printStackTrace();
                            return null;
                        }
                    }, blockingExecutor);
                    futures.add(f);
                }

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                for (var f : futures) {
                    try {
                        String url = f.get();
                        if (url != null && !url.isEmpty()) {
                            finalDownloadUrls.add(url);
                        }
                    } catch (Exception ignored) {}
                }
                if (finalDownloadUrls.size() >= n) {
                    return true;
                }
            }
            return finalDownloadUrls.size() >= n;
        }, computeExecutor);
    }

    /** 将所有阻塞 HTTP/SSE 读取集中在这里 **/
    private String doSingleImageCall(String userPrompt) throws IOException, JSONException {
        // 构造请求 JSON
        JSONObject json = new JSONObject();
        json.put("function_image_gen", true);
        json.put("function_web_search", true);
        json.put("image_aspect_ratio", "1:1");
        json.put("image_style", "anime");
        json.put("max_tokens", 8000);
        json.put("web_search_engine", "auto");
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", SYSTEMCONTENT));
        msgs.put(new JSONObject().put("role", "user").put("content", "Draw: " + userPrompt));
        json.put("messages", msgs);
        json.put("model", "claude-3-5-sonnet");
        json.put("source", "chat/pro_image");

        Request req = utils.utils.buildRequest(
                json.toString().getBytes(StandardCharsets.UTF_8),
                "/chats/stream"
        );
        Call call = utils.utils.getOkHttpClient().newCall(req);

        try (Response resp = call.execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return null;
            ResponseBody body = resp.body();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8)
            );
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;
                    JSONObject part = new JSONObject(data);
                    if (part.has("choices")) {
                        JSONArray choices = part.getJSONArray("choices");
                        for (int i = 0; i < choices.length(); i++) {
                            JSONObject delta = choices.getJSONObject(i).optJSONObject("delta");
                            if (delta != null && delta.has("content")) {
                                sb.append(delta.getString("content"));
                            }
                        }
                    }
                }
            }
            String markdown = sb.toString();
            String path = utils.utils.extractPathFromMarkdown(markdown)
                    .replace("https://spc.unk/", "");
            String storageUrl = "https://api.chaton.ai/storage/" + path;
            return utils.utils.fetchGetUrlFromStorage(storageUrl);
        }
    }

    /** 简单读取 InputStream 全文 **/
    private String readAll(InputStream is) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    /** 下载单张图片 **/
    private byte[] downloadImage(String imageUrl) {
        try {
            Request req = new Request.Builder()
                    .url(imageUrl)
                    .get()
                    .build();
            try (Response r = utils.utils.getOkHttpClient().newCall(req).execute()) {
                if (r.isSuccessful() && r.body() != null) {
                    return r.body().bytes();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
