#ifndef IMAGE_GENERATIONS_HANDLER_H
#define IMAGE_GENERATIONS_HANDLER_H

#include <string>
#include "httplib.h"
#include <nlohmann/json.hpp>

class ImageGenerationHandler {
public:
    ImageGenerationHandler();
    void handle(const httplib::Request& req, httplib::Response& res);
};

#endif // IMAGE_GENERATIONS_HANDLER_H
