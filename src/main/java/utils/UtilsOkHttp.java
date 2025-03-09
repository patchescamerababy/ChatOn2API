package utils;

import okhttp3.*;
import org.json.JSONObject;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class UtilsOkHttp {

    // 创建 OkHttpClient 实例（默认情况下 OkHttp 会自动复用连接，但我们这里也显式设置了 Connection 头）
    public static OkHttpClient okHttpClient = createOkHttpClient();

    public static OkHttpClient createOkHttpClient() {
        OkHttpClient okHttpClient1 = null;
        try {
            // 创建一个不验证证书的 TrustManager
            final X509TrustManager trustAllCertificates = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    // 不做任何检查，信任所有客户端证书
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    // 不做任何检查，信任所有服务器证书
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };

            // 创建 SSLContext，使用我们的 TrustManager
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAllCertificates}, null);

            // 创建代理
            Proxy proxy = utils.getSystemProxy();

//            // 如果需要代理认证
            Authenticator proxyAuthenticator = new Authenticator() {
                @Override
                public Request authenticate(Route route, Response response) throws IOException {
                    String credential = Credentials.basic("username", "password");
                    return response.request().newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build();
                }
            };

            // 创建 OkHttpClient
            okHttpClient1 = new OkHttpClient.Builder()
                    .proxy(proxy)  // 设置代理
//                    .proxyAuthenticator(proxyAuthenticator)  // 如果需要代理认证
                    .sslSocketFactory(sslContext.getSocketFactory(), trustAllCertificates)  // 设置 SSL
                    .hostnameVerifier(new HostnameVerifier() {
                        @Override
                        public boolean verify(String hostname, SSLSession session) {
                            return true;  // 不验证主机名
                        }
                    })
                    .connectTimeout(30, TimeUnit.SECONDS)  // 连接超时
                    .readTimeout(30, TimeUnit.SECONDS)     // 读取超时
                    .writeTimeout(30, TimeUnit.SECONDS)    // 写入超时
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("OkHttpClient 初始化失败", e);
        }
        return okHttpClient1;
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

    /**
     * 构建 OkHttp 请求，并设置必要的请求头，包括 "Connection: Keep-Alive"
     *
     * @param modifiedRequestBody 请求体
     * @param UA                  用户端版本号
     * @return 构建好的 Request 对象
     */
    public static Request buildRequest(byte[] modifiedRequestBody, String UA) {
        String date = getFormattedDate();
        String tmpToken = BearerTokenGenerator.GetBearer(modifiedRequestBody, "/chats/stream", date, "POST");
        return new Request.Builder()
                .url("https://api.chaton.ai/chats/stream")
                .addHeader("Date", date)
                .addHeader("Client-time-zone", "-05:00")
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
     * 备用方法：解析 Base64 图片数据后调用 chaton.ai 接口上传图片。
     * 此方法使用 BearerTokenGenerator、UtilsOkHttp 工具生成请求头及签名，
     * 并解析返回 JSON 中的 getUrl 字段作为图片访问地址。
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
            System.out.println("base64为png");
        } else if (dataUrl.startsWith("data:image/jpeg") || dataUrl.startsWith("data:image/jpg")) {
            extension = "jpg";
            System.out.println("base64为jpg");
        } else if (dataUrl.startsWith("data:image/gif")) {
            extension = "gif";
        } else if (dataUrl.startsWith("data:image/webp")) {
            extension = "webp";
        }

        // 生成 filename 为当前时间戳，例如 "1740205782603.jpg"
        String filename = System.currentTimeMillis() + "." + extension;

        // 获取当前格式化日期，例如 "2025-02-22T06:29:51Z"
        String formattedDate = UtilsOkHttp.getFormattedDate();
        // 生成用于上传图片的 Bearer Token，假设 bodyContent 为空字符串以保持签名与 Python 版一致
        // 注意：生成的 token 已包含 "Bearer " 前缀
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
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("备用上传失败，状态码: " + response.code());
            }

            String responseBody;
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("备用上传响应体为空");
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
                throw new IOException("备用上传响应中缺少 getUrl 字段");
            }
            // 返回 JSON 中的 getUrl 字段作为图片访问 URL
            return jsonResponse.getString("getUrl");
        }
    }
}
