#include <iostream> 
#include <string>
#include <sstream>
#include <iomanip>
#include <chrono>
#include <ctime>
#include "httplib.h"
#include <thread>
#include <vector>
#include "completion_handler.h"
#include "models_handler.h"
#include "image_generations_handler.h"
#include "text_to_speech_handler.h" // Include the TTS handler header
#include <locale>
#include <clocale>

// 更新后的日志记录函数，新增参数 remoteIP 和 remotePort
void logResponse(const std::string& endpoint, int status, const std::string& httpVersion, const std::string &remoteIP, int remotePort) {
    // 获取当前时间，并格式化为 YYYY-MM-DD HH:MM:SS
    auto now = std::chrono::system_clock::now();
    std::time_t now_time = std::chrono::system_clock::to_time_t(now);
    std::stringstream time_ss;
    time_ss << std::put_time(std::localtime(&now_time), "%Y-%m-%d %H:%M:%S");

    // 根据状态码设置颜色：绿色（成功/重定向），红色（错误）
    std::string colorCode;
    if (status >= 200 && status < 400) {
        colorCode = "\033[32m";  // 绿色
    } else if (status >= 400) {
        colorCode = "\033[31m";  // 红色
    } else {
        colorCode = "\033[0m";   // 默认颜色
    }

    // 输出日志，此处打印了 HTTP 版本、请求来源 IP 和端口
    std::cout << "[" << time_ss.str() << "] "
            << remoteIP << ":" << remotePort << " "
            << endpoint << " " << httpVersion << " " 
            << colorCode << status << "\033[0m" << std::endl;
}

int global_port = 0;

int main(int argc, char* argv[]) {
    TextToSpeechHandler ttsHandler; // Create an instance of the TTS handler
    std::setlocale(LC_ALL, "zh_CN.UTF-8");
    int port = 8080;
    if (argc > 1) {
        try {
            port = std::stoi(argv[1]);
        } catch (const std::exception& e) {
            std::cerr << "无效的端口号: " << e.what() << std::endl;
            return 1;
        }
    }
    global_port = port;

    // 实例化各处理类
    CompletionHandler completionHandler;
    ModelsHandler modelsHandler;
    ImageGenerationHandler imageGenerationHandler;

    // 循环尝试绑定端口，直到绑定成功
    while (true) {
        std::cout << "启动服务器，监听端口 " << port << "..." << std::endl;
        // 每次绑定前创建一个新的 Server 对象，并注册相应的路由处理函数
        httplib::Server server;

        // 路由：POST /v1/chat/completions
        server.Post("/v1/chat/completions", [&](const httplib::Request& req, httplib::Response& res) {
            // 调用实际处理函数
            completionHandler.handle(req, res);
            // 记录返回状态码及请求来源（IP 和端口）
            logResponse("/v1/chat/completions", res.status, req.version, req.remote_addr, req.remote_port);
        });

        // 路由：POST /v1/images/generations
        server.Post("/v1/images/generations", [&](const httplib::Request& req, httplib::Response& res) {
            imageGenerationHandler.handle(req, res);
            logResponse("/v1/images/generations", res.status, req.version, req.remote_addr, req.remote_port);
        });

        // 路由：GET /v1/models
        server.Get("/v1/models", [&](const httplib::Request& req, httplib::Response& res) {
            modelsHandler.handle(req, res);
            logResponse("/v1/models", res.status, req.version, req.remote_addr, req.remote_port);
        });

        // Set up the route for TTS
        server.Post("/v1/audio/speech", [&](const httplib::Request& req, httplib::Response& res) {
            ttsHandler.handle(req, res);
        });
        if (server.listen("0.0.0.0", port)) {
            // 如果 listen 返回 true，则服务器正常启动，进入阻塞服务状态
            break;
        } else {
            std::cerr << "端口 " << port << " 绑定失败，正在尝试下一个端口..." << std::endl;
            // 递增端口号，并更新 global_port
            port++;
            global_port = port;
        }
        
    }
    return 0;
}
