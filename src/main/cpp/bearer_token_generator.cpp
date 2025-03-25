#include "global.h"
#include "bearer_token_generator.h"
#include <openssl/hmac.h>
#include <openssl/evp.h>
#include <sstream>
#include <iomanip>
#include <algorithm>
#include <iostream>
#include <cstdlib> // For getenv
#include <stdexcept> // For std::runtime_error

// 初始化静态成员变量 - 从环境变量获取
std::string BearerTokenGenerator::getUA() {
    const char* env_ua = std::getenv("USER-AGENT");
    return std::string(env_ua);
}

const std::string BearerTokenGenerator::UA = BearerTokenGenerator::getUA();

// 从环境变量获取密钥A
std::vector<unsigned char> BearerTokenGenerator::getKeyA() {
    const char* env_key = std::getenv("KEY_A");
    return std::vector<unsigned char>(env_key, env_key + strlen(env_key));
}

std::vector<unsigned char> BearerTokenGenerator::getKeyB() {
    const char* env_key = std::getenv("KEY_A");

    return std::vector<unsigned char>(env_key, env_key + strlen(env_key));
}


const std::vector<unsigned char> BearerTokenGenerator::keyA = BearerTokenGenerator::getKeyA();
const std::vector<unsigned char> BearerTokenGenerator::keyB = BearerTokenGenerator::getKeyB();

std::string BearerTokenGenerator::GetBearer(const std::vector<unsigned char>& bodyContent,
                                              const std::string& path,
                                              const std::string& formattedDate,
                                              const std::string& method) {
    std::cout << "Bearer token generation - Body content size: " << bodyContent.size() << " bytes" << std::endl;
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