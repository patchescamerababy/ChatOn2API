//image_uploader.cpp
#include "global.h"
#include "image_uploader.h"
#include "bearer_token_generator.h"  // ȷ��������ͷ�ļ��Ա�ʹ�� GetBearer �� UA
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

// �ص����������ϴ��ļ�����Ӧ����׷�ӵ� std::string ��
static size_t UploadWriteCallback(void* contents, size_t size, size_t nmemb, std::string* s) {
    size_t totalSize = size * nmemb;
    try {
        s->append(static_cast<char*>(contents), totalSize);
        return totalSize;
    } catch(...) {
        return 0;
    }
}

// �ص������������Ӧͷ���Ƿ��� gzip ������Ϣ
static size_t UploadHeaderCallback(void* buffer, size_t size, size_t nitems, void* userdata) {
    size_t totalSize = size * nitems;
    std::string headerLine(static_cast<char*>(buffer), totalSize);
    bool* isGzip = static_cast<bool*>(userdata);
    if (headerLine.find("Content-Encoding: gzip") != std::string::npos) {
        *isGzip = true;
    }
    return totalSize;
}

// base64 ���뺯�������ؽ������ֽ�����
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

// �� dataUrl �ϴ�ͼƬ�������ϴ����ͼƬ URL
std::string uploadImageFromDataUrl(const std::string& dataUrl) {
    size_t pos = dataUrl.find("base64,");
    if (pos == std::string::npos) {
        std::cerr << "Invalid dataUrl: missing 'base64,'\n";
        return "";
    }
    std::string base64Data = dataUrl.substr(pos + 7);

    // ֱ�ӵ��� base64DecodeToBytes �õ�ͼƬ�ֽ�
    std::vector<unsigned char> imageBytes = base64DecodeToBytes(base64Data);

    // �ж�ͼƬ��չ��
    std::string extension = "jpg";
    if (dataUrl.find("data:image/png") == 0) {
        extension = "png";
        std::cout << "base64Ϊpng" << std::endl;
    } else if (dataUrl.find("data:image/jpeg") == 0 || dataUrl.find("data:image/jpg") == 0) {
        extension = "jpg";
        std::cout << "base64Ϊjpg" << std::endl;
    } else if (dataUrl.find("data:image/gif") == 0) {
        extension = "gif";
    } else if (dataUrl.find("data:image/webp") == 0) {
        extension = "webp";
    }

    // �����ļ�����ʹ�õ�ǰʱ�����
    auto now = std::chrono::system_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count();
    std::string filename = std::to_string(ms) + "." + extension;

    // ��ȡ��ǰ UTC ʱ���ַ���
    std::time_t t = std::chrono::system_clock::to_time_t(now);
    std::tm* gmt = std::gmtime(&t);
    char formattedDate[32];
    std::strftime(formattedDate, sizeof(formattedDate), "%Y-%m-%dT%H:%M:%SZ", gmt);

    // �����ϴ������ token
    std::vector<unsigned char> emptyBody;
    std::string uploadBearerToken = BearerTokenGenerator::GetBearer(emptyBody, "/storage/upload", formattedDate, "POST");

    // �ж� contentType
    std::string contentType = (extension == "jpg") ? "image/jpeg" : "image/" + extension;

    // ��ʼ�� CURL
    CURL* curl = curl_easy_init();
    if (!curl) {
        std::cerr << "Failed to initialize CURL" << std::endl;
        return "";
    }
    curl_easy_setopt(curl, CURLOPT_URL, "https://api.chaton.ai/storage/upload");

    // ��������ͷ
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

    // gzip ѹ�����
    bool isGzip = false;
    curl_easy_setopt(curl, CURLOPT_HEADERFUNCTION, UploadHeaderCallback);
    curl_easy_setopt(curl, CURLOPT_HEADERDATA, &isGzip);

    // multipart/form-data ����
    curl_mime* mime = curl_mime_init(curl);
    curl_mimepart* part = curl_mime_addpart(mime);
    curl_mime_name(part, "file");
    curl_mime_filename(part, filename.c_str());
    curl_mime_data(part, reinterpret_cast<const char*>(imageBytes.data()), imageBytes.size());
    curl_mime_type(part, contentType.c_str());
    curl_easy_setopt(curl, CURLOPT_MIMEPOST, mime);

    // ִ������
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

    // ����ӦΪ gzip ѹ�������н�ѹ
    if (isGzip) {
        try {
            responseString = decompressGzip(responseString);
        } catch (const std::exception& e) {
            std::cerr << "Gzip decompression error: " << e.what() << std::endl;
            return "";
        }
    }

    // �� JSON �ж�ȡ getUrl �ֶ�
    try {
        auto jsonResponse = json::parse(responseString);
        if (!jsonResponse.contains("getUrl")) {
            std::cerr << "Response missing getUrl field" << std::endl;
            return "";
        }
        return jsonResponse["getUrl"].get<std::string>();
    } catch (std::exception& e) {
        std::cerr << "JSON parse error: " << e.what() << std::endl;
        return "";
    }
}
