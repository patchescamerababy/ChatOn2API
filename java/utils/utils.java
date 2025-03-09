package utils;
import java.net.InetSocketAddress;
import java.net.Proxy;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import com.sun.net.httpserver.HttpExchange;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONObject;

import java.io.*;

import java.nio.charset.StandardCharsets;

import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import java.util.zip.GZIPInputStream;

import static utils.BearerTokenGenerator.GetBearer;

public class utils {
    static {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "true");

    }
    /**
     * 获取格式化的当前 UTC 日期，格式为 yyyy-MM-dd'T'HH:mm:ss'Z'
     *
     * @return 格式化后的日期字符串
     */
    public static String getFormattedDate() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        String date = dateFormat.format(new Date());
        System.out.println("date = " + date);
        return date;
    }
    public static Proxy getSystemProxy() {
        String os = System.getProperty("os.name").toLowerCase();
        Proxy proxy = Proxy.NO_PROXY;

        try {
            if (os.contains("win")) {
                // Windows系统检查
                // 1. 首先检查系统属性
                String proxyHost = System.getProperty("http.proxyHost");
                String proxyPort = System.getProperty("http.proxyPort");

                if (proxyHost != null && proxyPort != null) {
                    try {
                        int port = Integer.parseInt(proxyPort);
                        if (port > 0 && port <= 65535) {
                            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, port));
                        }
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid proxy port number: " + proxyPort);
                    }
                }

                // 2. 检查环境变量
                String envProxy = System.getenv("HTTP_PROXY");
                if (envProxy != null && !envProxy.isEmpty()) {
                    try {
                        return parseProxyFromString(envProxy);
                    } catch (Exception e) {
                        System.err.println("Failed to parse HTTP_PROXY environment variable: " + e.getMessage());
                    }
                }

                // 3. 检查Windows注册表
                try {
                    // 检查代理是否启用
                    boolean proxyEnable = Advapi32Util.registryGetIntValue(
                            WinReg.HKEY_CURRENT_USER,
                            "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
                            "ProxyEnable"
                    ) != 0;

                    if (proxyEnable) {
                        // 获取代理服务器地址
                        String proxyServer = Advapi32Util.registryGetStringValue(
                                WinReg.HKEY_CURRENT_USER,
                                "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
                                "ProxyServer"
                        );

                        if (proxyServer != null && !proxyServer.isEmpty()) {
                            // 处理代理服务器地址
                            // 可能的格式：
                            // 1. host:port
                            // 2. http=host:port;https=host:port;ftp=host:port
                            if (proxyServer.contains("=")) {
                                // 包含多个协议的代理设置
                                for (String proxy0 : proxyServer.split(";")) {
                                    if (proxy0.startsWith("http=")) {
                                        proxyServer = proxy0.substring(5);
                                        break;
                                    }
                                }
                            }

                            try {
                                System.out.println("Detected system proxy: " + proxyServer);
                                return parseProxyFromString(proxyServer);
                            } catch (Exception e) {
                                System.err.println("Failed to parse registry proxy settings: " + e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Failed to read Windows registry: " + e.getMessage());
                }

            } else if (os.contains("nix") || os.contains("nux") || os.contains("mac")) {
                // Linux/MacOS系统检查
                String[] proxyEnvVars = {"https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY"};

                for (String envVar : proxyEnvVars) {
                    String proxyUrl = System.getenv(envVar);
                    if (proxyUrl != null && !proxyUrl.isEmpty()) {
                        try {
                            System.out.println("Detected system proxy: " + proxyUrl);
                            return parseProxyFromString(proxyUrl);
                        } catch (Exception e) {
                            System.err.println("Failed to parse " + envVar + ": " + e.getMessage());
                        }
                    }
                }
            } else {
                System.out.println("Unknown OS or no system proxy configuration found.");
            }
        } catch (Exception e) {
            System.err.println("Error while getting system proxy: " + e.getMessage());
        }
        return proxy;
    }

    private static Proxy parseProxyFromString(String proxyString) {
        proxyString = proxyString.trim().toLowerCase();
        proxyString = proxyString.replaceFirst("^(http|https)://", "");

        // 处理认证信息
        if (proxyString.contains("@")) {
            proxyString = proxyString.substring(proxyString.lastIndexOf("@") + 1);
        }

        String host;
        int port;

        if (proxyString.contains(":")) {
            String[] parts = proxyString.split(":");
            host = parts[0];
            String portStr = parts[1].split("/")[0];
            port = Integer.parseInt(portStr);
        } else {
            host = proxyString;
            port = 80; // 默认HTTP代理端口
        }

        if (!host.isEmpty() && port > 0 && port <= 65535) {
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
        }

        throw new IllegalArgumentException("Invalid proxy configuration");
    }


    /**
     * 发送错误响应
     */
    public static void sendError(HttpExchange exchange, String message,int HTTP_code) {
        try {
            JSONObject error = new JSONObject();
            error.put("error", message);
            byte[] bytes = error.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(HTTP_code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    /**
     * 发送错误响应
     */
    public static void sendError(HttpExchange exchange, String message) {
        try {
            JSONObject error = new JSONObject();
            error.put("error", message);
            byte[] bytes = error.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public static String fetchURL(String URL) {
        String Base64URL = "/urls/" + Base64.getEncoder().encodeToString(URL.getBytes());
        String url = "https://api.chaton.ai" + Base64URL;
        String formattedDate = utils.getFormattedDate();
        String timeZone = "-05:00";  // Replace with actual logic for fetching the client's time zone
        byte[] bodyContent = new byte[0];  // Assuming an empty body, replace as necessary

        String bearerToken = GetBearer(bodyContent, Base64URL, formattedDate, "GET");
        System.out.println(bearerToken);

        // 构建 OkHttp 请求
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", bearerToken)
                .addHeader("Date", formattedDate)
                .addHeader("Client-time-zone", timeZone)
                .addHeader("User-Agent", BearerTokenGenerator.UA)
                .addHeader("Accept-Language", "en-US")
                .addHeader("X-Cl-Options", "hb")
                .addHeader("Accept-Encoding", "gzip")
                .addHeader("Connection", "Keep-Alive")
                .get()
                .build();

        StringBuilder content = new StringBuilder();

        try {
            // 使用 OkHttpClient 发送请求
            Response response = UtilsOkHttp.okHttpClient.newCall(request).execute();

            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            // 获取响应体
            try (ResponseBody responseBody = response.body()) {
                if (responseBody == null) {
                    throw new IOException("Response body is null");
                }

                // 检查是否是gzip压缩
                String contentEncoding = response.header("Content-Encoding");
                InputStream inputStream;

                if ("gzip".equalsIgnoreCase(contentEncoding)) {
                    System.out.println("检测到GZIP压缩响应，正在解压...");
                    inputStream = new GZIPInputStream(responseBody.byteStream());
                } else {
                    System.out.println("响应未使用GZIP压缩");
                    inputStream = responseBody.byteStream();
                }

                // 读取流数据
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            if (line.equals("data: [DONE]")) {
                                System.out.println("Data Done");
                                continue;
                            }

                            String data = line.substring(6).trim();
                            JSONObject responseJson = new JSONObject(data);
                            JSONObject dataJson = responseJson.getJSONObject("data");
                            if (dataJson.has("content_delta")) {
                                content.append(dataJson.getString("content_delta"));
                                System.out.println("Content Delta: " + content);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return content.toString();
    }


    /**
     * 从 storage URL 获取 JSON 并提取 getUrl
     *
     * @param storageUrl 拼接后的 storage URL
     * @return getUrl 的值，如果失败则返回 null
     */
    public static String fetchGetUrlFromStorage(String storageUrl) {
        try {
            String date = utils.getFormattedDate();
            String path = storageUrl.replace("https://api.chaton.ai/", "");
            String bearer = BearerTokenGenerator.GetBearer(new byte[0], path, date, "GET");

            // 构建 OkHttp 请求
            Request request = new Request.Builder()
                    .url(storageUrl)
                    .addHeader("Date", date)
                    .addHeader("Client-time-zone", "-05:00")
                    .addHeader("Authorization", bearer)
                    .addHeader("User-Agent", BearerTokenGenerator.UA)
                    .addHeader("Accept-Language", "en-US")
                    .addHeader("X-Cl-Options", "hb")
                    .addHeader("Content-Type", "application/json; charset=UTF-8")
                    .addHeader("Accept-Encoding", "gzip")
                    .addHeader("Connection", "Keep-Alive")
                    .get()
                    .build();

            // 发送请求并获取响应
            Response response = UtilsOkHttp.okHttpClient.newCall(request).execute();

            if (!response.isSuccessful()) {
                System.err.println("获取 storage URL 失败，状态码: " + response.code());
                return null;
            }

            // 获取响应体
            String responseBody;
            try (ResponseBody body = response.body()) {
                if (body == null) {
                    System.err.println("响应体为空");
                    return null;
                }

                // 检查是否是gzip压缩
                String contentEncoding = response.header("Content-Encoding");
                if ("gzip".equalsIgnoreCase(contentEncoding)) {
                    // 使用GZIPInputStream解压数据
                    try (GZIPInputStream gzipInputStream = new GZIPInputStream(body.byteStream());
                         BufferedReader reader = new BufferedReader(new InputStreamReader(gzipInputStream, StandardCharsets.UTF_8))) {

                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        responseBody = sb.toString();
                        System.out.println("响应使用了GZIP压缩，已解压");
                    }
                } else {
                    // 没有使用gzip压缩，直接读取
                    responseBody = body.string();
                    System.out.println("响应没有使用GZIP压缩");
                }
            }

            System.out.println("响应内容: " + responseBody);

            // 解析 JSON 响应
            JSONObject jsonResponse = new JSONObject(responseBody);
            if (jsonResponse.has("getUrl")) {
                return jsonResponse.getString("getUrl");
            } else {
                System.err.println("JSON 响应中缺少 'getUrl' 字段。");
                return null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
