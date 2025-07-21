//image_uploader.cpp
#include "global.h"
#include "image_uploader.h"
#include "bearer_token_generator.h"  // 确保包含该头文件以便使用 GetBearer 和 UA
#include <iostream>
#include <string>
#include <chrono>
#include <ctime>
#include <vector>
#include <stdexcept>
#include <curl/curl.h>
#include <nlohmann/json.hpp>
#include <cstring>
#include <zlib.h>

using json = nlohmann::json;

// 回调函数：将上传文件的响应数据追加到 std::string 中
static size_t UploadWriteCallback(void* contents, size_t size, size_t nmemb, std::string* s) {
    size_t totalSize = size * nmemb;
    try {
        s->append(static_cast<char*>(contents), totalSize);
        return totalSize;
    }
    catch (...) {
        return 0;
    }
}

// 回调函数：检测响应头中是否含有 gzip 编码信息
static size_t UploadHeaderCallback(void* buffer, size_t size, size_t nitems, void* userdata) {
    size_t totalSize = size * nitems;
    std::string headerLine(static_cast<char*>(buffer), totalSize);
    bool* isGzip = static_cast<bool*>(userdata);
    if (headerLine.find("Content-Encoding: gzip") != std::string::npos) {
        *isGzip = true;
    }
    return totalSize;
}

// base64 解码函数，返回解码后的字节数组
static std::vector<unsigned char> base64DecodeToBytes(const std::string& encoded) {
    static const std::string base64_chars =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        "abcdefghijklmnopqrstuvwxyz"
        "0123456789+/";
    std::vector<unsigned char> decoded;
    int val = 0, valb = -8;
    for (unsigned char c : encoded) {
        if (c == '=') break;
        size_t pos = base64_chars.find(c);
        if (pos == std::string::npos) continue;
        val = (val << 6) + pos;
        valb += 6;
        if (valb >= 0) {
            decoded.push_back(static_cast<unsigned char>((val >> valb) & 0xFF));
            valb -= 8;
        }
    }
    return decoded;
}

// 将 dataUrl 上传图片，返回上传后的图片 URL
std::string uploadImageFromDataUrl(const std::string& dataUrl) {
    size_t pos = dataUrl.find("base64,");
    if (pos == std::string::npos) {
        std::cerr << "Invalid dataUrl: missing 'base64,'\n";
        return "";
    }
    std::string base64Data = dataUrl.substr(pos + 7);

    // 直接调用 base64DecodeToBytes 得到图片字节
    std::vector<unsigned char> imageBytes = base64DecodeToBytes(base64Data);

    // 判断图片扩展名
    std::string extension = "jpg";
    if (dataUrl.find("data:image/png") == 0) {
        extension = "png";
        std::cout << "base64为png" << std::endl;
    }
    else if (dataUrl.find("data:image/jpeg") == 0 || dataUrl.find("data:image/jpg") == 0) {
        extension = "jpg";
        std::cout << "base64为jpg" << std::endl;
    }
    else if (dataUrl.find("data:image/gif") == 0) {
        extension = "gif";
    }
    else if (dataUrl.find("data:image/webp") == 0) {
        extension = "webp";
    }

    // 生成文件名（使用当前时间戳）
    auto now = std::chrono::system_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count();
    std::string filename = std::to_string(ms) + "." + extension;

    // 获取当前 UTC 时间字符串
    std::time_t t = std::chrono::system_clock::to_time_t(now);
    std::tm* gmt = std::gmtime(&t);
    char formattedDate[32];
    std::strftime(formattedDate, sizeof(formattedDate), "%Y-%m-%dT%H:%M:%SZ", gmt);

    // 生成上传所需的 token
    std::vector<unsigned char> emptyBody;
    std::string uploadBearerToken = BearerTokenGenerator::GetBearer(emptyBody, "/storage/upload", formattedDate, "POST");

    // 判断 contentType
    std::string contentType = (extension == "jpg") ? "image/jpeg" : "image/" + extension;

    // 初始化 CURL
    CURL* curl = curl_easy_init();
    if (!curl) {
        std::cerr << "Failed to initialize CURL" << std::endl;
        return "";
    }
    curl_easy_setopt(curl, CURLOPT_URL, "https://api.chaton.ai/storage/upload");

    // 设置请求头
    struct curl_slist* headers = nullptr;
    std::string dateHeader = "Date: " + std::string(formattedDate);
    headers = curl_slist_append(headers, dateHeader.c_str());
    headers = curl_slist_append(headers, "Client-time-zone: -05:00");
    std::string authHeader = "Authorization: " + uploadBearerToken;
    headers = curl_slist_append(headers, authHeader.c_str());
    std::string uaHeader = "User-Agent: " + BearerTokenGenerator::UA;
    headers = curl_slist_append(headers, uaHeader.c_str());
    headers = curl_slist_append(headers, "Accept-language: en-US");
    headers = curl_slist_append(headers, "X-Cl-Options: hb");
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);

    // gzip 压缩检测
    bool isGzip = false;
    curl_easy_setopt(curl, CURLOPT_HEADERFUNCTION, UploadHeaderCallback);
    curl_easy_setopt(curl, CURLOPT_HEADERDATA, &isGzip);

    // multipart/form-data 构造
    curl_mime* mime = curl_mime_init(curl);
    curl_mimepart* part = curl_mime_addpart(mime);
    curl_mime_name(part, "file");
    curl_mime_filename(part, filename.c_str());
    curl_mime_data(part, reinterpret_cast<const char*>(imageBytes.data()), imageBytes.size());
    curl_mime_type(part, contentType.c_str());
    curl_easy_setopt(curl, CURLOPT_MIMEPOST, mime);

    // 执行请求
    std::string responseString;
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, UploadWriteCallback);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &responseString);
    CURLcode resCode = curl_easy_perform(curl);
    if (resCode != CURLE_OK) {
        std::cerr << "CURL error: " << curl_easy_strerror(resCode) << std::endl;
        curl_mime_free(mime);
        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);
        return "";
    }
    long responseCode = 0;
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &responseCode);
    if (responseCode != 200) {
        std::cerr << "Upload failed with code: " << responseCode << std::endl;
        std::cerr << "Response: " << responseString << std::endl;
        curl_mime_free(mime);
        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);
        return "";
    }

    curl_mime_free(mime);
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);

    // 若响应为 gzip 压缩，进行解压
    if (isGzip) {
        try {
            responseString = decompressGzip(responseString);
        }
        catch (const std::exception& e) {
            std::cerr << "Gzip decompression error: " << e.what() << std::endl;
            return "";
        }
    }

    // 从 JSON 中读取 getUrl 字段
    try {
        auto jsonResponse = json::parse(responseString);
        if (!jsonResponse.contains("getUrl")) {
            std::cerr << "Response missing getUrl field" << std::endl;
            return "";
        }
        return jsonResponse["getUrl"].get<std::string>();
    }
    catch (std::exception& e) {
        std::cerr << "JSON parse error: " << e.what() << std::endl;
        return "";
    }
}
