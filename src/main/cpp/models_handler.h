#ifndef MODELS_HANDLER_H
#define MODELS_HANDLER_H

#include <vector>
#include <string>
#include <nlohmann/json.hpp>
namespace httplib { class Request; class Response; }

class ModelsHandler {
public:
    ModelsHandler();
    void handle(const httplib::Request& req, httplib::Response& res);

private:
    static std::vector<nlohmann::json> models;
    void initialize_models();
};

#endif // MODELS_HANDLER_H
