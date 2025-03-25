#ifndef GLOBAL_H
#define GLOBAL_H

#include <string>
#include <httplib.h>

// 统一错误响应接口
void sendError(httplib::Response& res, const std::string& message, int HTTP_code);

std::string decompressGzip(const std::string &compressedStr);
// Base64 编码／解码接口（从 image_generations_handler 中分离出来）
std::string base64_encode(const std::string &in);
std::string base64_decode(const std::string &encoded_string);

#endif // GLOBAL_H
