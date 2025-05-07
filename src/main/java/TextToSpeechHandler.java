import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import utils.BearerTokenGenerator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TextToSpeechHandler implements HttpHandler {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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

        // 2. 异步读取请求、调用下游 TTS、并流式返回
        CompletableFuture.runAsync(() -> {
            try (InputStream clientIs = exchange.getRequestBody()) {
                // 2.1 读取并解析客户端 JSON
                String jsonText = new BufferedReader(new InputStreamReader(clientIs, StandardCharsets.UTF_8))
                        .lines().reduce("", (accumulator, actual) -> accumulator + actual);

                JSONObject inJson = new JSONObject(jsonText);
                System.out.println("Text To Speech 收到的请求: \n" + inJson.toString());
                String inputText = inJson.getString("input");
                String format   = inJson.optString("response_format", "mp3");
                String voice = inJson.optString("voice","nova");
                String model = inJson.optString("model", "tts-1-hd");


                if(!model.startsWith("tts-1")){
                    model = "tts-1-hd";
                }
                int speed = inJson.optInt("speed", 1);
                // 2.2 构造并发起到 TTS 服务的请求
                JSONObject outJson = new JSONObject();
                outJson.put("input", inputText);
                outJson.put("response_format", format);
                outJson.put("voice", voice);
                outJson.put("model", model);
                outJson.put("speed", speed);
                if(inJson.has("stream")) {
                    boolean stream = inJson.optBoolean("stream", false);
                    outJson.put("stream", stream);
                }
                byte[] bodyBytes = outJson.toString().getBytes(StandardCharsets.UTF_8);

                Request ttsRequest = utils.utils.buildRequest(bodyBytes, "/audio/speech");
                OkHttpClient client  = utils.utils.getOkHttpClient();

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

                        // 2.3 设置响应头，使用 chunked 传输
                        Headers hdrs = exchange.getResponseHeaders();
                        hdrs.add("Content-Type", "audio/mpeg");
                        hdrs.add("Accept-Ranges", "bytes");
                        hdrs.add("Content-Disposition", "attachment; filename=\"output.mp3\"");
                        try {
                            // length=0 即启用 chunked 模式
                            exchange.sendResponseHeaders(200, 0);
                            InputStream ttsStream = ttsResp.body().byteStream();
                            OutputStream clientOs = exchange.getResponseBody();

                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = ttsStream.read(buffer)) != -1) {
                                clientOs.write(buffer, 0, len);
                            }
                            clientOs.flush();
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
        }, executor);
    }
}
