#ifndef GLOBAL_H
#define GLOBAL_H

#include <string>
#include <httplib.h>

// ͳһ������Ӧ�ӿ�
void sendError(httplib::Response& res, const std::string& message, int HTTP_code);

std::string decompressGzip(const std::string &compressedStr);
// Base64 ���룯����ӿڣ��� image_generations_handler �з��������
std::string base64_encode(const std::string &in);
std::string base64_decode(const std::string &encoded_string);

#endif // GLOBAL_H
