import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.*;

import com.sun.net.httpserver.*;




public class Main {
    public static int port = 80;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static HttpServer createHttpServer(int initialPort) throws IOException {

        int port = initialPort;
        HttpServer server = null;

        while (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
                System.out.println("Server started on port " + port);
                Main.port = port;
            } catch (BindException e) {
                if (port < 65535) {
                    System.err.println("Port " + port + " is already in use. Trying port " + (port + 1));
                    ++port;
                } else {
                    try {
                        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
                        System.out.println("Server started on port " + port);
                        Main.port = port;
                    } catch (BindException e2) {
                        System.err.println("Port " + port + " is already in use. Trying port " + (port - 1));
                        --port;
                        if (port < 0) {
                            System.err.println("No available ports found.");
                            System.exit(1);
                        }
                    }
                }
            }
        }
        return server;
    }

    public static void main(String[] args) throws IOException {
        // 判断当前控制台的的编码方式 chcp
        if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            //执行cmd chcp 65001
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "chcp 65001");
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.forName("GBK")))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }
        }
        try {
            OutputStream os = System.out;  // 获取 System.out 的 OutputStream
            PrintStream ps = new PrintStream(os, true, "GBK");
            System.setOut(ps);  // 设置新的 System.out 输出流
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        int port = 80;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        HttpServer server = createHttpServer(port);
        server.createContext("/v1/chat/completions", new CompletionHandler());
        server.createContext("/v1/images/generations", new TextToImageHandler());
        server.createContext("/v1/models", new ModelsHandler());

        server.setExecutor(executor);
        server.start();
    }
}
