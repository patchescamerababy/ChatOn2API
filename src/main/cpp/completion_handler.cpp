// completion_handler.cpp

#include "completion_handler.h"
#include "header_manager.h"
#include "global.h"
#include "bearer_token_generator.h"  // 确保包含该头文件
#include "image_generations_handler.h"  // 确保可以使用 base64_decode 方法
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
// 缺失的函数定义补充
// -------------------------

CompletionHandler::CompletionHandler() : hasImage_(false) {
    std::setlocale(LC_ALL, "zh_CN.UTF-8");
}

// 这里根据需要修改你的上游请求地址、超时、代理等
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
    // 如需代理请自行开启
    //curl_easy_setopt(curl, CURLOPT_PROXY, "127.0.0.1");
    //curl_easy_setopt(curl, CURLOPT_PROXYPORT, 5257L);

    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, jsonBody.c_str());
    curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, static_cast<long>(jsonBody.length()));

    return curl;
}

// 非流式回调：将数据全部放入一个 std::string（旧的逻辑，如无需修改可保留）
size_t CompletionResponseCallback(void* contents, size_t size, size_t nmemb, std::string* s) {
    size_t newLength = size * nmemb;
    try {
        s->append(static_cast<char*>(contents), newLength);
        return newLength;
    }
    catch (std::bad_alloc& e) {
        return 0;
    }
}

// -------------------------
// 匿名命名空间放置一些辅助函数
// -------------------------
namespace {

    // 行缓冲结构，用于生产者-消费者模型
    struct StreamBuffer {
        std::mutex mtx;
        std::condition_variable cv;
        std::queue<std::string> lines;
        bool finished = false;          // 标记是否上游数据接收完毕
        std::string partialBuffer;      // 存放不完整的那一截数据
    };

    // Curl 写回调：每次读到一批数据，拼接到 partialBuffer，再用换行符切分完整行
    size_t CurlWriteCallback(void* ptr, size_t size, size_t nmemb, void* userdata) {
        size_t total = size * nmemb;
        auto streamBuffer = static_cast<StreamBuffer*>(userdata);
        std::string data(static_cast<char*>(ptr), total);
        {
            std::lock_guard<std::mutex> lock(streamBuffer->mtx);
            // 将最新读取到的数据添加到 partialBuffer
            streamBuffer->partialBuffer += data;
            // 查找换行符，将完整行分离出来
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

    // 去掉字符串左右的空白字符
    static std::string trim(const std::string& s) {
        size_t start = s.find_first_not_of(" \t\n\r");
        if (start == std::string::npos)
            return "";
        size_t end = s.find_last_not_of(" \t\n\r");
        return s.substr(start, end - start + 1);
    }

    // 判断 SSE 消息中是否包含需要过滤的内容（例如 ping 或 analytics 信息）
    static bool shouldFilterOut(const json& j) {
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
// 以下为 CompletionHandler 类其他成员函数
// -------------------------

// 简单的 token 计数示例
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

// 聚合 SSE 响应：如果是非流式，把所有 SSE 拿到后再一次性解析
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
            }
            catch (std::exception& e) {
                std::cerr << "JSON解析错误: " << e.what() << std::endl;
            }
        }
    }
    return { aggregatedContent, completionTokens };
}

// 组装最终返回给用户的 JSON
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

// 处理 OPTIONS 请求
void CompletionHandler::handleOptionsRequest(httplib::Response& res) {
    res.set_header("Access-Control-Allow-Origin", "*");
    res.set_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    res.set_header("Access-Control-Allow-Headers", "Content-Type, Authorization");
    res.set_header("Connection", "keep-alive");
    res.status = 204;
}

// 处理 GET 请求
void CompletionHandler::handleGetRequest(httplib::Response& res) {
    res.set_header("Access-Control-Allow-Origin", "*");
    res.set_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    res.set_header("Access-Control-Allow-Headers", "Content-Type, Authorization");
    res.set_header("Connection", "keep-alive");

    std::string response = R"(
        <html>
          <head><meta charset="UTF-8"><title>欢迎使用API</title></head>
          <body>
            <h1>欢迎使用API</h1>
            <p>此 API 用于与 ChatGPT / Claude 模型交互。您可以发送消息给模型并接收响应。</p>
          </body>
        </html>)";

    res.set_header("Content-Type", "text/html; charset=utf-8");
    res.status = 200;
    res.set_content(response, "text/html");
}

// 处理发送过来的 messages，根据需要上传图片等
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
                // 存放所有异步图片上传任务的 future
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
                                // 异步上传 base64 格式的图片
                                imageUploadFutures.push_back(
                                    std::async(std::launch::async, [imageUrl]() -> std::string {
                                        return uploadImageFromDataUrl(imageUrl);
                                        })
                                );
                            }
                            else {
                                // 标准 URL，直接加入结果
                                std::cout << "接收到标准图片 URL: " << imageUrl << std::endl;
                                nlohmann::json imageObj;
                                imageObj["data"] = imageUrl;
                                imagesArray.push_back(imageObj);
                            }
                            messageHasImage = true;
                            hasImage_ = true;
                        }
                    }
                }

                // 等待所有异步上传任务完成，并收集返回的 URL
                for (auto& fut : imageUploadFutures) {
                    std::string uploadedUrl = fut.get();
                    if (uploadedUrl.empty()) {
                        throw std::runtime_error("图片上传失败，请稍后重试。");
                    }
                    std::cout << "Base64 图片已上传，URL: " << uploadedUrl << std::endl;
                    nlohmann::json imageObj;
                    imageObj["data"] = uploadedUrl;
                    imagesArray.push_back(imageObj);
                }

                // 移除文本末尾多余的空格
                if (!textContent.empty() && textContent.back() == ' ')
                    textContent.pop_back();

                if (textContent.empty() && !messageHasImage) {
                    std::cout << "移除内容为空的消息。" << std::endl;
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
                    std::cout << "移除内容为空的消息。" << std::endl;
                    continue;
                }
                processedMessage["content"] = contentStr;
            }
            else {
                std::cout << "移除非预期类型的消息。" << std::endl;
                continue;
            }
        }
        // system 消息中可能追加一些前置提示
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


// 核心：流式响应，采用 curl + chunked_content_provider，收到一行就发送一行
void CompletionHandler::handleStreamResponse(const httplib::Request& req, httplib::Response& res, const json& requestJson) {
    res.set_header("Access-Control-Allow-Origin", "*");
    res.set_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    res.set_header("Access-Control-Allow-Headers", "Content-Type, Authorization");
    res.set_header("Connection", "keep-alive");

    // 构造请求头
    struct curl_slist* headers = getHeaderManager().getApiHeaders(HeaderManager::ApiType::CHAT_COMPLETIONS, requestJson);
    // 打印或日志
    std::string jsonBody = requestJson.dump(-1, ' ', false, nlohmann::json::error_handler_t::replace);
    std::cout << "Stream request JSON size: " << jsonBody.size() << " bytes" << std::endl;

    // 共享缓冲区，用于生产者-消费者
    auto streamBuffer = std::make_shared<StreamBuffer>();

    // 生产者线程：发起 curl 请求，不断把读到的数据按行写入 streamBuffer
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
            // 如果还有没处理完的 partialBuffer，也可把它当作一行
            if (!streamBuffer->partialBuffer.empty()) {
                streamBuffer->lines.push(streamBuffer->partialBuffer);
                streamBuffer->partialBuffer.clear();
            }
            streamBuffer->finished = true;
            streamBuffer->cv.notify_all();
        }
        });
    curlThread.detach();

    // 消费者：启用 chunked_content_provider，一次次弹出队列里的行并发给客户端
    res.set_header("Content-Type", "text/event-stream; charset=utf-8");
    res.set_chunked_content_provider("text/event-stream",
        [streamBuffer, requestJson](size_t /*offset*/, httplib::DataSink& sink) {
            while (true) {
                std::unique_lock<std::mutex> lock(streamBuffer->mtx);
                // 等到有数据或者结束
                streamBuffer->cv.wait(lock, [&]() {
                    return !streamBuffer->lines.empty() || streamBuffer->finished;
                    });

                // 一次可能有多行
                while (!streamBuffer->lines.empty()) {
                    std::string line = streamBuffer->lines.front();
                    streamBuffer->lines.pop();
                    lock.unlock();

                    // 如果行以 "data: " 开头，则说明是一条 SSE 数据
                    if (line.rfind("data: ", 0) == 0) {
                        std::string dataPart = trim(line.substr(6));
                        // 如果是 [DONE] 就结束
                        if (dataPart == "[DONE]") {
                            std::string doneMsg = "data: [DONE]\n\n";
                            sink.write(doneMsg.c_str(), doneMsg.size());
                            sink.done();
                            return true;
                        }
                        // 这里可解析 JSON，看是否要过滤
                        try {
                            json j = json::parse(dataPart);
                            if (shouldFilterOut(j)) {
                                // 过滤不想要的消息
                                lock.lock();
                                continue;
                            }
                            // 如果包含 web 搜索结果，可以处理后再输出
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
                                    // 再构造一个 SSE json
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
                        }
                        catch (std::exception& e) {
                            std::cerr << "JSON解析错误: " << e.what() << std::endl;
                        }
                    }

                    // 将普通行或“解析后依然要发送给客户端”的行输出
                    std::string output = line + "\n\n";
                    sink.write(output.c_str(), output.size());

                    lock.lock();
                }

                // 若已结束且队列空，结束
                if (streamBuffer->finished && streamBuffer->lines.empty()) {
                    sink.done();
                    return true;
                }
            }
        }
    );
}

// 非流式响应：一次拿完
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

    // 解析 SSE 内容，拼成最终完整输出
    auto [aggregatedContent, completionTokens] = aggregateSSEResponse(sseResponse);
    std::string finalJson = buildFinalJson(aggregatedContent, completionTokens, requestJson);

    std::cout << "最终响应 JSON:\n" << finalJson << std::endl;
    res.set_header("Content-Type", "application/json; charset=utf-8");
    res.set_content(finalJson, "application/json; charset=utf-8");
}

// 处理 POST 请求：判断是否要流式
void CompletionHandler::handlePostRequest(const httplib::Request& req, httplib::Response& res) {
    res.set_header("Access-Control-Allow-Origin", "*");
    res.set_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    res.set_header("Access-Control-Allow-Headers", "Content-Type, Authorization");
    res.set_header("Connection", "keep-alive");

    json requestJson;
    try {
        requestJson = json::parse(req.body);
    }
    catch (std::exception& e) {
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
        sendError(res, "所有消息的内容均为空。", 400);
        return;
    }

    // 处理模型名、对齐你自己的逻辑
    if (model == "claude-3.5-sonnet")
        model = "claude-3-5-sonnet";
    else if (model == "gpt 4o")
        model = "gpt-4o";

    // 构造最终请求的 JSON
    json newRequestJson;
    newRequestJson["function_image_gen"] = true;
    newRequestJson["function_web_search"] = true;
    newRequestJson["max_tokens"] = maxTokens;
    newRequestJson["web_search_engine"] = "auto";
    newRequestJson["model"] = model;
    newRequestJson["source"] = (hasImage_ ? "chat/image_upload" : "chat/free");
    newRequestJson["messages"] = processedMessages;

    std::cout << "Modified Request JSON:\n" << newRequestJson.dump(4) << "\n" << std::endl;

    // 流式还是非流式
    //std::string token = "";  // 若需要 token，请自行获取
    if (isStream)
        handleStreamResponse(req, res, newRequestJson);
    else
        handleNonStreamResponse(req, res, newRequestJson);
}

// 统一对外 handle
void CompletionHandler::handle(const httplib::Request& req, httplib::Response& res) {
    if (req.method == "OPTIONS") {
        handleOptionsRequest(res);
    }
    else if (req.method == "GET") {
        handleGetRequest(res);
    }
    else if (req.method == "POST") {
        handlePostRequest(req, res);
    }
    else {
        sendError(res, "Method Not Allowed", 405);
    }
}
