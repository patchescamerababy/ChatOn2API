#include "global.h"
#include "bearer_token_generator.h"
#include <openssl/hmac.h>
#include <openssl/evp.h>
#include <sstream>
#include <iomanip>
#include <algorithm>
#include <iostream>
#include <cstdlib>

// 静态成员变量定义
std::vector<unsigned char> BearerTokenGenerator::keyA;
std::vector<unsigned char> BearerTokenGenerator::keyB;
std::string BearerTokenGenerator::UA;

bool BearerTokenGenerator::Initialize() {
    const char* envKeyA = std::getenv("KEY_A");
    const char* envKeyB = std::getenv("KEY_B");
    const char* envUA = std::getenv("UA");
    
    if (!envKeyA || !envKeyB || !envUA) {
        return false;
    }
    
    keyA = StringToBytes(std::string(envKeyA));
    keyB = StringToBytes(std::string(envKeyB));
    UA = std::string(envUA);
    
    return true;
}

const std::string& BearerTokenGenerator::GetUA() {
    return UA;
}

std::vector<unsigned char> BearerTokenGenerator::StringToBytes(const std::string& str) {
    return std::vector<unsigned char>(str.begin(), str.end());
}

std::string BearerTokenGenerator::GetBearer(const std::vector<unsigned char>& bodyContent,
                                          const std::string& path,
                                          const std::string& formattedDate,
                                          const std::string& method) {
    if (keyA.empty() || keyB.empty()) {
        return "";
    }
    
    std::string upperMethod = method;
    std::transform(upperMethod.begin(), upperMethod.end(), upperMethod.begin(), ::toupper);
    std::string combinedString = upperMethod + ":" + path + ":" + formattedDate + "\n";
    std::vector<unsigned char> headerBytes(combinedString.begin(), combinedString.end());

    std::vector<unsigned char> connect_data = connect(headerBytes, bodyContent);

    std::vector<unsigned char> sig = signature(keyB, connect_data);
    if (sig.empty()) {
        return "";
    }

    std::string encodedSignature = base64_encode(std::string(sig.begin(), sig.end()));
    std::string encodedKeyA = base64_encode(std::string(keyA.begin(), keyA.end()));

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
    } catch (...) {
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
