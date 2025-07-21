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
const std::string BearerTokenGenerator::UA = "ChatOn_iOS/1.62.873.0";
// 密钥初始化
const std::vector<unsigned char> BearerTokenGenerator::keyA = { 
    0xFF, 0x56, 0x83, 0xB3,
    0x5D, 0x76, 0xBC, 0xC5,
    0xF2, 0x65, 0xB4, 0x89,
    0xED, 0xF6, 0x94, 0xAE
};

const std::vector<unsigned char> BearerTokenGenerator::keyB = { 
    0x66, 0xF8, 0x00, 0x87, 0x1B, 0x58, 0xB1, 0x53,
    0xA1, 0xE7, 0x2E, 0x0A, 0x40, 0xFE,
    0xB9, 0xDE, 0x55, 0x93, 0x85,
    0x1E, 0x28, 0x98, 0xD9, 0xFD,
    0xAB, 0xF2, 0xEB, 0x2D,
    0xFD, 0x08, 0xFB, 0x6D
};


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
