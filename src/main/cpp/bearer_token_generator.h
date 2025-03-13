#ifndef BEARER_TOKEN_GENERATOR_H
#define BEARER_TOKEN_GENERATOR_H

#include <string>
#include <vector>

// Base64编码函数声明
std::string base64Encode(const std::vector<unsigned char>& data);

class BearerTokenGenerator {
public:

    static std::string GetBearer(const std::vector<unsigned char>& bodyContent,
                                const std::string& path,
                                const std::string& formattedDate,
                                const std::string& method);
    

    static bool Initialize();
    

    static const std::string& UA;

private:

    static std::vector<unsigned char> keyA;
    static std::vector<unsigned char> keyB;


    static std::vector<unsigned char> signature(const std::vector<unsigned char>& key,
                                              const std::vector<unsigned char>& data);

    static std::vector<unsigned char> connect(const std::vector<unsigned char>& a,
                                            const std::vector<unsigned char>& b);
                                            

    static std::vector<unsigned char> StringToBytes(const std::string& str);
};

#endif // BEARER_TOKEN_GENERATOR_H
