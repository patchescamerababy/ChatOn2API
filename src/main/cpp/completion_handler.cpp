// completion_handler.cpp

#include "completion_handler.h"
#include "header_manager.h"
#include "global.h"
#include "bearer_token_generator.h"  // ȷ��������ͷ�ļ�
#include "image_generations_handler.h"  // ȷ������ʹ�� base64_decode ����
#include "image_uploader.h"

#include <iostream>
#include <string>
#include <regex>
#include <sstream>
#include <ctime>
#include <clocale>
#include <nlohmann/json.hpp>
#include <curl/curl.h>
#include "httplib.h"
#include <chrono>
#include <iomanip>
#include <vector>
#include <stdexcept>
#include <zlib.h>
#include <cstring>
#include <algorithm>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <queue>
#include <memory>
#include <future>

using json = nlohmann::json;

// -------------------------
// ȱʧ�ĺ������岹��
// -------------------------

CompletionHandler::CompletionHandler() : hasImage_(false) {
    std::setlocale(LC_ALL, "zh_CN.UTF-8");
}

// ���������Ҫ�޸�������������ַ����ʱ�������
CURL* CompletionHandler::createCompletionConnection(struct curl_slist* headers, const std::string& jsonBody) {
    CURL* curl = curl_easy_init();
    if (!curl) return nullptr;
    curl_easy_setopt(curl, CURLOPT_URL, "https://api.chaton.ai/chats/stream");

    std::cout << "Completion Request Headers:" << std::endl;
    for (struct curl_slist* temp = headers; temp; temp = temp->next) {
        std::cout << temp->data << std::endl;
    }
    curl_easy_setopt(curl, CURLOPT_POST, 1L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 60L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 60L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);
    // ������������п���
    //curl_easy_setopt(curl, CURLOPT_PROXY, "127.0.0.1");
    //curl_easy_setopt(curl, CURLOPT_PROXYPORT, 5257L);

    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, jsonBody.c_str());
    curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, static_cast<long>(jsonBody.length()));

    return curl;
}

// ����ʽ�ص���������ȫ������һ�� std::string���ɵ��߼����������޸Ŀɱ�����
size_t CompletionResponseCallback(void* contents, size_t size, size_t nmemb, std::string* s) {
    size_t newLength = size * nmemb;
    try {
        s->append(static_cast<char*>(contents), newLength);
        return newLength;
    } catch (std::bad_alloc& e) {
        return 0;
    }
}

// -------------------------
// ���������ռ����һЩ��������
// -------------------------
namespace {

    // �л���ṹ������������-������ģ��
    struct StreamBuffer {
        std::mutex mtx;
        std::condition_variable cv;
        std::queue<std::string> lines;
        bool finished = false;          // ����Ƿ��������ݽ������
        std::string partialBuffer;      // ��Ų���������һ������
    };

    // Curl д�ص���ÿ�ζ���һ�����ݣ�ƴ�ӵ� partialBuffer�����û��з��з�������
    size_t CurlWriteCallback(void* ptr, size_t size, size_t nmemb, void* userdata) {
        size_t total = size * nmemb;
        auto streamBuffer = static_cast<StreamBuffer*>(userdata);
        std::string data(static_cast<char*>(ptr), total);
        {
            std::lock_guard<std::mutex> lock(streamBuffer->mtx);
            // �����¶�ȡ����������ӵ� partialBuffer
            streamBuffer->partialBuffer += data;
            // ���һ��з����������з������
            size_t pos = 0;
            while ((pos = streamBuffer->partialBuffer.find('\n')) != std::string::npos) {
                std::string line = streamBuffer->partialBuffer.substr(0, pos);
                streamBuffer->partialBuffer.erase(0, pos + 1);
                streamBuffer->lines.push(line);
                streamBuffer->cv.notify_one();
            }
        }
        return total;
    }

    // ȥ���ַ������ҵĿհ��ַ�
    static std::string trim(const std::string &s) {
        size_t start = s.find_first_not_of(" \t\n\r");
        if (start == std::string::npos)
            return "";
        size_t end = s.find_last_not_of(" \t\n\r");
        return s.substr(start, end - start + 1);
    }

    // �ж� SSE ��Ϣ���Ƿ������Ҫ���˵����ݣ����� ping �� analytics ��Ϣ��
    static bool shouldFilterOut(const json &j) {
        if (j.contains("ping"))
            return true;
        if (j.contains("data")) {
            json data = j["data"];
            if (data.contains("analytics"))
                return true;
            if (data.contains("operation") && data.contains("message"))
                return true;
        }
        return false;
    }
}

// -------------------------
// ����Ϊ CompletionHandler ��������Ա����
// -------------------------

// �򵥵� token ����ʾ��
int countTokens(const std::string& text) {
    if (text.empty()) return 0;
    std::istringstream iss(text);
    int tokenCount = 0;
    std::string token;
    while (iss >> token) {
        tokenCount++;
    }
    return tokenCount;
}

// �ۺ� SSE ��Ӧ������Ƿ���ʽ�������� SSE �õ�����һ���Խ���
std::pair<std::string, int> CompletionHandler::aggregateSSEResponse(const std::string& sseResponse) {
    std::istringstream iss(sseResponse);
    std::string line;
    std::string aggregatedContent;
    int completionTokens = 0;

    while (std::getline(iss, line)) {
        if (line.rfind("data: ", 0) == 0) {
            std::string data = trim(line.substr(6));
            if (data == "[DONE]") break;
            try {
                auto sseJson = json::parse(data);
                if (sseJson.contains("choices")) {
                    for (const auto& choice : sseJson["choices"]) {
                        if (choice.contains("delta") && choice["delta"].contains("content")) {
                            std::string content = choice["delta"]["content"].get<std::string>();
                            aggregatedContent += content;
                            completionTokens += countTokens(content);
                        }
                    }
                }
            } catch (std::exception &e) {
                std::cerr << "JSON��������: " << e.what() << std::endl;
            }
        }
    }
    return {aggregatedContent, completionTokens};
}

// ��װ���շ��ظ��û��� JSON
std::string CompletionHandler::buildFinalJson(const std::string& aggregatedContent, int completionTokens, const json& requestJson) {
    json openAIResponse;
    openAIResponse["id"] = "chatcmpl-" + std::to_string(std::time(nullptr));
    openAIResponse["object"] = "chat.completion";
    openAIResponse["created"] = std::time(nullptr);
    openAIResponse["model"] = requestJson.value("model", "unknown");

    json choicesArray = json::array();
    json choiceObject;
    choiceObject["index"] = 0;
    json messageObject;
    messageObject["role"] = "assistant";
    messageObject["content"] = aggregatedContent;
    messageObject["refusal"] = nullptr;
    choiceObject["message"] = messageObject;
    choiceObject["logprobs"] = nullptr;
    choiceObject["finish_reason"] = "stop";
    choicesArray.push_back(choiceObject);
    openAIResponse["choices"] = choicesArray;

    json usageObject;
    int promptTokens = countTokens(aggregatedContent);
    usageObject["prompt_tokens"] = promptTokens;
    usageObject["completion_tokens"] = completionTokens;
    usageObject["total_tokens"] = promptTokens + completionTokens;

    json promptTokensDetails;
    promptTokensDetails["cached_tokens"] = 0;
    promptTokensDetails["audio_tokens"] = 0;
    usageObject["prompt_tokens_details"] = promptTokensDetails;

    json completionTokensDetails;
    completionTokensDetails["reasoning_tokens"] = 0;
    completionTokensDetails["audio_tokens"] = 0;
    completionTokensDetails["accepted_prediction_tokens"] = 0;
    completionTokensDetails["rejected_prediction_tokens"] = 0;
    usageObject["completion_tokens_details"] = completionTokensDetails;

    openAIResponse["usage"] = usageObject;
    openAIResponse["system_fingerprint"] = "fp_" + std::to_string(std::time(nullptr));

    return openAIResponse.dump();
}

// ���� OPTIONS ����
void CompletionHandler::handleOptionsRequest(httplib::Response& res) {
    res.set_header("Access-Control-Allow-Origin", "*");
    res.set_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    res.set_header("Access-Control-Allow-Headers", "Content-Type, Authorization");
    res.set_header("Connection", "keep-alive");
    res.status = 204;
}

// ���� GET ����
void CompletionHandler::handleGetRequest(httplib::Response& res) {
    res.set_header("Access-Control-Allow-Origin", "*");
    res.set_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    res.set_header("Access-Control-Allow-Headers", "Content-Type, Authorization");
    res.set_header("Connection", "keep-alive");

    std::string response = R"(
        <html>
          <head><meta charset="UTF-8"><title>��ӭʹ��API</title></head>
          <body>
            <h1>��ӭʹ��API</h1>
            <p>�� API ������ ChatGPT / Claude ģ�ͽ����������Է�����Ϣ��ģ�Ͳ�������Ӧ��</p>
          </body>
        </html>)";

    res.set_header("Content-Type", "text/html; charset=utf-8");
    res.status = 200;
    res.set_content(response, "text/html");
}

// �����͹����� messages��������Ҫ�ϴ�ͼƬ��
nlohmann::json CompletionHandler::processMessages(const nlohmann::json& messages) {
    nlohmann::json processedMessages = nlohmann::json::array();
    if (!messages.is_array()) return processedMessages;

    for (const auto& message : messages) {
        nlohmann::json processedMessage = message;
        std::string role = message.value("role", "");

        if (message.contains("content")) {
            auto contentValue = message["content"];
            if (contentValue.is_array()) {
                std::string textContent;
                bool messageHasImage = false;
                nlohmann::json imagesArray = nlohmann::json::array();
                // ��������첽ͼƬ�ϴ������ future
                std::vector<std::future<std::string>> imageUploadFutures;

                for (const auto& item : contentValue) {
                    if (item.contains("type")) {
                        std::string type = item["type"];
                        if (type == "text" && item.contains("text")) {
                            textContent += item["text"].get<std::string>() + " ";
                        }
                        else if (type == "image_url" && item.contains("image_url")) {
                            std::string imageUrl = item["image_url"]["url"].get<std::string>();
                            if (imageUrl.rfind("data:image/", 0) == 0) {
                                // �첽�ϴ� base64 ��ʽ��ͼƬ
                                imageUploadFutures.push_back(
                                    std::async(std::launch::async, [imageUrl]() -> std::string {
                                        return uploadImageFromDataUrl(imageUrl);
                                        })
                                );
                            }
                            else {
                                // ��׼ URL��ֱ�Ӽ�����
                                std::cout << "���յ���׼ͼƬ URL: " << imageUrl << std::endl;
                                nlohmann::json imageObj;
                                imageObj["data"] = imageUrl;
                                imagesArray.push_back(imageObj);
                            }
                            messageHasImage = true;
                            hasImage_ = true;
                        }
                    }
                }

                // �ȴ������첽�ϴ�������ɣ����ռ����ص� URL
                for (auto& fut : imageUploadFutures) {
                    std::string uploadedUrl = fut.get();
                    if (uploadedUrl.empty()) {
                        throw std::runtime_error("ͼƬ�ϴ�ʧ�ܣ����Ժ����ԡ�");
                    }
                    std::cout << "Base64 ͼƬ���ϴ���URL: " << uploadedUrl << std::endl;
                    nlohmann::json imageObj;
                    imageObj["data"] = uploadedUrl;
                    imagesArray.push_back(imageObj);
                }

                // �Ƴ��ı�ĩβ����Ŀո�
                if (!textContent.empty() && textContent.back() == ' ')
                    textContent.pop_back();

                if (textContent.empty() && !messageHasImage) {
                    std::cout << "�Ƴ�����Ϊ�յ���Ϣ��" << std::endl;
                    continue;
                }
                else {
                    processedMessage["content"] = textContent;
                    if (messageHasImage)
                        processedMessage["images"] = imagesArray;
                }
            }
            else if (contentValue.is_string()) {
                std::string contentStr = trim(contentValue.get<std::string>());
                if (contentStr.empty()) {
                    std::cout << "�Ƴ�����Ϊ�յ���Ϣ��" << std::endl;
                    continue;
                }
                processedMessage["content"] = contentStr;
            }
            else {
                std::cout << "�Ƴ���Ԥ�����͵���Ϣ��" << std::endl;
                continue;
            }
        }
        // system ��Ϣ�п���׷��һЩǰ����ʾ
        if (role == "system" && processedMessage.contains("content")) {
            std::string systemContent = processedMessage["content"];
            if (systemContent.find("This dialog contains a call to the web search function") == std::string::npos) {
                systemContent =
                    "This dialog contains a call to the web search function. Use it only when you need to get up-to-date data or data that is not in your training database.\n"
                    + systemContent;
            }
            processedMessage["content"] = systemContent;
        }
        processedMessages.push_back(processedMessage);
    }
    return processedMessages;
}
// ���ģ���ʽ��Ӧ������ curl + chunked_content_provider���յ�һ�оͷ���һ��
void CompletionHandler::handleStreamResponse(const httplib::Request& req, httplib::Response& res,const json& requestJson) {
    res.set_header("Access-Control-Allow-Origin", "*");
    res.set_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    res.set_header("Access-Control-Allow-Headers", "Content-Type, Authorization");
    res.set_header("Connection", "keep-alive");

    // ��������ͷ
    struct curl_slist* headers = getHeaderManager().getApiHeaders(HeaderManager::ApiType::CHAT_COMPLETIONS, requestJson);
    // ��ӡ����־
    std::string jsonBody = requestJson.dump(-1, ' ', false, nlohmann::json::error_handler_t::replace);
    std::cout << "Stream request JSON size: " << jsonBody.size() << " bytes" << std::endl;

    // ��������������������-������
    auto streamBuffer = std::make_shared<StreamBuffer>();

    // �������̣߳����� curl ���󣬲��ϰѶ��������ݰ���д�� streamBuffer
    std::thread curlThread([headers, jsonBody, streamBuffer, this]() {
        CURL* curl = createCompletionConnection(headers, jsonBody);
        if (!curl) {
            std::lock_guard<std::mutex> lock(streamBuffer->mtx);
            streamBuffer->lines.push("data: [DONE]");
            streamBuffer->finished = true;
            streamBuffer->cv.notify_all();
            return;
        }
        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, CurlWriteCallback);
        curl_easy_setopt(curl, CURLOPT_WRITEDATA, streamBuffer.get());
        CURLcode result = curl_easy_perform(curl);
        if (result != CURLE_OK) {
            std::cerr << "CURL error: " << curl_easy_strerror(result) << std::endl;
        }

        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);

        {
            std::lock_guard<std::mutex> lock(streamBuffer->mtx);
            // �������û������� partialBuffer��Ҳ�ɰ�������һ��
            if (!streamBuffer->partialBuffer.empty()) {
                streamBuffer->lines.push(streamBuffer->partialBuffer);
                streamBuffer->partialBuffer.clear();
            }
            streamBuffer->finished = true;
            streamBuffer->cv.notify_all();
        }
    });
    curlThread.detach();

    // �����ߣ����� chunked_content_provider��һ�δε�����������в������ͻ���
    res.set_header("Content-Type", "text/event-stream; charset=utf-8");
    res.set_chunked_content_provider("text/event-stream",
        [streamBuffer, requestJson](size_t /*offset*/, httplib::DataSink& sink) {
            while (true) {
                std::unique_lock<std::mutex> lock(streamBuffer->mtx);
                // �ȵ������ݻ��߽���
                streamBuffer->cv.wait(lock, [&](){
                    return !streamBuffer->lines.empty() || streamBuffer->finished;
                });

                // һ�ο����ж���
                while (!streamBuffer->lines.empty()) {
                    std::string line = streamBuffer->lines.front();
                    streamBuffer->lines.pop();
                    lock.unlock();

                    // ������� "data: " ��ͷ����˵����һ�� SSE ����
                    if (line.rfind("data: ", 0) == 0) {
                        std::string dataPart = trim(line.substr(6));
                        // ����� [DONE] �ͽ���
                        if (dataPart == "[DONE]") {
                            std::string doneMsg = "data: [DONE]\n\n";
                            sink.write(doneMsg.c_str(), doneMsg.size());
                            sink.done();
                            return true;
                        }
                        // ����ɽ��� JSON�����Ƿ�Ҫ����
                        try {
                            json j = json::parse(dataPart);
                            if (shouldFilterOut(j)) {
                                // ���˲���Ҫ����Ϣ
                                lock.lock();
                                continue;
                            }
                            // ������� web ������������Դ���������
                            if (j.contains("data") && j["data"].contains("web")) {
                                json webData = j["data"]["web"];
                                if (webData.contains("sources")) {
                                    json sources = webData["sources"];
                                    std::string content;
                                    for (const auto& source : sources) {
                                        std::string title = source.value("title", "");
                                        std::string url = source.value("url", "");
                                        content += "\n### " + title + "\n" + url + "\n";
                                    }
                                    // �ٹ���һ�� SSE json
                                    json newJson;
                                    newJson["id"] = "chatcmpl-" + std::to_string(std::time(nullptr));
                                    newJson["object"] = "chat.completion.chunk";
                                    newJson["created"] = std::time(nullptr);
                                    newJson["model"] = requestJson.value("model", "gpt-4o");
                                    json choices = json::array();
                                    json choice;
                                    json delta;
                                    delta["content"] = content;
                                    choice["delta"] = delta;
                                    choice["index"] = 0;
                                    choice["finish_reason"] = nullptr;
                                    choices.push_back(choice);
                                    newJson["choices"] = choices;

                                    std::string newLine = "data: " + newJson.dump() + "\n\n";
                                    sink.write(newLine.c_str(), newLine.size());
                                    lock.lock();
                                    continue;
                                }
                            }
                        } catch (std::exception &e) {
                            std::cerr << "JSON��������: " << e.what() << std::endl;
                        }
                    }

                    // ����ͨ�л򡰽�������ȻҪ���͸��ͻ��ˡ��������
                    std::string output = line + "\n\n";
                    sink.write(output.c_str(), output.size());

                    lock.lock();
                }

                // ���ѽ����Ҷ��пգ�����
                if (streamBuffer->finished && streamBuffer->lines.empty()) {
                    sink.done();
                    return true;
                }
            }
        }
    );
}

// ����ʽ��Ӧ��һ������
void CompletionHandler::handleNonStreamResponse(const httplib::Request& req, httplib::Response& res, const json& requestJson) {
    res.set_header("Access-Control-Allow-Origin", "*");
    res.set_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    res.set_header("Access-Control-Allow-Headers", "Content-Type, Authorization");
    res.set_header("Connection", "keep-alive");

    struct curl_slist* headers = getHeaderManager().getApiHeaders(HeaderManager::ApiType::CHAT_COMPLETIONS, requestJson);
    std::string jsonBody = requestJson.dump(-1, ' ', false, nlohmann::json::error_handler_t::replace);
    std::cout << "Non-stream request JSON size: " << jsonBody.size() << " bytes" << std::endl;

    CURL* curl = createCompletionConnection(headers, jsonBody);
    if (!curl) {
        sendError(res, "Failed to initialize CURL", 500);
        curl_slist_free_all(headers);
        return;
    }

    std::string sseResponse;
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, CompletionResponseCallback);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &sseResponse);
    CURLcode result = curl_easy_perform(curl);
    if (result != CURLE_OK) {
        sendError(res, std::string("CURL error: ") + curl_easy_strerror(result), 500);
        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);
        return;
    }

    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);

    // ���� SSE ���ݣ�ƴ�������������
    auto [aggregatedContent, completionTokens] = aggregateSSEResponse(sseResponse);
    std::string finalJson = buildFinalJson(aggregatedContent, completionTokens, requestJson);

    std::cout << "������Ӧ JSON:\n" << finalJson << std::endl;
    res.set_header("Content-Type", "application/json; charset=utf-8");
    res.set_content(finalJson, "application/json; charset=utf-8");
}

// ���� POST �����ж��Ƿ�Ҫ��ʽ
void CompletionHandler::handlePostRequest(const httplib::Request& req, httplib::Response& res) {
    res.set_header("Access-Control-Allow-Origin", "*");
    res.set_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    res.set_header("Access-Control-Allow-Headers", "Content-Type, Authorization");
    res.set_header("Connection", "keep-alive");

    json requestJson;
    try {
        requestJson = json::parse(req.body);
    } catch (std::exception& e) {
        sendError(res, "Invalid JSON in request body", 400);
        return;
    }
    hasImage_ = false;

    int maxTokens = requestJson.value("max_tokens", 8000);
    std::string model = requestJson.value("model", "gpt-4o");
    bool isStream = requestJson.value("stream", false);

    json messages = requestJson.value("messages", json::array());
    json processedMessages = processMessages(messages);
    if (processedMessages.empty()) {
        sendError(res, "������Ϣ�����ݾ�Ϊ�ա�", 400);
        return;
    }

    // ����ģ�������������Լ����߼�
    if (model == "claude-3.5-sonnet")
        model = "claude-3-5-sonnet";
    else if (model == "gpt 4o")
        model = "gpt-4o";

    // ������������� JSON
    json newRequestJson;
    newRequestJson["function_image_gen"] = true;
    newRequestJson["function_web_search"] = true;
    newRequestJson["max_tokens"] = maxTokens;
    newRequestJson["web_search_engine"] = "auto";
    newRequestJson["model"] = model;
    newRequestJson["source"] = (hasImage_ ? "chat/image_upload" : "chat/free");
    newRequestJson["messages"] = processedMessages;

    std::cout << "Modified Request JSON:\n" << newRequestJson.dump(4) << "\n" << std::endl;

    // ��ʽ���Ƿ���ʽ
    //std::string token = "";  // ����Ҫ token�������л�ȡ
    if (isStream)
        handleStreamResponse(req, res,  newRequestJson);
    else
        handleNonStreamResponse(req, res,  newRequestJson);
}

// ͳһ���� handle
void CompletionHandler::handle(const httplib::Request& req, httplib::Response& res) {
    if (req.method == "OPTIONS") {
        handleOptionsRequest(res);
    } else if (req.method == "GET") {
        handleGetRequest(res);
    } else if (req.method == "POST") {
        handlePostRequest(req, res);
    } else {
        sendError(res, "Method Not Allowed", 405);
    }
}
