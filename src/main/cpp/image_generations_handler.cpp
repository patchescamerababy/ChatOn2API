// image_generations_handler.cpp 
#include "image_generations_handler.h"
#include "header_manager.h"
#include "global.h"
#include <iostream>
#include <curl/curl.h>
#include <nlohmann/json.hpp>
#include <regex>
#include <sstream>
#include <ctime>
#include <cstdlib>
#include <cctype>
#include <stdexcept>
#include <cstring>
#include <zlib.h>

using json = nlohmann::json;
extern int global_port;

ImageGenerationHandler::ImageGenerationHandler() {
    std::setlocale(LC_ALL, "zh_CN.UTF-8");
}

size_t CurlWriteCallback(void* contents, size_t size, size_t nmemb, std::string* s) {
    size_t newLength = size * nmemb;
    try {
        s->append(static_cast<char*>(contents), newLength);
        return newLength;
    }
    catch (std::bad_alloc& e) {
        return 0;
    }
}

void ImageGenerationHandler::handle(const httplib::Request& req, httplib::Response& res) {
    res.set_header("Access-Control-Allow-Origin", "*");
    res.set_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    res.set_header("Access-Control-Allow-Headers", "Content-Type, Authorization");
    res.set_header("Connection", "keep-alive");

    json requestJson;
    try {
        requestJson = json::parse(req.body);
    }
    catch (const std::exception& e) {
        sendError(res, std::string("Invalid JSON: ") + e.what(), 400);
        return;
    }
    if (!requestJson.contains("prompt")) {
        sendError(res, "ȱ�ٱ�����ֶ�: prompt", 400);
        return;
    }
    std::string userPrompt = requestJson["prompt"].get<std::string>();
    //std::string responseFormat = "";
    bool needBase64 = false;
    if (requestJson.contains("response_format")) {
        //responseFormat = requestJson["response_format"].get<std::string>();
        needBase64 = true;
    }
    // ���� Text-to-Image �����壨��������ɫ�������ԣ�
    json textToImageJson;
    textToImageJson["function_image_gen"] = true;
    textToImageJson["function_web_search"] = true;
    textToImageJson["max_tokens"] = 8000;
    json systemMessage;
    systemMessage["role"] = "system";
    systemMessage["content"] =
        "Your role is that of a smart and creative assistant. Do not mention that you are a chatbot or AI assistant. "
        "Consider the terms when communicating: 1. The length of your response: Auto. 2. The tone style of your speech: Default. "
        "This dialog box has an option to generate images. The function should be called only when the user explicitly requests it "
        "- for example, using any related words associated with image generation requests. In other cases - the call of the image "
        "generation function should not be called.";
    json userMessage;
    userMessage["role"] = "user";
    userMessage["content"] = "Draw: " + userPrompt;
    textToImageJson["messages"] = json::array({ systemMessage, userMessage });
    textToImageJson["model"] = "gpt-4o";
    textToImageJson["image_aspect_ratio"] = "1:1";
    textToImageJson["image_style"] = "anime";
    textToImageJson["source"] = "chat/pro_image";

    std::string requestBody = textToImageJson.dump();

    // POST ���� completions API��ʹ��ȫ�ֶ˿ڣ�
    CURL* curl = curl_easy_init();
    if (!curl) {
        sendError(res, "Failed to initialize CURL", 500);
        return;
    }
    // ��Ӵ������ü�����֤����֤
    //curl_easy_setopt(curl, CURLOPT_PROXY, "127.0.0.1");
    //curl_easy_setopt(curl, CURLOPT_PROXYPORT, 5257L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);

    std::string completionsUrl = "https://api.chaton.ai/chats/stream";
    curl_easy_setopt(curl, CURLOPT_URL, completionsUrl.c_str());
    curl_easy_setopt(curl, CURLOPT_POST, 1L);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, requestBody.c_str());
    curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, static_cast<long>(requestBody.length()));
    //print
    std::cout << "requestBody: " << requestBody << std::endl;
    struct curl_slist* curlHeaders = getHeaderManager().getApiHeaders(HeaderManager::ApiType::IMAGE_GENERATIONS, textToImageJson);
    curlHeaders = curl_slist_append(curlHeaders, "Content-Type: application/json");
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, curlHeaders);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 60L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 60L);

    std::string completionsResponse;
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, CurlWriteCallback);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &completionsResponse);

    CURLcode result = curl_easy_perform(curl);
    if (result != CURLE_OK) {
        curl_slist_free_all(curlHeaders);
        curl_easy_cleanup(curl);
        sendError(res, "CURL perform failed", 500);
        return;
    }
    long httpCode = 0;
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &httpCode);
    curl_slist_free_all(curlHeaders);
    curl_easy_cleanup(curl);
    if (httpCode != 200) {
        sendError(res, "Completions API returned non-200", httpCode);
        return;
    }

    // �ж� completionsResponse �Ƿ�Ϊ gzip ��ʽ��gzip �ļ�ͷ��ǰ�����ֽ�Ϊ 0x1F 0x8B������������ѹ
    std::string decompressedCompletionsResponse;
    if (completionsResponse.size() >= 2 &&
        static_cast<unsigned char>(completionsResponse[0]) == 0x1F &&
        static_cast<unsigned char>(completionsResponse[1]) == 0x8B) {
        try {
            decompressedCompletionsResponse = decompressGzip(completionsResponse);
        }
        catch (std::exception& e) {
            sendError(res, std::string("Gzip ��ѹ completions response ʧ��: ") + e.what(), 500);
            return;
        }
    }
    else {
        decompressedCompletionsResponse = completionsResponse;
    }

    // ʹ��������ʽ��ȡ���� SSE �¼���
    // SSE ��ͨ���� "data: " ��ͷ�����Կ��зָ�
    std::string accumulatedContent;
    std::regex eventRegex("data: (.*?)(?:\r?\n){2}");
    std::smatch eventMatch;
    std::string s = decompressedCompletionsResponse;
    while (std::regex_search(s, eventMatch, eventRegex)) {
        std::string jsonStr = eventMatch[1].str();
        // �ж��Ƿ�Ϊ������־
        if (jsonStr == "[DONE]") {
            break;
        }
        try {
            auto chunkJson = json::parse(jsonStr);
            // ��� choices �Ƿ������Ϊ���飬���ҷǿ�
            if (chunkJson.contains("choices") &&
                chunkJson["choices"].is_array() &&
                !chunkJson["choices"].empty()) {

                auto firstChoice = chunkJson["choices"][0];
                // ������� delta ���󣬲��������� content �ֶΣ����ۻ�����
                if (firstChoice.contains("delta") &&
                    firstChoice["delta"].contains("content")) {
                    accumulatedContent += firstChoice["delta"]["content"].get<std::string>();
                }
            }
        }
        catch (const std::exception& e) {
            std::cerr << "JSON ��������: " << e.what() << std::endl;
        }
        s = eventMatch.suffix().str();
    }

    std::string imageMarkdown = accumulatedContent;
    if (imageMarkdown.empty()) {
        sendError(res, "�޷��� completions JSON ����ȡͼ�� Markdown��", 500);
        return;
    }

    // �� Markdown ����ȡͼ��·�������磺![Image](https://spc.unk/xxxxx)��
    std::regex pathRegex("https://spc\\.unk/([^\\s\\)]+)");
    std::smatch match;
    std::string extractedPath;
    if (std::regex_search(imageMarkdown, match, pathRegex)) {
        extractedPath = match[1].str();
    }
    else {
        // ��ȡʧ��ʱֱ�ӷ���ԭʼ Markdown ���ڵ���
        json responseJson;
        responseJson["raw_markdown"] = imageMarkdown;
        res.set_header("Content-Type", "application/json; charset=utf-8");
        res.status = 200;
        res.set_content(responseJson.dump(), "application/json; charset=utf-8");
        return;
    }

    // ƴ�� storage URL
    std::string storageUrl = "https://api.chaton.ai/storage/" + extractedPath;

    // GET ���� storage URL ��ȡ�����������ӣ������ֶ�Ϊ getUrl��
    curl = curl_easy_init();
    if (!curl) {
        sendError(res, "Failed to initialize CURL for storage", 500);
        return;
    }
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);
    curl_easy_setopt(curl, CURLOPT_URL, storageUrl.c_str());
    curl_easy_setopt(curl, CURLOPT_HTTPGET, 1L);
    std::string storageResponse;
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, CurlWriteCallback);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &storageResponse);
    result = curl_easy_perform(curl);
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &httpCode);
    curl_easy_cleanup(curl);
    if (result != CURLE_OK || httpCode != 200) {
        sendError(res, "Storage GET failed", 500);
        return;
    }
    std::string finalDownloadUrl;
    try {
        json storageJson = json::parse(storageResponse);
        if (storageJson.contains("getUrl"))
            finalDownloadUrl = storageJson["getUrl"].get<std::string>();
        else {
            sendError(res, "Storage response JSON missing 'getUrl' field", 500);
            return;
        }
    }
    catch (std::exception& e) {
        sendError(res, std::string("JSON parse error for storage response: ") + e.what(), 500);
        return;
    }

    // ����������Ӧ JSON������ response_format ���� Base64 �����ֱ�ӷ��� getUrl
    json responseJson;
    responseJson["created"] = static_cast<long>(std::time(nullptr));
    json dataObject = json::object();
    if (needBase64) {
        std::cout << "��������, URL: " << finalDownloadUrl.c_str() << std::endl;
        // ����ͼ�񲢱���Ϊ Base64
        curl = curl_easy_init();
        if (!curl) {
            sendError(res, "Failed to initialize CURL for image download", 500);
            return;
        }
        // ��Ӵ�������֤����֤�������Ҫ������������������ã�
        //curl_easy_setopt(curl, CURLOPT_PROXY, "127.0.0.1");
        //curl_easy_setopt(curl, CURLOPT_PROXYPORT, 5257L);
        curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
        curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);
        // ����Զ�������ͷ��ͬʱ���� gzip ѹ��
        struct curl_slist* downloadHeaders = nullptr;
        downloadHeaders = curl_slist_append(downloadHeaders, "Host: chaton-img.s3.us-east-2.amazonaws.com");
        downloadHeaders = curl_slist_append(downloadHeaders, "Connection: Keep-Alive");
        downloadHeaders = curl_slist_append(downloadHeaders, "Accept-Encoding: gzip");
        downloadHeaders = curl_slist_append(downloadHeaders, "User-Agent: Mozilla/5.0");
        curl_easy_setopt(curl, CURLOPT_HTTPHEADER, downloadHeaders);

        curl_easy_setopt(curl, CURLOPT_URL, finalDownloadUrl.c_str());
        curl_easy_setopt(curl, CURLOPT_HTTPGET, 1L);
        std::string imageData;
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, CurlWriteCallback);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, &imageData);
        result = curl_easy_perform(curl);
        curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &httpCode);
        curl_easy_cleanup(curl);
        curl_slist_free_all(downloadHeaders);
        if (result != CURLE_OK || httpCode != 200) {
            sendError(res, "Image download failed", 500);
            return;
        }
        // �ж� imageData �Ƿ�Ϊ gzip ѹ����ʽ��gzip �ļ�ͷ��ǰ�����ֽ�Ϊ 0x1F 0x8B��
        bool needDecompress = false;
        if (imageData.size() >= 2) {
            if (static_cast<unsigned char>(imageData[0]) == 0x1F &&
                static_cast<unsigned char>(imageData[1]) == 0x8B) {
                needDecompress = true;
            }
        }

        std::string finalImageData;
        if (needDecompress) {
            try {
                finalImageData = decompressGzip(imageData);
            }
            catch (const std::exception& e) {
                sendError(res, std::string("Gzip ��ѹͼƬʧ��: ") + e.what(), 500);
                return;
            }
        }
        else {
            finalImageData = imageData;
        }

        std::string encodedImage = base64_encode(finalImageData);
        dataObject["b64_json"] = encodedImage;
    }
    else {
        dataObject["url"] = finalDownloadUrl;
    }
    responseJson["data"] = json::array({ dataObject });

    res.set_header("Content-Type", "application/json; charset=utf-8");
    res.status = 200;
    res.set_content(responseJson.dump(), "application/json; charset=utf-8");
}
