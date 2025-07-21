#ifndef TEXT_TO_SPEECH_HANDLER_H
#define TEXT_TO_SPEECH_HANDLER_H

namespace httplib { class Request; class Response; }
#include <nlohmann/json.hpp>
#include <iostream>
#include <thread>
#include <future>

using json = nlohmann::json;

class TextToSpeechHandler {
public:
    void handle(const httplib::Request& req, httplib::Response& res);
};

#endif // TEXT_TO_SPEECH_HANDLER_H
