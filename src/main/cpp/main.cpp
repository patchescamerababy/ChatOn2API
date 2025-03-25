#include <iostream>
#include <string>
#include "httplib.h"
#include <thread>
#include <vector>
#include "completion_handler.h"
#include "models_handler.h"
#include "image_generations_handler.h" // Image generation interface
#include <locale>
#include <clocale>
int global_port = 0;

int main(int argc, char* argv[]) {
    std::setlocale(LC_ALL, "zh_CN.UTF-8");
    int port = 8080;
    if (argc > 1) {
        try {
            port = std::stoi(argv[1]);
        } catch (const std::exception& e) {
            std::cerr << "Invalid port number: " << e.what() << std::endl;
            return 1;
        }
    }

    std::cout << "Starting server on port " << port << "..." << std::endl;
    global_port = port;

    httplib::Server server;
    CompletionHandler completionHandler;
    ModelsHandler modelsHandler;
    ImageGenerationHandler imageGenerationHandler;

    server.Post("/v1/chat/completions", [&](const httplib::Request& req, httplib::Response& res) {
        completionHandler.handle(req, res);
    });

    server.Post("/v1/images/generations", [&](const httplib::Request& req, httplib::Response& res) {
        // Handle image generation request
        imageGenerationHandler.handle(req, res);
    });

    server.Get("/v1/models", [&](const httplib::Request& req, httplib::Response& res) {
        modelsHandler.handle(req, res);
    });

    // Add more endpoints as needed
    std::cout << "Server started. Listening on port " << port << "..." << std::endl;
    server.listen("0.0.0.0", port);
    return 0;
}