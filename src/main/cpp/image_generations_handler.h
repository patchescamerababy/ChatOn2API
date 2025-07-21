#ifndef IMAGE_GENERATIONS_HANDLER_H
#define IMAGE_GENERATIONS_HANDLER_H

#include <string>
namespace httplib { class Request; class Response; }
#include <nlohmann/json.hpp>

class ImageGenerationHandler {
public:
    ImageGenerationHandler();
    void handle(const httplib::Request& req, httplib::Response& res);
};

#endif // IMAGE_GENERATIONS_HANDLER_H
