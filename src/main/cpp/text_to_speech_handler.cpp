#include "httplib.h"
#include "text_to_speech_handler.h"
#include "bearer_token_generator.h"
#include <curl/curl.h>
#include <chrono>
#include <ctime>
#include <sstream>
#include <iomanip>
#include <vector>
#include <cstring>
#include <zlib.h>

// Helper: Write callback for libcurl
static size_t WriteCallback(void* contents, size_t size, size_t nmemb, void* userp) {
    size_t totalSize = size * nmemb;
    std::vector<char>* buf = static_cast<std::vector<char>*>(userp);
    buf->insert(buf->end(), (char*)contents, (char*)contents + totalSize);
    return totalSize;
}

// Helper: Gzip decompress
static std::string decompressGzip(const std::vector<char>& compressed) {
    if (compressed.size() < 4) return "";
    z_stream zs{};
    zs.next_in = (Bytef*)compressed.data();
    zs.avail_in = compressed.size();

    if (inflateInit2(&zs, 16 + MAX_WBITS) != Z_OK) return "";

    std::string out;
    char buffer[8192];
    int ret;
    do {
        zs.next_out = reinterpret_cast<Bytef*>(buffer);
        zs.avail_out = sizeof(buffer);
        ret = inflate(&zs, Z_NO_FLUSH);
        if (out.size() < zs.total_out)
            out.append(buffer, zs.total_out - out.size());
    } while (ret == Z_OK);
    inflateEnd(&zs);
    return (ret == Z_STREAM_END) ? out : "";
}

void TextToSpeechHandler::handle(const httplib::Request& req, httplib::Response& res) {
    // 1. CORS Preprocessing
    res.set_header("Access-Control-Allow-Origin", "*");
    res.set_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    res.set_header("Access-Control-Allow-Headers", "Content-Type, Authorization");

    if (req.method == "OPTIONS") {
        res.status = 204;
        return;
    }

    if (req.method == "GET") {
        res.set_content("<html><head><title>欢迎使用API</title></head><body>"
                        "<h1>欢迎使用API</h1>"
                        "<p>此 API 用于与 ChatGPT / Claude 模型交互。您可以发送消息给模型并接收响应。</p>"
                        "</body></html>", "text/html; charset=utf-8");
        res.status = 200;
        return;
    }

    if (req.method != "POST") {
        res.status = 405;
        return;
    }

    // 2. Parse client JSON
    try {
        auto inJson = json::parse(req.body);
        std::string inputText = inJson.at("input").get<std::string>();
        std::string format = inJson.value("response_format", "mp3");
        std::string voice = inJson.value("voice", "nova");
        std::string model = inJson.value("model", "tts-1-hd");
        int speed = inJson.value("speed", 1);
        if (!model.starts_with("tts-1")) model = "tts-1-hd";

        json outJson = {
            {"input", inputText},
            {"response_format", format},
            {"voice", voice},
            {"model", model},
            {"speed", speed}
        };
        if (inJson.contains("stream")) {
            outJson["stream"] = inJson["stream"];
        }
        std::string bodyStr = outJson.dump();

        // 3. Prepare headers
        auto now = std::chrono::system_clock::now();
        auto itt = std::chrono::system_clock::to_time_t(now);
        std::ostringstream ss;
        ss << std::put_time(std::gmtime(&itt), "%Y-%m-%dT%H:%M:%SZ");
        std::string formattedDate = ss.str();

        std::vector<unsigned char> bodyContent(bodyStr.begin(), bodyStr.end());
        std::string path = "/audio/speech";
        std::string bearer = BearerTokenGenerator::GetBearer(bodyContent, path, formattedDate, "POST");

        struct curl_slist* headers = nullptr;
        headers = curl_slist_append(headers, "Content-Type: application/json; charset=UTF-8");
        headers = curl_slist_append(headers, "Accept: application/json");
        headers = curl_slist_append(headers, ("Date: " + formattedDate).c_str());
        headers = curl_slist_append(headers, "Client-time-zone: -04:00");
        headers = curl_slist_append(headers, ("Authorization: " + bearer).c_str());
        headers = curl_slist_append(headers, ("User-Agent: " + BearerTokenGenerator::UA).c_str());
        headers = curl_slist_append(headers, "Accept-Language: en-US");
        headers = curl_slist_append(headers, "X-Cl-Options: hb");
        headers = curl_slist_append(headers, "Accept-Encoding: gzip");
        headers = curl_slist_append(headers, "Connection: Keep-Alive");

        // 4. Send request to TTS API
        CURL* curl = curl_easy_init();
        if (!curl) {
            res.status = 500;
            return;
        }
        curl_easy_setopt(curl, CURLOPT_URL, "https://api.chaton.ai/audio/speech");
        curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
        curl_easy_setopt(curl, CURLOPT_POSTFIELDS, bodyStr.c_str());
        curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, bodyStr.size());

        std::vector<char> responseBuf;
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, WriteCallback);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, &responseBuf);

        // Accept compressed response
        curl_easy_setopt(curl, CURLOPT_ACCEPT_ENCODING, "gzip");

        CURLcode code = curl_easy_perform(curl);
        long http_code = 0;
        curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &http_code);

        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);

        if (code != CURLE_OK || http_code != 200) {
            res.status = 502;
            return;
        }

        // 5. Check for gzip and decompress if needed
        std::string audioData;
        // Try decompressing, fallback to raw if not gzip
        audioData = decompressGzip(responseBuf);
        if (audioData.empty()) {
            audioData.assign(responseBuf.begin(), responseBuf.end());
        }

        // 6. Set response headers and stream audio
        res.set_header("Content-Type", "audio/mpeg");
        res.set_header("Accept-Ranges", "bytes");
        res.set_header("Content-Disposition", "attachment; filename=\"output.mp3\"");
        res.status = 200;
        res.body = std::move(audioData);

    } catch (const std::exception& ex) {
        res.status = 500;
    }
}
