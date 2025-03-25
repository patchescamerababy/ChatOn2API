// completion_handler.h
#ifndef COMPLETION_HANDLER_H
#define COMPLETION_HANDLER_H

#include <string>
#include "httplib.h"
#include <nlohmann/json.hpp>
#include <curl/curl.h>
#include <utility>

class CompletionHandler {
public:
    CompletionHandler();
    void handle(const httplib::Request& req, httplib::Response& res);

private:
    bool hasImage_;
    CURL* createCompletionConnection(struct curl_slist* headers, const std::string& jsonBody);
    void handleOptionsRequest(httplib::Response& res);
    void handleGetRequest(httplib::Response& res);
    void handlePostRequest(const httplib::Request& req, httplib::Response& res);
    // 解析 SSE 数据，返回聚合后的内容和 completion tokens 数
    std::pair<std::string, int> aggregateSSEResponse(const std::string& sseResponse);
    // 根据聚合结果构造最终 JSON 字符串
    std::string buildFinalJson(const std::string& aggregatedContent, int completionTokens, const nlohmann::json& requestJson);
    // 消息处理（文本、图片等）
    nlohmann::json processMessages(const nlohmann::json& messages);
    // 两种模式的处理分支
    void handleStreamResponse(const httplib::Request& req, httplib::Response& res, const nlohmann::json& requestJson);
    void handleNonStreamResponse(const httplib::Request& req, httplib::Response& res, const nlohmann::json& requestJson);
};

// 用于非流式读取的回调函数
size_t CompletionResponseCallback(void* contents, size_t size, size_t nmemb, std::string* s);

#endif // COMPLETION_HANDLER_H
