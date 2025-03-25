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
    // ���� SSE ���ݣ����ؾۺϺ�����ݺ� completion tokens ��
    std::pair<std::string, int> aggregateSSEResponse(const std::string& sseResponse);
    // ���ݾۺϽ���������� JSON �ַ���
    std::string buildFinalJson(const std::string& aggregatedContent, int completionTokens, const nlohmann::json& requestJson);
    // ��Ϣ�����ı���ͼƬ�ȣ�
    nlohmann::json processMessages(const nlohmann::json& messages);
    // ����ģʽ�Ĵ����֧
    void handleStreamResponse(const httplib::Request& req, httplib::Response& res, const nlohmann::json& requestJson);
    void handleNonStreamResponse(const httplib::Request& req, httplib::Response& res, const nlohmann::json& requestJson);
};

// ���ڷ���ʽ��ȡ�Ļص�����
size_t CompletionResponseCallback(void* contents, size_t size, size_t nmemb, std::string* s);

#endif // COMPLETION_HANDLER_H
