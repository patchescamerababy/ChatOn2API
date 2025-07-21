#include "httplib.h"
#include "models_handler.h"
#include <iostream>

std::vector<nlohmann::json> ModelsHandler::models;

ModelsHandler::ModelsHandler() {
    initialize_models();
}

void ModelsHandler::initialize_models() {
    if (!models.empty()) return;
    models.push_back({{"id", "gpt-4o"}, {"object", "model"}});
    models.push_back({{"id", "gpt-4o-mini"}, {"object", "model"}});
    models.push_back({{"id", "claude"}, {"object", "model"}});
    models.push_back({{"id", "claude-3-haiku"}, {"object", "model"}});
    models.push_back({{"id", "claude-3-5-sonnet"}, {"object", "model"}});
    models.push_back({ {"id", "claude-3-7-sonnet"}, {"object", "model"} });
    models.push_back({ {"id", "sonar-reasoning-pro"}, {"object", "model"} });
    models.push_back({{"id", "deepseek-r1"}, {"object", "model"}});
}

void ModelsHandler::handle(const httplib::Request& req, httplib::Response& res) {
    if (req.method != "GET") {
        res.status = 405;
        return;
    }
    try {
        nlohmann::json response_json = {
            {"object", "list"},
            {"data", models}
        };
        res.set_content(response_json.dump(), "application/json");
        res.status = 200;
    } catch (const std::exception& e) {
        std::cerr << "Error in ModelsHandler: " << e.what() << std::endl;
        res.status = 500;
    }
}
