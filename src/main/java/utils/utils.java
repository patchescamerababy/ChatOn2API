package utils;
import java.net.*;


import okhttp3.*;
import org.json.JSONObject;

import javax.net.ssl.*;
import java.io.*;

import java.nio.charset.StandardCharsets;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.*;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
//import utils.BearerTokenGenerator;
import static utils.BearerTokenGenerator.GetBearer;

public class utils {
    public static final String timeZone = "-04:00";
    /**
     * 获取格式化的当前 UTC 日期，格式为 yyyy-MM-dd'T'HH:mm:ss'Z'
     *
     * @return 格式化后的日期字符串
     */
    public static String getFormattedDate() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        String date = dateFormat.format(new Date());
        return date;
    }

    /**
     * 方法：解析 Base64 图片数据后调用 api.chaton.ai 接口上传图片。。
     *
     * @param dataUrl Base64 图片数据字符串
     * @return 上传成功后返回的图片 URL
     * @throws IOException 网络或解析异常时抛出
     */
    public static String uploadImage(String dataUrl) throws IOException {
        // 解析 Base64 数据并解码
        String base64Data = dataUrl.substring(dataUrl.indexOf("base64,") + 7);
        byte[] imageBytes = Base64.getDecoder().decode(base64Data);

        // 判断图片格式，确定扩展名
        String extension = "jpg"; // 默认扩展名
        if (dataUrl.startsWith("data:image/png")) {
            extension = "png";
        } else if (dataUrl.startsWith("data:image/jpeg") || dataUrl.startsWith("data:image/jpg")) {
            extension = "jpg";
        } else if (dataUrl.startsWith("data:image/gif")) {
            extension = "gif";
        } else if (dataUrl.startsWith("data:image/webp")) {
            extension = "webp";
        }

        // 生成 filename 为当前时间戳，例如 "1740205782603.jpg"
        String filename = System.currentTimeMillis() + "." + extension;
        String formattedDate = utils.getFormattedDate();
        String uploadBearerToken = BearerTokenGenerator.GetBearer(new byte[0], "/storage/upload", formattedDate, "POST");

        // 如果扩展名为 "jpg"，Content-Type 必须设置为 "image/jpeg"
        String contentType = extension.equals("jpg") ? "jpeg" : extension;

        // 构建 multipart/form-data 请求体
        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename,
                        RequestBody.create(imageBytes, MediaType.parse("image/" + contentType)))
                .build();

        // 构建上传图片的 HTTP 请求
        Request request = new Request.Builder()
                .url("https://api.chaton.ai/storage/upload")
                .header("Date", formattedDate)
                .header("Client-time-zone", "-05:00")
                .header("Authorization", uploadBearerToken)
                .header("User-Agent", BearerTokenGenerator.UA)
                .header("Accept-language", "en-US")
                .header("X-Cl-Options", "hb")
                .header("Content-Type", requestBody.contentType().toString())
                .header("Accept-Encoding", "gzip")
                .post(requestBody)
                .build();

        // 发送请求并解析响应
        try (Response response = getOkHttpClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("上传失败，状态码: " + response.code());
            }

            String responseBody;
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("上传响应体为空");
            }
            String contentEncoding = response.header("Content-Encoding", "");
            if ("gzip".equalsIgnoreCase(contentEncoding)) {
                // 处理 gzip 压缩的响应
                try (GZIPInputStream gzipIn = new GZIPInputStream(new ByteArrayInputStream(body.bytes()));
                     InputStreamReader isr = new InputStreamReader(gzipIn, StandardCharsets.UTF_8);
                     BufferedReader br = new BufferedReader(isr)) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    responseBody = sb.toString();
                }
            } else {
                responseBody = body.string();
            }

            JSONObject jsonResponse = new JSONObject(responseBody);
            if (!jsonResponse.has("getUrl")) {
                throw new IOException("上传响应中缺少 getUrl 字段");
            }
            // 返回 JSON 中的 getUrl 字段作为图片访问 URL
            return jsonResponse.getString("getUrl");
        }
    }
    /**
     * 构建 OkHttp 请求，并设置必要的请求头，包括 "Connection: Keep-Alive"
     *
     * @param modifiedRequestBody 请求体
     * @param UA                  用户端版本号
     * @return 构建好的 Request 对象
     */
    public static Request buildRequest(byte[] modifiedRequestBody,String path, String UA) {
        String date = utils.getFormattedDate();
        String tmpToken = BearerTokenGenerator.GetBearer(modifiedRequestBody, path, date, "POST");
        return new Request.Builder()
                .url("https://api.chaton.ai"+path)
                .addHeader("Date", date)
                .addHeader("Client-time-zone", utils.timeZone)
                .addHeader("Authorization", tmpToken)
                .addHeader("User-Agent", "ChatOn_Android/" + UA)
                .addHeader("Accept-Language", "en-US")
                .addHeader("X-Cl-Options", "hb")
                .addHeader("Content-Type", "application/json; charset=UTF-8")
                .addHeader("Accept-Encoding", "gzip")
                .addHeader("Connection", "Keep-Alive")
                .post(RequestBody.create(modifiedRequestBody, MediaType.get("application/json; charset=utf-8")))
                .build();
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
//        String timeZone = "-04:00";  // Replace with actual logic for fetching the client's time zone
        byte[] bodyContent = new byte[0];  // Assuming an empty body, replace as necessary

        String bearerToken = GetBearer(bodyContent, Base64URL, formattedDate, "GET");
//        System.out.println(bearerToken);

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
            Response response = utils.getOkHttpClient().newCall(request).execute();

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
                InputStream inputStream = "gzip".equalsIgnoreCase(contentEncoding) ?
                        new GZIPInputStream(responseBody.byteStream()) :
                        responseBody.byteStream();



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
//                                System.out.println("Content Delta: " + content);
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
                    .addHeader("Client-time-zone", timeZone)
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
            Response response = getOkHttpClient().newCall(request).execute();

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
    /**
     * 提取 Markdown 图片链接中的路径
     *
     * @param markdown 图片的 Markdown 语法字符串
     * @return 提取出的路径，如果失败则返回 null
     */
    public static String extractPathFromMarkdown(String markdown) {
        // 正则表达式匹配 ![Image](URL)
        Pattern pattern = Pattern.compile("!\\[.*?\\]\\((.*?)\\)");
        Matcher matcher = pattern.matcher(markdown);

        // 如果找到了匹配的路径，返回第一个URL
        if (matcher.find()) {
            return matcher.group(1);
        }

        // 如果没有找到匹配，返回null
        return null;
    }
    // 不在定义时直接初始化，而是置为 null
    private static volatile OkHttpClient okHttpClient = null;

    /**
     * 通过双重检查锁确保在运行时第一次调用时才创建实例
     */
    public static OkHttpClient getOkHttpClient() {
        if (okHttpClient == null) {
            synchronized (utils.class) {
                if (okHttpClient == null) {
                    okHttpClient = createOkHttpClient();
                }
            }
        }
        return okHttpClient;
//        return createOkHttpClient();
    }
    /**
     * 调用 reg.exe 读取注册表中某个键值（以字符串形式返回）。
     * 兼容 Windows XP 及以上。
     *
     * @param hive  根键名："HKCU", "HKLM" 等
     * @param path  子路径，例如 "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
     * @param key   值名称，例如 "ProxyEnable" 或 "ProxyServer"
     * @return      如果存在则返回值（例如 "0x1"、"proxy.example.com:8080" 等），否则返回 null
     */
    public static String readRegistry(String hive, String path, String key) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("reg", "query",
                hive + "\\" + path,
                "/v", key);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "GBK"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith(key)) {
                    // 按空白分割，最后一段即为值
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        return parts[parts.length - 1];
                    }
                }
            }
        }
        return null;
    }

    /**
     * 解析代理字符串，支持：
     *  - socks=host:port 或 socks5=host:port
     *  - http=host:port、https=host:port
     *  - 纯 host:port（默认 HTTP，端口若缺省则用 80）
     *
     * @param proxyStr  原始代理配置字符串
     * @return          java.net.Proxy 对象（Type 为 HTTP 或 SOCKS）
     */
    private static Proxy parseProxy(String proxyStr) {
        String s = proxyStr.trim();

        // 去掉协议前缀（http://、https://、socks://、socks5://）
        s = s.replaceFirst("(?i)^(http|https|socks5?)://", "");

        // 去掉用户认证信息
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1);
        }

        // 默认 HTTP
        Proxy.Type type = Proxy.Type.HTTP;

        // 检查多协议条目形式：socks=...;http=...;...
        if (s.contains("=") && s.contains(";")) {
            // 以分号拆分，优先找 socks= 或 socks5=
            for (String entry : s.split(";")) {
                String e = entry.trim().toLowerCase();
                if (e.startsWith("socks5=") || e.startsWith("socks=")) {
                    type = Proxy.Type.SOCKS;
                    s = entry.substring(entry.indexOf('=') + 1);
                    break;
                } else if (e.startsWith("http=")) {
                    // 后续若无 socks，才处理 http=
                    s = entry.substring(entry.indexOf('=') + 1);
                    type = Proxy.Type.HTTP;
                }
            }
        } else {
            // 单一条目且以 socks= 或 socks5= 开头
            String low = s.toLowerCase();
            if (low.startsWith("socks5=") || low.startsWith("socks=")) {
                type = Proxy.Type.SOCKS;
                s = s.substring(s.indexOf('=') + 1);
            }
        }

        // 拆分 host:port
        String host;
        int port = (type == Proxy.Type.SOCKS ? 1080 : 80);
        if (s.contains(":")) {
            String[] hp = s.split(":", 2);
            host = hp[0];
            try {
                port = Integer.parseInt(hp[1].replaceAll("/.*$", ""));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("无效的端口号: " + hp[1], ex);
            }
        } else {
            host = s;
        }

        return new Proxy(type, new InetSocketAddress(host, port));
    }

    /**
     * 获取 Windows 上的系统代理（HTTP / HTTPS / SOCKS5）。
     * 优先级：
     *   1. Java 系统属性 http.proxyHost/http.proxyPort
     *   2. 环境变量 HTTP_PROXY
     *   3. 注册表：ProxyEnable + ProxyServer
     */
    public static Proxy getWindowsProxy() {
        // 1. Java 系统属性
        String propHost = System.getProperty("http.proxyHost");
        String propPort = System.getProperty("http.proxyPort");
        if (propHost != null && propPort != null) {
            try {
                int port = Integer.parseInt(propPort);
                if (port > 0 && port <= 65535) {
                    return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(propHost, port));
                }
            } catch (NumberFormatException ignored) { }
        }

        // 2. 环境变量
        String env = System.getenv("HTTP_PROXY");
        if (env != null && !env.isEmpty()) {
            return parseProxy(env);
        }

        // 3. 注册表
        String hive = "HKCU";
        String path = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
        try {
            String enable = readRegistry(hive, path, "ProxyEnable");
            if ("0x1".equalsIgnoreCase(enable) || "1".equals(enable)) {
                String server = readRegistry(hive, path, "ProxyServer");
                if (server != null && !server.isEmpty()) {
                    System.out.println("Detected system proxy from Registry: " + server);
                    return parseProxy(server);
                }
            }
        } catch (IOException e) {
            System.err.println("Read Registry Failed: " + e.getMessage());
        }

        return Proxy.NO_PROXY;
    }

    /**
     * 获取 Unix-like (Linux/macOS) 系统代理（HTTP / HTTPS / SOCKS5）。
     * 检查环境变量（优先级由上至下）：
     *   socks5_proxy, SOCKS5_PROXY,
     *   socks_proxy,  SOCKS_PROXY,
     *   all_proxy,    ALL_PROXY,
     *   https_proxy,  HTTPS_PROXY,
     *   http_proxy,   HTTP_PROXY
     */
    public static Proxy getUnixProxy() {
        String[] vars = {
                "socks5_proxy", "SOCKS5_PROXY",
                "socks_proxy",  "SOCKS_PROXY",
                "all_proxy",    "ALL_PROXY",
                "https_proxy",  "HTTPS_PROXY",
                "http_proxy",   "HTTP_PROXY"
        };
        for (String env : vars) {
            String val = System.getenv(env);
            if (val != null && !val.isEmpty()) {
                return parseProxy(val);
            }
        }
        return Proxy.NO_PROXY;
    }

    /**
     * 检测当前操作系统并返回对应的系统代理设置。
     */
    public static Proxy getSystemProxy() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return getWindowsProxy();
        } else {
            return getUnixProxy();
        }
    }

    private static OkHttpClient createOkHttpClient() {
        try {
            // 创建 OkHttpClient
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)  // 连接超时
                    .readTimeout(60, TimeUnit.SECONDS)     // 读取超时
                    .writeTimeout(60, TimeUnit.SECONDS)    // 写入超时
                    .proxy(getSystemProxy())  // 设置系统代理
                    .build();
            return client;
        } catch (Exception e) {
            throw new RuntimeException("OkHttpClient 初始化失败", e);
        }
    }


}
