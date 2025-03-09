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
}
