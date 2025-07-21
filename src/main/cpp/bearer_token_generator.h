#ifndef BEARER_TOKEN_GENERATOR_H
#define BEARER_TOKEN_GENERATOR_H

#include <string>
#include <vector>

// Base64编码函数声明
//std::string base64Encode(const std::vector<unsigned char>& data);

class BearerTokenGenerator {
public:
    // 生成Bearer令牌
    static std::string GetBearer(const std::vector<unsigned char>& bodyContent,
                                 const std::string& path,
                                 const std::string& formattedDate,
                                 const std::string& method);
    // 用户代理字符串
    static const std::string UA;

private:
    // 密钥A和B
    static const std::vector<unsigned char> keyA;
    static const std::vector<unsigned char> keyB;

    // 生成HMAC-SHA256签名
    static std::vector<unsigned char> signature(const std::vector<unsigned char>& key,
                                                const std::vector<unsigned char>& data);
    // 连接两个字节数组
    static std::vector<unsigned char> connect(const std::vector<unsigned char>& a,
                                              const std::vector<unsigned char>& b);
};

#endif // BEARER_TOKEN_GENERATOR_H