//bearer_token_generator.cpp
#include "global.h"
#include "bearer_token_generator.h"
#include <openssl/hmac.h>
#include <openssl/evp.h>
#include <sstream>
#include <iomanip>
#include <algorithm>
#include <iostream>

// 初始化静态成员变量
const std::string BearerTokenGenerator::UA = "ChatOn_Android/1.66.536";
// 密钥初始化
const std::vector<unsigned char> BearerTokenGenerator::keyA = { 'W', 'L', 'G', 'D', 'K', 'D', 'd', '3', '3', 'd', 'a', 'B', 'P', 'l', 'w', 'r' };
const std::vector<unsigned char> BearerTokenGenerator::keyB = { 's', 'E', 'v', 'P', '7', '5', 'K', 'G', 'U', 'U', 'l', 'C', 'n', 'R', '6', 'i', '5', 'h', 'b', 'a', 's', 'h', 'r', 'Z', 'r', '5', 'l', 'o', 'w', 'z', 'T', 'B' };



std::string BearerTokenGenerator::GetBearer(const std::vector<unsigned char>& bodyContent,
    const std::string& path,
    const std::string& formattedDate,
    const std::string& method) {
    //std::cout << "Bearer token generation - Body content size: " << bodyContent.size() << " bytes" << std::endl;
    std::string upperMethod = method;
    std::transform(upperMethod.begin(), upperMethod.end(), upperMethod.begin(), ::toupper);
    std::string combinedString = upperMethod + ":" + path + ":" + formattedDate + "\n";
    std::vector<unsigned char> headerBytes(combinedString.begin(), combinedString.end());

    // 连接 header 和 body
    std::vector<unsigned char> connect_data = connect(headerBytes, bodyContent);

    // 生成签名
    std::vector<unsigned char> sig = signature(keyB, connect_data);
    if (sig.empty()) {
        return "";
    }

    // Base64编码签名和密钥A
// 使用 global.cpp 中的 base64_encode 进行编码：将 vector 转换为 string 后调用
    std::string encodedSignature = base64_encode(std::string(sig.begin(), sig.end()));
    std::string encodedKeyA = base64_encode(std::string(keyA.begin(), keyA.end()));

    // 构造Bearer Token
    return "Bearer " + encodedKeyA + "." + encodedSignature;
}

std::vector<unsigned char> BearerTokenGenerator::signature(const std::vector<unsigned char>& key,
    const std::vector<unsigned char>& data) {
    try {
        unsigned char digest[EVP_MAX_MD_SIZE];
        unsigned int digest_len = 0;
        if (HMAC(EVP_sha256(), key.data(), key.size(), data.data(), data.size(), digest, &digest_len) == nullptr) {
            return std::vector<unsigned char>();
        }
        return std::vector<unsigned char>(digest, digest + digest_len);
    }
    catch (...) {
        return std::vector<unsigned char>();
    }
}

std::vector<unsigned char> BearerTokenGenerator::connect(const std::vector<unsigned char>& a,
    const std::vector<unsigned char>& b) {
    std::vector<unsigned char> result;
    result.reserve(a.size() + b.size());
    result.insert(result.end(), a.begin(), a.end());
    result.insert(result.end(), b.begin(), b.end());
    return result;
}
