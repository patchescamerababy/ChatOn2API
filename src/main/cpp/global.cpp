//global.cpp
#include "global.h"
#include "httplib.h"
#include <iostream>
#include <nlohmann/json.hpp>
#include <cctype>
#include <stdexcept>
#include <cstring>
#include <zlib.h>

// 错误响应实现
void sendError(httplib::Response& res, const std::string& message, int HTTP_code) {
    res.status = HTTP_code;
    nlohmann::json errorJson;
    errorJson["error"] = {
        {"message", message},
        {"type", "api_error"},
        {"code", HTTP_code}
    };
    res.set_header("Content-Type", "application/json; charset=utf-8");
    res.set_content(errorJson.dump(), "application/json");
    //std::cerr << "Error: " << message << " (" << HTTP_code << ")" << std::endl;
}
std::string decompressGzip(const std::string& compressedStr) {
    if (compressedStr.empty()) return "";
    z_stream zs;
    memset(&zs, 0, sizeof(zs));
    if (inflateInit2(&zs, 16 + MAX_WBITS) != Z_OK) {
        throw std::runtime_error("inflateInit2 failed while decompressing.");
    }
    zs.next_in = reinterpret_cast<Bytef*>(const_cast<char*>(compressedStr.data()));
    zs.avail_in = compressedStr.size();

    int ret;
    char outbuffer[32768];
    std::string decompressed;
    do {
        zs.next_out = reinterpret_cast<Bytef*>(outbuffer);
        zs.avail_out = sizeof(outbuffer);
        ret = inflate(&zs, 0);
        if (ret == Z_STREAM_ERROR || ret == Z_DATA_ERROR || ret == Z_MEM_ERROR) {
            inflateEnd(&zs);
            throw std::runtime_error("Exception during zlib decompression");
        }
        size_t have = sizeof(outbuffer) - zs.avail_out;
        decompressed.append(outbuffer, have);
    } while (ret != Z_STREAM_END);
    inflateEnd(&zs);
    return decompressed;
}

// -------------------------
// Base64 编码／解码实现代码（从 image_generations_handler.cpp 移出）
// -------------------------
// Base64 字符集定义
static const std::string base64_chars =
"ABCDEFGHIJKLMNOPQRSTUVWXYZ"
"abcdefghijklmnopqrstuvwxyz"
"0123456789+/";

// 判断字符是否为 Base64 合法字符
static inline bool is_base64(unsigned char c) {
    return (std::isalnum(c) || (c == '+') || (c == '/'));
}

// Base64 编码实现，接口签名不变
std::string base64_encode_impl(const unsigned char* bytes_to_encode, unsigned int in_len) {
    std::string ret;
    ret.reserve(((in_len + 2) / 3) * 4);

    int i = 0;
    unsigned char char_array_3[3], char_array_4[4];

    while (in_len--) {
        char_array_3[i++] = *(bytes_to_encode++);
        if (i == 3) {
            char_array_4[0] = (char_array_3[0] & 0xfc) >> 2;
            char_array_4[1] = ((char_array_3[0] & 0x03) << 4) + ((char_array_3[1] & 0xf0) >> 4);
            char_array_4[2] = ((char_array_3[1] & 0x0f) << 2) + ((char_array_3[2] & 0xc0) >> 6);
            char_array_4[3] = char_array_3[2] & 0x3f;
            for (i = 0; i < 4; i++)
                ret += base64_chars[char_array_4[i]];
            i = 0;
        }
    }

    if (i) {
        for (int j = i; j < 3; j++)
            char_array_3[j] = '\0';
        char_array_4[0] = (char_array_3[0] & 0xfc) >> 2;
        char_array_4[1] = ((char_array_3[0] & 0x03) << 4) + ((char_array_3[1] & 0xf0) >> 4);
        char_array_4[2] = ((char_array_3[1] & 0x0f) << 2) + ((char_array_3[2] & 0xc0) >> 6);
        char_array_4[3] = char_array_3[2] & 0x3f;
        for (int j = 0; j < i + 1; j++)
            ret += base64_chars[char_array_4[j]];
        while ((i++ < 3))
            ret += '=';
    }
    return ret;
}

// 直接对 std::string 进行 Base64 编码
std::string base64_encode(const std::string& in) {
    return base64_encode_impl(reinterpret_cast<const unsigned char*>(in.c_str()), in.size());
}

// Base64 解码实现
std::string base64_decode(const std::string& encoded_string) {
    int in_len = encoded_string.size();
    int i = 0, j = 0, in_ = 0;
    unsigned char char_array_4[4], char_array_3[3];
    std::string ret;

    while (in_len-- && (encoded_string[in_] != '=') && is_base64(encoded_string[in_])) {
        char_array_4[i++] = encoded_string[in_];
        in_++;
        if (i == 4) {
            for (i = 0; i < 4; i++)
                char_array_4[i] = base64_chars.find(char_array_4[i]);
            char_array_3[0] = (char_array_4[0] << 2) + ((char_array_4[1] & 0x30) >> 4);
            char_array_3[1] = ((char_array_4[1] & 0xf) << 4) + ((char_array_4[2] & 0x3c) >> 2);
            char_array_3[2] = ((char_array_4[2] & 0x3) << 6) + char_array_4[3];
            for (i = 0; i < 3; i++)
                ret += char_array_3[i];
            i = 0;
        }
    }
    if (i) {
        for (j = i; j < 4; j++)
            char_array_4[j] = 0;
        for (j = 0; j < 4; j++)
            char_array_4[j] = base64_chars.find(char_array_4[j]);
        char_array_3[0] = (char_array_4[0] << 2) + ((char_array_4[1] & 0x30) >> 4);
        char_array_3[1] = ((char_array_4[1] & 0xf) << 4) + ((char_array_4[2] & 0x3c) >> 2);
        char_array_3[2] = ((char_array_4[2] & 0x3) << 6) + char_array_4[3];
        for (j = 0; j < i - 1; j++)
            ret += char_array_3[j];
    }
    return ret;
}
