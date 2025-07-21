#ifndef BEARER_TOKEN_GENERATOR_H
#define BEARER_TOKEN_GENERATOR_H

#include <string>
#include <vector>

// Base64���뺯������
//std::string base64Encode(const std::vector<unsigned char>& data);

class BearerTokenGenerator {
public:
    // ����Bearer����
    static std::string GetBearer(const std::vector<unsigned char>& bodyContent,
                                 const std::string& path,
                                 const std::string& formattedDate,
                                 const std::string& method);
    // �û������ַ���
    static const std::string UA;

private:
    // ��ԿA��B
    static const std::vector<unsigned char> keyA;
    static const std::vector<unsigned char> keyB;

    // ����HMAC-SHA256ǩ��
    static std::vector<unsigned char> signature(const std::vector<unsigned char>& key,
                                                const std::vector<unsigned char>& data);
    // ���������ֽ�����
    static std::vector<unsigned char> connect(const std::vector<unsigned char>& a,
                                              const std::vector<unsigned char>& b);
};

#endif // BEARER_TOKEN_GENERATOR_H