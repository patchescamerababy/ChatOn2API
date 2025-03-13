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
        sendError(res, "缺少必需的字段: prompt", 400);
        return;
    }
    std::string userPrompt = requestJson["prompt"].get<std::string>();
    //std::string responseFormat = "";
    bool needBase64 = false;
    if (requestJson.contains("response_format")) {
        //responseFormat = requestJson["response_format"].get<std::string>();
        needBase64 = true;
    }
    // 构建 Text-to-Image 请求体（不进行润色、不重试）
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

    // POST 请求到 completions API（使用全局端口）
    CURL* curl = curl_easy_init();
    if (!curl) {
        sendError(res, "Failed to initialize CURL", 500);
        return;
    }
    // 添加代理设置及禁用证书验证
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

    // 判断 completionsResponse 是否为 gzip 格式（gzip 文件头的前两个字节为 0x1F 0x8B），如果是则解压
    std::string decompressedCompletionsResponse;
    if (completionsResponse.size() >= 2 &&
        static_cast<unsigned char>(completionsResponse[0]) == 0x1F &&
        static_cast<unsigned char>(completionsResponse[1]) == 0x8B) {
        try {
            decompressedCompletionsResponse = decompressGzip(completionsResponse);
        }
        catch (std::exception& e) {
            sendError(res, std::string("Gzip 解压 completions response 失败: ") + e.what(), 500);
            return;
        }
    }
    else {
        decompressedCompletionsResponse = completionsResponse;
    }

    // 使用正则表达式提取所有 SSE 事件块
    // SSE 块通常以 "data: " 开头，并以空行分隔
    std::string accumulatedContent;
    std::regex eventRegex("data: (.*?)(?:\r?\n){2}");
    std::smatch eventMatch;
    std::string s = decompressedCompletionsResponse;
    while (std::regex_search(s, eventMatch, eventRegex)) {
        std::string jsonStr = eventMatch[1].str();
        // 判断是否为结束标志
        if (jsonStr == "[DONE]") {
            break;
        }
        try {
            auto chunkJson = json::parse(jsonStr);
            // 检查 choices 是否存在且为数组，并且非空
            if (chunkJson.contains("choices") &&
                chunkJson["choices"].is_array() &&
                !chunkJson["choices"].empty()) {

                auto firstChoice = chunkJson["choices"][0];
                // 如果存在 delta 对象，并且其中有 content 字段，则累积内容
                if (firstChoice.contains("delta") &&
                    firstChoice["delta"].contains("content")) {
                    accumulatedContent += firstChoice["delta"]["content"].get<std::string>();
                }
            }
        }
        catch (const std::exception& e) {
            std::cerr << "JSON 解析错误: " << e.what() << std::endl;
        }
        s = eventMatch.suffix().str();
    }

    std::string imageMarkdown = accumulatedContent;
    if (imageMarkdown.empty()) {
        sendError(res, "无法从 completions JSON 中提取图像 Markdown。", 500);
        return;
    }

    // 从 Markdown 中提取图像路径（例如：![Image](https://spc.unk/xxxxx)）
    std::regex pathRegex("https://spc\\.unk/([^\\s\\)]+)");
    std::smatch match;
    std::string extractedPath;
    if (std::regex_search(imageMarkdown, match, pathRegex)) {
        extractedPath = match[1].str();
    }
    else {
        // 提取失败时直接返回原始 Markdown 用于调试
        json responseJson;
        responseJson["raw_markdown"] = imageMarkdown;
        res.set_header("Content-Type", "application/json; charset=utf-8");
        res.status = 200;
        res.set_content(responseJson.dump(), "application/json; charset=utf-8");
        return;
    }

    // 拼接 storage URL
    std::string storageUrl = "https://api.chaton.ai/storage/" + extractedPath;

    // GET 请求 storage URL 获取最终下载链接（期望字段为 getUrl）
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

    // 构建最终响应 JSON，根据 response_format 返回 Base64 编码或直接返回 getUrl
    json responseJson;
    responseJson["created"] = static_cast<long>(std::time(nullptr));
    json dataObject = json::object();
    if (needBase64) {
        std::cout << "正在下载, URL: " << finalDownloadUrl.c_str() << std::endl;
        // 下载图像并编码为 Base64
        curl = curl_easy_init();
        if (!curl) {
            sendError(res, "Failed to initialize CURL for image download", 500);
            return;
        }
        // 添加代理及禁用证书验证（如果需要代理可以启用以下设置）
        //curl_easy_setopt(curl, CURLOPT_PROXY, "127.0.0.1");
        //curl_easy_setopt(curl, CURLOPT_PROXYPORT, 5257L);
        curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
        curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);
        // 添加自定义请求头，同时请求 gzip 压缩
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
        // 判断 imageData 是否为 gzip 压缩格式（gzip 文件头的前两个字节为 0x1F 0x8B）
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
                sendError(res, std::string("Gzip 解压图片失败: ") + e.what(), 500);
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
