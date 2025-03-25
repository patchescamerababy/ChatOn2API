#ifndef BEARER_TOKEN_GENERATOR_H
#define BEARER_TOKEN_GENERATOR_H

#include <string>
#include <vector>

class BearerTokenGenerator {
public:

    static std::string GetBearer(const std::vector<unsigned char>& bodyContent,
                                 const std::string& path,
                                 const std::string& formattedDate,
                                 const std::string& method);

    static const std::string UA;


    static std::string getUA();
    static std::vector<unsigned char> getKeyA();
    static std::vector<unsigned char> getKeyB();


    static const std::vector<unsigned char> keyA;
    static const std::vector<unsigned char> keyB;

private:

    static std::vector<unsigned char> signature(const std::vector<unsigned char>& key,
                                                const std::vector<unsigned char>& data);

    static std::vector<unsigned char> connect(const std::vector<unsigned char>& a,
                                              const std::vector<unsigned char>& b);
};

#endif // BEARER_TOKEN_GENERATOR_H
