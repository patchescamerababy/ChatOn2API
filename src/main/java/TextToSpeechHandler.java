import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import utils.BearerTokenGenerator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TextToSpeechHandler implements HttpHandler {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // 1. CORS 预处理
        Headers respHeaders = exchange.getResponseHeaders();
        respHeaders.add("Access-Control-Allow-Origin", "*");
        respHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        respHeaders.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

        String method = exchange.getRequestMethod().toUpperCase();
        if ("OPTIONS".equals(method)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if ("GET".equals(method)) {
            // 返回简单欢迎页
            String html = "<html><head><title>欢迎使用API</title></head><body>"
                    + "<h1>欢迎使用API</h1>"
                    + "<p>此 API 用于与 ChatGPT / Claude 模型交互。您可以发送消息给模型并接收响应。</p>"
                    + "</body></html>";
            byte[] htmlBytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, htmlBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(htmlBytes);
            }
            return;
        }
        if (!"POST".equals(method)) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }


            try (InputStream clientIs = exchange.getRequestBody()) {
                // 2.1 读取并解析客户端 JSON
                String jsonText = new BufferedReader(new InputStreamReader(clientIs, StandardCharsets.UTF_8))
                        .lines().reduce("", (accumulator, actual) -> accumulator + actual);

                // 用 Jackson 解析 JSON
                Map<String, Object> inJson = objectMapper.readValue(jsonText, Map.class);
                System.out.println("Text To Speech 收到的请求: \n" + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(inJson));
                String inputText = (String) inJson.get("input");
                String format = inJson.getOrDefault("response_format", "mp3").toString();
                String voice = inJson.getOrDefault("voice", "nova").toString();
                String model = inJson.getOrDefault("model", "tts-1-hd").toString();

                if (!model.startsWith("tts-1")) {
                    model = "tts-1-hd";
                }
                int speed = 1;
                if (inJson.containsKey("speed")) {
                    Object speedObj = inJson.get("speed");
                    if (speedObj instanceof Number) {
                        speed = ((Number) speedObj).intValue();
                    } else {
                        try {
                            speed = Integer.parseInt(speedObj.toString());
                        } catch (Exception ignore) {}
                    }
                }


                Map<String, Object> outJson = new HashMap<>();
                outJson.put("input", inputText);
                outJson.put("response_format", format);
                outJson.put("voice", voice);
                outJson.put("model", model);
                outJson.put("speed", speed);
                if (inJson.containsKey("stream")) {
                    outJson.put("stream", Boolean.parseBoolean(inJson.get("stream").toString()));
                }
                byte[] bodyBytes = objectMapper.writeValueAsBytes(outJson);

                Request ttsRequest = utils.utils.buildRequest(bodyBytes, "/audio/speech");
                OkHttpClient client = utils.utils.getOkHttpClient();

                client.newCall(ttsRequest).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NotNull Call call, @NotNull IOException e) {
                        e.printStackTrace();
                    }

                    @Override
                    public void onResponse(@NotNull Call call, @NotNull Response ttsResp) {
                        if (!ttsResp.isSuccessful() || ttsResp.body() == null) {
                            System.err.println("TTS 服务返回错误，code=" + ttsResp.code());
                            return;
                        }

                        // 设置响应头，使用 chunked 传输
                        Headers hdrs = exchange.getResponseHeaders();
                        hdrs.add("Content-Type", "audio/mpeg");
                        hdrs.add("Accept-Ranges", "bytes");
                        hdrs.add("Content-Disposition", "attachment; filename=\"output.mp3\"");
                        try {
                            // length=0 即启用 chunked 模式
                            exchange.sendResponseHeaders(200, 0);
                            InputStream ttsStream = ttsResp.body().byteStream();
                            OutputStream clientOs = exchange.getResponseBody();

                            // 使用较小的缓冲区以减少延迟
                            byte[] buffer = new byte[1024];
                            int len;
                            while ((len = ttsStream.read(buffer)) != -1) {
                                clientOs.write(buffer, 0, len);
                                clientOs.flush(); // 立即刷新，确保数据尽快发送到客户端
                            }
                        } catch (IOException ioe) {
                            ioe.printStackTrace();
                        } finally {
                            try {
                                exchange.getResponseBody().close();
                            } catch (IOException ignored) {}
                        }
                    }

                });

            } catch (Exception ex) {
                ex.printStackTrace();
            }
    }
}
