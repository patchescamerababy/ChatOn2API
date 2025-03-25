//header_manager.cpp
#include "header_manager.h"
#include "bearer_token_generator.h"
#include <sstream>
#include <iomanip>
#include <chrono>
#include <iostream>
struct curl_slist* HeaderManager::getApiHeaders(ApiType type, const nlohmann::json& requestJson) {
    struct curl_slist* headers = nullptr;
    
    // 添加通用头部
    headers = curl_slist_append(headers, "Content-Type: application/json; charset=UTF-8");
    headers = curl_slist_append(headers, "Accept: application/json");

    auto now = std::chrono::system_clock::now();
    auto itt = std::chrono::system_clock::to_time_t(now);
    std::ostringstream ss;
    ss << std::put_time(std::gmtime(&itt), "%Y-%m-%dT%H:%M:%SZ");
    std::string formattedDate = ss.str();

    headers = curl_slist_append(headers, ("Date: " + formattedDate).c_str());
    headers = curl_slist_append(headers, "Client-time-zone: -04:00");

    std::string jsonBody = requestJson.dump(-1, ' ', false, nlohmann::json::error_handler_t::replace);
    std::vector<unsigned char> bodyContent(jsonBody.begin(), jsonBody.end());

    std::string path;
    switch(type) {
        case ApiType::CHAT_COMPLETIONS:
            path = "/chats/stream";
            break;
        case ApiType::IMAGE_GENERATIONS:
            path = "/chats/stream";
            break;
        default:
            path = "/chats/stream";
    }

    std::string tmpToken = BearerTokenGenerator::GetBearer(bodyContent, path, formattedDate, "POST");

    headers = curl_slist_append(headers, ("Authorization: " + tmpToken).c_str());
    headers = curl_slist_append(headers, ("User-Agent: " + BearerTokenGenerator::UA).c_str());
    headers = curl_slist_append(headers, "Accept-Language: en-US");
    headers = curl_slist_append(headers, "X-Cl-Options: hb");
    headers = curl_slist_append(headers, "Accept-Encoding: gzip");
    headers = curl_slist_append(headers, "Connection: Keep-Alive");

    return headers;
}