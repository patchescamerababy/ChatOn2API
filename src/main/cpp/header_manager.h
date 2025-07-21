#ifndef HEADER_MANAGER_H
#define HEADER_MANAGER_H

#include <string>
#include <curl/curl.h>
#include <nlohmann/json.hpp>

class HeaderManager {
public:
    enum class ApiType {
        CHAT_COMPLETIONS,
        IMAGE_GENERATIONS,
        EMBEDDINGS
    };

    struct curl_slist* getApiHeaders(ApiType type, const nlohmann::json& requestJson);

    static HeaderManager& getInstance() {
        static HeaderManager instance;
        return instance;
    }

private:
    HeaderManager() = default;
    HeaderManager(const HeaderManager&) = delete;
    HeaderManager& operator=(const HeaderManager&) = delete;
};

inline HeaderManager& getHeaderManager() {
    return HeaderManager::getInstance();
}

#endif // HEADER_MANAGER_H
