import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import utils.BearerTokenGenerator;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.io.ByteArrayInputStream;

import static utils.utils.sendError;
import utils.utils.*;
/**
 * 处理聊天补全请求的处理器，使用 OkHttp 替代 HttpClient
 */
public class CompletionHandler implements HttpHandler {

    // OkHttp 客户端实例
//    private final OkHttpClient okHttpClient = utils.utils.getOkHttpClient();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // 设置 CORS 头
        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

        String requestMethod = exchange.getRequestMethod().toUpperCase();

        if (requestMethod.equals("OPTIONS")) {
            // 处理预检请求
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if ("GET".equals(requestMethod)) {
            // 返回欢迎页面
            String response = "<html><head><title>欢迎使用API</title></head><body><h1>欢迎使用API</h1><p>此 API 用于与 ChatGPT / Claude 模型交互。您可以发送消息给模型并接收响应。</p></body></html>";

            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        if (!"POST".equals(requestMethod)) {
            // 不支持的方法
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // 异步处理请求
        CompletableFuture.runAsync(() -> {
            // 读取请求体
            try {
                // 读取请求体
                InputStream is = exchange.getRequestBody();
                String requestBody = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .lines()
                        .reduce("", (acc, line) -> acc + line);



                JSONObject requestJson = new JSONObject(requestBody);

                StringBuilder contentBuilder = new StringBuilder();
                JSONArray messages = requestJson.optJSONArray("messages");
                int maxTokens = requestJson.optInt("max_tokens", 8000);
                String model = requestJson.optString("model", "gpt-4o");

                boolean isStream = requestJson.optBoolean("stream", false);
                boolean hasImage = false;
                boolean hasURL = false;
                if (messages != null) {
                    JSONArray processedMessages = new JSONArray();
                    Iterator<Object> iterator = messages.iterator();
                    while (iterator.hasNext()) {
                        JSONObject message = (JSONObject) iterator.next();
                        String role = message.optString("role", "").toLowerCase();

                        if (message.has("content")) {
                            Object contentObj = message.get("content");

                            // 定义一个链表用于存储所有图片 URL
                            LinkedList<String> imageUrlList = new LinkedList<>();
                            // 用于存储所有图片上传的异步任务
                            List<CompletableFuture<Void>> uploadTasks = new ArrayList<>();

                            boolean messageHasImage = false;

                            if (contentObj instanceof JSONArray) {
                                JSONArray contentArray = (JSONArray) contentObj;
                                StringBuilder msgContentBuilder = new StringBuilder();
                                System.gc();
                                for (int j = 0; j < contentArray.length(); j++) {
                                    JSONObject contentItem = contentArray.getJSONObject(j);
                                    if (contentItem.has("type")) {

                                        String type = contentItem.getString("type");
                                        if (type.equals("text") && contentItem.has("text")) {

                                            // 处理文本内容
                                            String text = contentItem.getString("text");
                                            msgContentBuilder.append(text);
                                            if (j < contentArray.length() - 1) {
                                                msgContentBuilder.append(" ");
                                            }
                                        } else if (type.equals("image_url") && contentItem.has("image_url")) {
                                            JSONObject imageUrlObj = contentItem.getJSONObject("image_url");
                                            String dataUrl = imageUrlObj.getString("url");
                                            if (dataUrl.startsWith("data:image/")) {
                                                // 异步上传 Base64 编码的图片
                                                CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
                                                    try {
                                                        return utils.utils.uploadImage(dataUrl);
                                                    } catch (IOException e) {
                                                        throw new CompletionException(e);
                                                    }
                                                }).thenAccept(uploadedUrl -> {
                                                    System.out.println("图片已上传，URL: " + uploadedUrl);
                                                    // 注意：这里需要保证线程安全，避免多个线程同时写入链表
                                                    synchronized (imageUrlList) {
                                                        imageUrlList.add(uploadedUrl);
                                                    }
                                                });
                                                uploadTasks.add(future);
                                            } else {
                                                // 处理标准 URL 的图片，直接添加
                                                System.out.println("接收到标准图片 URL: " + dataUrl);
                                                imageUrlList.add(dataUrl);
                                            }
                                            // 标记此消息包含图片
                                            hasImage = true;
                                            messageHasImage = true;
                                        }
                                    }
                                }

                                // 等待当前消息中所有图片上传任务完成
                                if (!uploadTasks.isEmpty()) {
                                    CompletableFuture<Void> allUploads = CompletableFuture.allOf(
                                            uploadTasks.toArray(new CompletableFuture[0])
                                    );
                                    allUploads.join(); // 阻塞直到所有上传完成
                                }

                                // 如果链表中有图片 URL，则将其封装到 images 字段中
                                if (!imageUrlList.isEmpty()) {
                                    JSONArray imagesArray = new JSONArray();
                                    for (String url : imageUrlList) {
                                        JSONObject imageObj = new JSONObject();
                                        imageObj.put("data", url);
                                        imagesArray.put(imageObj);
                                    }
                                    message.put("images", imagesArray);
                                }

                                // 处理完 contentArray 后，设置消息的 content 字段
                                String extractedContent = msgContentBuilder.toString().trim();
                                if (extractedContent.isEmpty() && !messageHasImage) {
                                    iterator.remove();
                                    System.out.println("移除内容为空的消息。");
                                    continue;
                                } else {
                                    message.put("content", extractedContent);
                                    System.out.println("提取的内容: " + extractedContent);
                                }
                            } else if (contentObj instanceof String) {
                                // 处理纯文本内容
                                String contentStr = ((String) contentObj).trim();
                                if (contentStr.isEmpty()) {
                                    iterator.remove();
                                    System.out.println("移除内容为空的消息。");
                                    continue;
                                } else {
                                    message.put("content", contentStr);
                                }
                            } else {
                                // 移除不符合预期类型的消息
                                iterator.remove();
                                System.out.println("移除非预期类型的消息。");
                                continue;
                            }
                        }

                        if (role.equals("system") && message.has("content")) {
                            String systemContent = message.optString("content", "");
                            if (!systemContent.contains("This dialog contains a call to the web search function. Use it only when you need to get up-to-date data or data that is not in your training database.")) {
                                systemContent = "This dialog contains a call to the web search function. Use it only when you need to get up-to-date data or data that is not in your training database.\n" + systemContent;
                            }
                            contentBuilder.append(systemContent);
                            message.put("content", systemContent);
                        }

                        if (role.equals("user") && message.has("content")) {
                            String userContent = message.optString("content", "");
                            if (userContent.contains("http://") || userContent.contains("https://")) {
                                // 这是一个比较通用的 URL 正则
                                Pattern pattern = Pattern.compile(
                                        "(ht|f)tp(s?)\\:\\/\\/[0-9a-zA-Z]([-.\\w]*[0-9a-zA-Z])*(:(0-9)*)*(\\/?)([a-zA-Z0-9\\-\\.\\?\\,\\'\\/\\\\\\+&%\\$#_]*)?$"
                                );
                                Matcher matcher = pattern.matcher(userContent);
                                if (matcher.find()) {
                                    String url = matcher.group();


                                    // 只有指向公网的 URL 才处理
                                    if (isPublicURL(url)) {
                                        System.out.println("URL: " + url);
                                        hasURL = true;
                                        String fetched = utils.utils.fetchURL(url);
                                        message.put("content", userContent + "\n\n" + fetched);

                                    } else {
                                        hasURL = false;
                                        // 也可以选择给用户提示 “URL 属于内网/回环地址，已忽略”
                                    }
                                }
                            }
                        }

                        // 将处理后的消息添加到新数组中
                        processedMessages.put(message);
                    }

                    if (processedMessages.isEmpty()) {
                        sendError(exchange, "所有消息的内容均为空。");
                        return;
                    }

                    // 替换原始消息为处理后的消息
                    messages = processedMessages;
                }


                model = model.toLowerCase();
                // "claude-3.5-sonnet" 转换为 "claude-3-5-sonnet"
                model = model.equals("claude-3.5-sonnet") ? "claude-3-5-sonnet" : model;
                // "gpt 4o" 转换为 "gpt-4o"
                model = model.equals("gpt 4o") ? "gpt-4o" : model;
                model = model.startsWith("gpt") ? "gpt-4o" : model;


                // 构建新的请求 JSON，替换相关内容
                JSONObject newRequestJson = new JSONObject();
                if(requestJson.has("functions")){
                    JSONArray functions = requestJson.getJSONArray("functions");
                    newRequestJson.put("functions", functions);
                } else if (requestJson.has("function_call")) {
                    String functionCall = requestJson.getString("function_call");
                    newRequestJson.put("function_call", functionCall);
                }
                newRequestJson.put("max_tokens", maxTokens);
                newRequestJson.put("function_code_interpreter", true);
                newRequestJson.put("function_image_gen", true);
                newRequestJson.put("function_web_search", !hasURL);
//                newRequestJson.put("function_web_search", false);

                newRequestJson.put("web_search_engine", "auto");
                newRequestJson.put("model", model);
                newRequestJson.put("source", hasImage ? "chat/image_upload" : "chat/free");
                newRequestJson.put("messages", messages);

                String modifiedRequestBody = newRequestJson.toString();
                System.out.println("_________\n修改后的请求 JSON: \n" + newRequestJson.toString(4) + "\n");
                byte[] requestBodyBytes = modifiedRequestBody.getBytes(StandardCharsets.UTF_8);

                if (isStream) {
                    Request request = utils.utils.buildRequest(requestBodyBytes, "/chats/stream", BearerTokenGenerator.UA);
                    handleStreamResponse(exchange, request);
                } else {
                    Request requestNormal = utils.utils.buildRequest(requestBodyBytes, "/chats/text", BearerTokenGenerator.UA);
//                    Request requestNormal = utils.utils.buildRequest(requestBodyBytes, "/chats/stream", BearerTokenGenerator.UA);
                    handleNormalResponse(exchange, requestNormal);
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, "内部服务器错误: " + e.getMessage());
            }
        }, executor);

    }

    /**
     * 处理流式响应
     *
     * @param exchange 当前的 HttpExchange 对象
     * @param request  构建好的 Request 对象
     */
    private void handleStreamResponse(HttpExchange exchange, Request request) {
        OkHttpClient okHttpClient = utils.utils.getOkHttpClient();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                e.printStackTrace();
//                sendError(exchange, "请求失败: " + e.getMessage());
                handleStreamResponse(exchange, request);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response){
                try {
                    if (!response.isSuccessful()) {
                        sendError(exchange, "API 错误: " + response.code());
                        return;
                    }
                    Headers responseHeaders = exchange.getResponseHeaders();
                    responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8");
                    responseHeaders.add("Cache-Control", "no-cache");
                    responseHeaders.add("Connection", "keep-alive");
                    exchange.sendResponseHeaders(200, 0);

                    try (OutputStream os = exchange.getResponseBody()) {
                        long time = Instant.now().getEpochSecond();
                        StringBuilder finalContent = new StringBuilder();
                        boolean inImageMode = false;
                        StringBuilder imageMarkdownBuffer = new StringBuilder();

                        BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.startsWith("data: ")) {
                                continue;
                            }
                            String data = line.substring(6).trim();
                            if (data.isEmpty()) {
                                continue;
                            }

                            if (data.equals("[DONE]")) {
                                if (finalContent.toString().contains("![Image](https://spc.unk/")) {
                                    System.out.println("图片模式: " + imageMarkdownBuffer.toString());
                                    String extractedPath = utils.utils.extractPathFromMarkdown(finalContent.toString());

                                    if (extractedPath == null || extractedPath.isEmpty()) {
                                        System.out.println(" - 无法从 Markdown 中提取路径。");
                                        return;
                                    }

                                    extractedPath = extractedPath.replace("https://spc.unk/", "");
                                    System.out.println(" - 提取的路径: " + extractedPath);

                                    String storageUrl = "https://api.chaton.ai/storage/" + extractedPath;
                                    System.out.println(" - 存储URL: " + storageUrl);

                                    String finalDownloadUrl = utils.utils.fetchGetUrlFromStorage(storageUrl);
                                    JSONObject newJson = new JSONObject();
                                    newJson.put("id", generateId());
                                    newJson.put("object", "chat.completion.chunk");
                                    newJson.put("created", time);
                                    newJson.put("model", "gpt-4o");

                                    JSONArray choices = new JSONArray();
                                    JSONObject choice = new JSONObject();
                                    JSONObject delta = new JSONObject();

                                    delta.put("content", "\n\n![Image](" + finalDownloadUrl + ")\n");
                                    choice.put("delta", delta);
                                    choice.put("index", 0);
                                    choice.put("finish_reason", JSONObject.NULL);
                                    choices.put(choice);
                                    newJson.put("choices", choices);

                                    String newLine = "data: " + newJson.toString() + "\n\n";
                                    os.write(newLine.getBytes(StandardCharsets.UTF_8));
                                    os.flush();
                                    String DONE = "data: [DONE]\n\n";
                                    os.write(DONE.getBytes(StandardCharsets.UTF_8));
                                    os.flush();
                                }
                            } else {
                                try {
                                    JSONObject json = new JSONObject(data);
                                    if (shouldFilterOut(json)) {
                                        continue;
                                    }

                                    if (json.has("data") && json.getJSONObject("data").has("web")) {
                                        JSONObject webData = json.getJSONObject("data").getJSONObject("web");
                                        if (webData.has("sources")) {
                                            JSONArray sources = webData.getJSONArray("sources");
                                            String[] urlsList = new String[sources.length()];
                                            String[] titlesList = new String[sources.length()];
                                            for (int i = 0; i < sources.length(); i++) {
                                                JSONObject source = sources.getJSONObject(i);
                                                if (source.has("url")) {
                                                    urlsList[i] = source.getString("url");
                                                }
                                                if (source.has("title")) {
                                                    titlesList[i] = source.getString("title");
                                                }
                                            }

                                            JSONObject newJson = new JSONObject();
                                            newJson.put("id", generateId());
                                            newJson.put("object", "chat.completion.chunk");
                                            newJson.put("created", time);
                                            newJson.put("model", "gpt-4o");
                                            JSONArray choices = new JSONArray();
                                            JSONObject choice = new JSONObject();
                                            JSONObject delta = new JSONObject();
                                            StringBuilder content = new StringBuilder();
                                            for (int i = 0; i < sources.length(); i++) {
                                                content.append("\n#### ").append(titlesList[i]).append("\n").append(urlsList[i]).append("\n");
                                            }
                                            delta.put("content", content);

                                            choice.put("delta", delta);
                                            choice.put("index", 0);
                                            choice.put("finish_reason", JSONObject.NULL);
                                            choices.put(choice);

                                            newJson.put("choices", choices);

                                            String newLine = "data: " + newJson + "\n\n";
                                            os.write(newLine.getBytes(StandardCharsets.UTF_8));
                                            os.flush();
                                        }
                                    } else if (json.has("choices")) {
                                        JSONArray choices = json.getJSONArray("choices");
                                        for (int i = 0; i < choices.length(); i++) {
                                            JSONObject choice = choices.getJSONObject(0);
                                            JSONObject delta = choice.optJSONObject("delta");
                                            if (delta != null && delta.has("content") ) {
                                                String reasoning_content =null;
                                                String content = null;
                                                if(delta.get("content").equals(JSONObject.NULL) && delta.has("reasoning_content")){
                                                    if(!delta.get("reasoning_content").equals(JSONObject.NULL)){
                                                        reasoning_content = delta.getString("reasoning_content");
                                                    }
                                                    if(reasoning_content != null){
                                                        System.out.print(reasoning_content);
                                                    }

                                                }else{
                                                    content = delta.getString("content");
                                                }
                                                if(!delta.get("content").equals(JSONObject.NULL)) {
                                                    System.out.print(content);
                                                }
                                                finalContent.append(content);
                                                if (content != null && (content.contains("\n\n![") || content.contains("spc.unk"))) {
                                                    inImageMode = true;
                                                    imageMarkdownBuffer.append(content);
                                                    break;
                                                }

                                                if (inImageMode) {
                                                    imageMarkdownBuffer.append(content);
                                                    continue;
                                                }

                                                os.write((line + "\n\n").getBytes(StandardCharsets.UTF_8));
                                                os.flush();
                                            }
                                        }
                                    }else {
                                        os.write((line + "\n\n").getBytes(StandardCharsets.UTF_8));
                                        os.flush();
                                    }
                                } catch (JSONException e) {
                                    //System.err.println("JSON解析错误: " + e.getMessage());
                                    e.printStackTrace();
                                    System.out.println("data = " + data);
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    sendError(exchange, "响应发送失败: " + e.getMessage());
                }
            }
        });
    }

    /**
     * 判断是否需要过滤掉当前的 SSE 消息
     *
     * @param json 解析后的 JSON 对象
     * @return 如果需要过滤掉则返回 true，否则返回 false
     */
    private boolean shouldFilterOut(JSONObject json) {
        if (json.has("ping")) {
            return true;
        }
        if (json.has("data")) {
            JSONObject data = json.getJSONObject("data");
            if (data.has("analytics")) {
                return true;
            }
            return data.has("operation") && data.has("message");
        }
        return false;
    }

    /**
     * 生成随机的 ID
     *
     * @return 长度为 24 的随机字符串
     */
    private String generateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

//    /**
//     * 处理非流式响应
//     *
//     * @param exchange 当前的 HttpExchange 对象
//     * @param request  构建好的 Request 对象
//     * @param model    使用的模型名称
//     */
//    private void handleNormalResponse(HttpExchange exchange, Request request, String model) {
//    OkHttpClient okHttpClient = utils.utils.getOkHttpClient();
//        okHttpClient.newCall(request).enqueue(new Callback() {
//            @Override
//            public void onFailure(Call call, IOException e) {
//                e.printStackTrace();
//                sendError(exchange, "请求失败: " + e.getMessage());
//            }
//
//            @Override
//            public void onResponse(Call call, Response response) throws IOException {
//                try {
//                    if (!response.isSuccessful()) {
//                        sendError(exchange, "API 错误: " + response.code());
//                        return;
//                    }
//
//                    List<String> sseLines = new ArrayList<>();
//                    BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
//                    String line;
//                    while ((line = reader.readLine()) != null) {
//                        sseLines.add(line);
//                    }
//
//                    StringBuilder contentBuilder = new StringBuilder();
//                    int completionTokens = 0;
//
//                    for (String sseLine : sseLines) {
//                        if (sseLine.startsWith("data: ")) {
//                            String data = sseLine.substring(6).trim();
//                            if (data.equals("[DONE]")) {
//                                break;
//                            }
//                            try {
//                                JSONObject sseJson = new JSONObject(data);
//                                System.out.println(data);
//                                if (sseJson.has("choices")) {
//                                    JSONArray choices = sseJson.getJSONArray("choices");
//                                    for (int i = 0; i < choices.length(); i++) {
//                                        JSONObject choice = choices.getJSONObject(i);
//                                        if (choice.has("delta")) {
//                                            JSONObject delta = choice.getJSONObject("delta");
//                                            if (delta.has("content")) {
//                                                if(!delta.get("content").equals(JSONObject.NULL)){
//                                                    String content = delta.getString("content");
//                                                    contentBuilder.append(content);
//                                                    completionTokens += countTokens(content);
//                                                }
//                                            }
//                                        }
//                                    }
//                                }
//                            } catch (JSONException e) {
//                                System.err.println("JSON解析错误: " + e.getMessage());
//                                e.printStackTrace();
//                            }
//                        }
//                    }
//
//                    JSONObject openAIResponse = new JSONObject();
//                    openAIResponse.put("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""));
//                    openAIResponse.put("object", "chat.completion");
//                    openAIResponse.put("created", Instant.now().getEpochSecond());
//                    openAIResponse.put("model", model);
//
//                    JSONArray choicesArray = new JSONArray();
//                    JSONObject choiceObject = new JSONObject();
//                    choiceObject.put("index", 0);
//
//                    JSONObject messageObject = new JSONObject();
//                    messageObject.put("role", "assistant");
//                    messageObject.put("content", contentBuilder.toString());
//                    messageObject.put("refusal", JSONObject.NULL);
//
//                    choiceObject.put("message", messageObject);
//                    choiceObject.put("logprobs", JSONObject.NULL);
//                    choiceObject.put("finish_reason", "stop");
//                    choicesArray.put(choiceObject);
//
//                    openAIResponse.put("choices", choicesArray);
//
//                    JSONObject usageObject = new JSONObject();
//                    int promptTokens = countTokens(contentBuilder.toString());
//                    usageObject.put("prompt_tokens", promptTokens);
//                    usageObject.put("completion_tokens", completionTokens);
//                    usageObject.put("total_tokens", promptTokens + completionTokens);
//
//                    JSONObject promptTokensDetails = new JSONObject();
//                    promptTokensDetails.put("cached_tokens", 0);
//                    promptTokensDetails.put("audio_tokens", 0);
//                    usageObject.put("prompt_tokens_details", promptTokensDetails);
//
//                    JSONObject completionTokensDetails = new JSONObject();
//                    completionTokensDetails.put("reasoning_tokens", 0);
//                    completionTokensDetails.put("audio_tokens", 0);
//                    completionTokensDetails.put("accepted_prediction_tokens", 0);
//                    completionTokensDetails.put("rejected_prediction_tokens", 0);
//                    usageObject.put("completion_tokens_details", completionTokensDetails);
//
//                    openAIResponse.put("usage", usageObject);
//                    openAIResponse.put("system_fingerprint", "fp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
//
//                    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
//                    String responseBody = openAIResponse.toString();
//                    exchange.sendResponseHeaders(200, responseBody.getBytes(StandardCharsets.UTF_8).length);
//                    try (OutputStream os = exchange.getResponseBody()) {
//                        os.write(responseBody.getBytes(StandardCharsets.UTF_8));
//                    }
//
//                } catch (Exception e) {
//                    e.printStackTrace();
//                    sendError(exchange, "处理响应时发生错误: " + e.getMessage());
//                }
//            }
//        });
//    }


    /**
     * 处理非流式响应
     *
     * @param exchange 当前的 HttpExchange 对象
     * @param request  构建好的 Request 对象
     */
    private void handleNormalResponse(HttpExchange exchange, Request request) {
        OkHttpClient okHttpClient = utils.utils.getOkHttpClient();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                e.printStackTrace();
                sendError(exchange, "请求失败: " + e.getMessage());
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                String responseBody = null;

                if (response.body() != null) {
                    // 检查响应是否使用gzip压缩
                    String contentEncoding = response.header("Content-Encoding");
                    if ("gzip".equalsIgnoreCase(contentEncoding)) {
//                        System.out.println("gzip");
                        // 使用GZIPInputStream解压数据
                        try (GZIPInputStream gzipInputStream = new GZIPInputStream(response.body().byteStream());
                             BufferedReader reader = new BufferedReader(new InputStreamReader(gzipInputStream, StandardCharsets.UTF_8))) {
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                sb.append(line);
                            }
                            responseBody = sb.toString();
                        }
                    } else {
//                        System.out.println("无gzip");
                        // 如果没有压缩，直接读取
                        responseBody = response.body().string();
                    }
                }
                System.out.println("responseBody = \n" + responseBody);

                if (responseBody != null) {
                    byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(responseBytes);
                        os.flush();
                    }
                }

                response.close();
            }
        });
    }
    public static int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        // 使用正则表达式按单词和标点符号分割
        String[] tokens = text.split("\\s+");

        int tokenCount = 0;
        for (String token : tokens) {
            // 使用正则表达式分割标点符号
            String[] subTokens = token.split("(?=[.,!?;:()\"'])|(?<=[.,!?;:()\"'])");
            for (String subToken : subTokens) {
                if (!subToken.trim().isEmpty()) {
                    tokenCount++;
                }
            }
        }

        return tokenCount;
    }
    /**
     * 判断一个 URL 是否指向公网地址（非私有、非回环、非链路本地、非任意本地）
     */
    public static boolean isPublicURL(String urlString) {
        try {
            URL url = new URL(urlString);
            String host = url.getHost();
            InetAddress addr = InetAddress.getByName(host);

            if (addr.isSiteLocalAddress()    // 10/8, 172.16/12, 192.168/16
                    || addr.isLoopbackAddress()    // 127/8
                    || addr.isLinkLocalAddress()   // 169.254/16
                    || addr.isAnyLocalAddress()    // 0.0.0.0
            ) {
                return false;
            }
            return true;
        } catch (MalformedURLException e) {
            // URL 语法不合法，拒绝
            return false;
        } catch (UnknownHostException e) {
            // 无法解析主机名，拒绝
            return false;
        }
    }
}
