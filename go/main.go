// main.go
package main

import (
	"bufio"
	"bytes"
	"compress/gzip"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/ioutil"
	"log"
	//"mime/multipart"
	"net"
	"net/http"
	"os"
	"os/signal"
	"regexp"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/google/uuid"
)

// Constants and Global Variables
var (
	models = []string{"deepseek-r1", "gpt-4o", "gpt-4o-mini", "claude", "claude-3-haiku", "claude-3-5-sonnet"}
	initialPort = 8080 

	client = &http.Client{
		Transport: &http.Transport{
			Proxy: http.ProxyFromEnvironment,
		},
	}
)

// Utility function to send JSON error responses
func sendError(w http.ResponseWriter, statusCode int, message string) {
	if w.Header().Get("Content-Type") == "" {
		w.Header().Set("Content-Type", "application/json")
	}
	w.WriteHeader(statusCode)
	resp := map[string]string{"error": message}
	jsonResp, _ := json.Marshal(resp)
	_, err := w.Write(jsonResp)
	if err != nil {
		return
	}
}

// Utility function to build HTTP requests to external API

func buildHttpRequestNew(modifiedRequestBody string, tmpToken string, date string) (*http.Request, error) {
	req, err := http.NewRequest("POST", "https://api.chaton.ai/chats/stream", strings.NewReader(modifiedRequestBody))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Date", date)
	req.Header.Set("Client-time-zone", "-04:00")
	req.Header.Set("Authorization", tmpToken)
	req.Header.Set("User-Agent", os.Getenv("USER_AGENT"))
	req.Header.Set("Accept-Language", "en-US")
	req.Header.Set("X-Cl-Options", "hb")
	req.Header.Set("Content-Type", "application/json; charset=UTF-8")
	req.Header.Set("Accept-Encoding", "gzip")
	return req, nil
}

// Handler for /v1/chat/completions
func completionHandler(w http.ResponseWriter, r *http.Request) {
	// Set CORS headers
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")

	if r.Method == http.MethodOptions {
		// Handle preflight request
		w.WriteHeader(http.StatusNoContent)
		return
	}

	if r.Method == http.MethodGet {
		// Return welcome HTML page
		w.Header().Set("Content-Type", "text/html; charset=UTF-8")
		w.WriteHeader(http.StatusOK)
		html := `<html><head><title>欢迎使用API</title></head><body><h1>欢迎使用API</h1><p>此 API 用于与 ChatGPT / Claude 模型交互。您可以发送消息给模型并接收响应。</p></body></html>`
		_, err := w.Write([]byte(html))
		if err != nil {
			return
		}
		return
	}

	if r.Method != http.MethodPost {
		// Method not allowed
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	// Handle the POST request synchronously
	// Read request body
	bodyBytes, err := ioutil.ReadAll(r.Body)
	if err != nil {
		sendError(w, http.StatusBadRequest, "无法读取请求体")
		return
	}
	defer func(Body io.ReadCloser) {
		err := Body.Close()
		if err != nil {

		}
	}(r.Body)

	// Parse JSON
	var requestJson map[string]interface{}
	if err := json.Unmarshal(bodyBytes, &requestJson); err != nil {
		sendError(w, http.StatusBadRequest, "JSON解析错误")
		return
	}
	// Process messages
	var contentBuilder strings.Builder
	messages, ok := requestJson["messages"].([]interface{})
	maxTokens, _ := getInt(requestJson, "max_tokens", 8000)
	model, _ := getString(requestJson, "model", "gpt-4o")
	isStream, _ := getBool(requestJson, "stream", false)
	hasImage := false
	var imageURL string

	if ok {
		var newMessages []interface{}
		// 系统提示内容，注意与 Java 代码保持一致
		systemPrompt := "This dialog contains a call to the web search function. Use it only when you need to get up-to-date data or data that is not in your training database."
		// 检查是否存在 system 角色的消息
		hasSystemMessage := false
		for _, msg := range messages {
			if message, ok := msg.(map[string]interface{}); ok {
				if role, _ := getString(message, "role", ""); strings.ToLower(role) == "system" {
					hasSystemMessage = true
					break
				}
			}
		}
		if !hasSystemMessage {
			systemMessage := map[string]interface{}{
				"role":    "system",
				"content": systemPrompt,
			}
			newMessages = append(newMessages, systemMessage)
		}

		urlContents := make(map[string]string)
		for _, msg := range messages {
			message, ok := msg.(map[string]interface{})
			if !ok {
				continue
			}
			role, _ := getString(message, "role", "")
			if content, exists := message["content"]; exists {
				switch contentTyped := content.(type) {
				case []interface{}:
					var msgContentBuilder strings.Builder
					for _, contentItem := range contentTyped {
						contentMap, ok := contentItem.(map[string]interface{})
						if !ok {
							continue
						}
						if msgType, exists := contentMap["type"].(string); exists {
							if msgType == "text" {
								if text, exists := contentMap["text"].(string); exists {
									msgContentBuilder.WriteString(text)
									msgContentBuilder.WriteString(" ")
								}
							} else if msgType == "image_url" {
								if imageURLMap, exists := contentMap["image_url"].(map[string]interface{}); exists {
									if imageURLStr, exists := imageURLMap["url"].(string); exists {
										if strings.HasPrefix(imageURLStr, "data:image/") {
											parts := strings.Split(imageURLStr, "base64,")
											if len(parts) != 2 {
												continue
											}
											imageBytes, err := base64.StdEncoding.DecodeString(parts[1])
											if err != nil {
												continue
											}
											extension := "jpg"
											if strings.HasPrefix(imageURLStr, "data:image/png") {
												extension = "png"
											} else if strings.HasPrefix(imageURLStr, "data:image/jpeg") || strings.HasPrefix(imageURLStr, "data:image/jpg") {
												extension = "jpg"
											} else {
												partsExt := strings.Split(imageURLStr, "data:image/")
												if len(partsExt) > 1 {
													extension = strings.Split(partsExt[1], ";")[0]
												}
											}
											uploadedURL, err := uploadImage(imageBytes, extension)
											if err != nil {
												log.Println("图片上传失败:", err)
												continue
											}
											imageURL = uploadedURL
											hasImage = true
											log.Printf("图片已上传, 可访问 URL: %s\n", imageURL)
											// 添加上传后的图片 URL 到消息
											imagesArray := []map[string]string{
												{
													"data": imageURL,
												},
											}
											message["images"] = imagesArray
										} else {
											// 处理标准图片 URL
											imageURL = imageURLStr
											hasImage = true
											log.Printf("接收到标准图片 URL: %s\n", imageURL)
											imagesArray := []map[string]string{
												{
													"data": imageURL,
												},
											}
											message["images"] = imagesArray
										}
									}
								}
							}
						}
					}
					// 更新消息内容
					extractedContent := strings.TrimSpace(msgContentBuilder.String())
					if extractedContent == "" && !hasImage {
						// 跳过内容为空的消息
						continue
					} else {
						if strings.ToLower(role) == "system" {
							if !strings.Contains(extractedContent, systemPrompt) {
								extractedContent = systemPrompt + "\n" + extractedContent
							}
						}
						message["content"] = extractedContent
						log.Printf("提取的内容: %s\n", extractedContent)
						contentBuilder.WriteString(extractedContent)
					}
				case string:
					contentStr := strings.TrimSpace(contentTyped)
					if contentStr == "" {
						continue
					} else {
						// 同理处理 system 消息
						if strings.ToLower(role) == "system" {
							if !strings.Contains(contentStr, systemPrompt) {
								contentStr = systemPrompt + "\n" + contentStr
							}
						} else if strings.ToLower(role) == "user" {
							// 处理用户消息中的URL
							re := regexp.MustCompile(`https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+`)
							urls := re.FindAllString(contentStr, -1)

							// 如果找到URL，获取内容
							for _, url := range urls {
								log.Printf("检测到URL: %s\n", url)
								if fetchedContent, exists := urlContents[url]; exists {
									contentStr = contentStr + "\n\n" + fetchedContent
								} else {
									// 获取URL内容并等待响应
									fetchedContent := fetchURL(url)
									urlContents[url] = fetchedContent
									contentStr = contentStr + "\n\n" + fetchedContent
								}
							}
						}
						message["content"] = contentStr
						log.Printf("保留的内容: %s\n", contentStr)
						contentBuilder.WriteString(contentStr)
					}
				default:
					continue
				}
			}
			newMessages = append(newMessages, message)
		}
		requestJson["messages"] = newMessages

		if len(newMessages) == 0 {
			sendError(w, http.StatusBadRequest, "所有消息的内容均为空。")
			return
		}
	}

	// Validate model
	modelValid := false
	for _, m := range models {
		if model == m {
			modelValid = true
			break
		}
	}
	if !modelValid {
		model = "claude-3-5-sonnet"
	}

	// Build new request JSON
	newRequestJson := map[string]interface{}{
		"function_image_gen":  true,
		"function_web_search": true,
		"max_tokens":          maxTokens,
		"model":               model,
		"source":              "chat/free",
		"messages":            requestJson["messages"],
		"web_search_engine":   "auto",
	}
	if hasImage {
		newRequestJson["source"] = "chat/image_upload"
	}

	modifiedRequestBodyBytes, err := json.Marshal(newRequestJson)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "修改请求体时发生错误")
		return
	}
	modifiedRequestBody := string(modifiedRequestBodyBytes)
	log.Printf("修改后的请求 JSON: %s\n", modifiedRequestBodyBytes)
	
	formattedDate := time.Now().UTC().Format("2006-01-02T15:04:05Z")
	token, err := bearerGenerator.GetBearerNew(modifiedRequestBody, "/chats/stream", formattedDate, "POST")
	if err != nil {
		sendError(w, http.StatusInternalServerError, "生成Bearer Token时发生错误")
		return
	}

	// Build external API request
	apiReq, err := buildHttpRequestNew(modifiedRequestBody, token, formattedDate)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "构建外部API请求时发生错误")
		return
	}

	// Send request to external API
	resp, err := client.Do(apiReq)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "请求外部API时发生错误")
		return
	}
	defer func(Body io.ReadCloser) {
		err := Body.Close()
		if err != nil {

		}
	}(resp.Body)

	if resp.StatusCode != http.StatusOK {
		sendError(w, resp.StatusCode, fmt.Sprintf("API 错误: %d", resp.StatusCode))
		return
	}

	// Handle response based on stream and image presence
	if isStream {
		handleStreamResponse(w, resp)
	} else {
		handleNormalResponse(w, resp, model)
	}
}

// Handler for /v1/images/generations
func textToImageHandler(w http.ResponseWriter, r *http.Request) {

	// Set CORS headers
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")

	if r.Method == http.MethodOptions {
		// Handle preflight request
		w.WriteHeader(http.StatusNoContent)
		return
	}

	if r.Method != http.MethodPost {
		// Only allow POST requests
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	// Handle the POST request synchronously
	// Read request body
	bodyBytes, err := ioutil.ReadAll(r.Body)
	if err != nil {
		sendError(w, http.StatusBadRequest, "无法读取请求体")
		return
	}
	defer func(Body io.ReadCloser) {
		err := Body.Close()
		if err != nil {

		}
	}(r.Body)

	// Parse JSON
	var userInput map[string]interface{}
	if err := json.Unmarshal(bodyBytes, &userInput); err != nil {
		sendError(w, http.StatusBadRequest, "JSON解析错误")
		return
	}

	fmt.Printf("Received Image Generations JSON: %s\n", string(bodyBytes))

	// Validate required fields
	prompt, ok := getString(userInput, "prompt", "")
	if !ok || prompt == "" {
		sendError(w, http.StatusBadRequest, "缺少必需的字段: prompt")
		return
	}
	responseFormat, _ := getString(userInput, "response_format", "")
	responseFormat = strings.TrimSpace(responseFormat)

	fmt.Printf("Prompt: %s\n", prompt)

	// Build new TextToImage JSON request body
	textToImageJson := map[string]interface{}{
		"function_image_gen":  true,
		"function_web_search": true,
		"max_tokens":          8000,
		"messages": []interface{}{
			map[string]interface{}{
				"content": "Your role is that of a smart and creative assistant. Do not mention that you are a chatbot or AI assistant. Consider the terms when communicating: 1. The length of your response: Auto. 2. The tone style of your speech: Default. This dialog box has an option to generate images. The function should be called only when the user explicitly requests it - for example, using any related words associated with image generation requests. In other cases - the call of the image generation function should not be called.",
				"role":    "system",
			},
			map[string]interface{}{
				"content": fmt.Sprintf("Draw: %s", prompt),
				"role":    "user",
			},
		},
		"model":  "gpt-4o",         
		"source": "chat/pro_image", 
	}

	modifiedRequestBodyBytes, err := json.Marshal(textToImageJson)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "修改请求体时发生错误")
		return
	}
	modifiedRequestBody := string(modifiedRequestBodyBytes)
	log.Printf("修改后的请求 JSON: %s\n", modifiedRequestBody)

	// Generate Bearer Token
	//tmpToken, err := bearerGenerator.GetBearer(modifiedRequestBody)
	//if err != nil {
	//	sendError(w, http.StatusInternalServerError, "生成Bearer Token时发生错误")
	//	return
	//}
	//
	//// Build external API request
	//apiReq, err := buildHttpRequest(modifiedRequestBody, tmpToken)
	formattedDate := time.Now().UTC().Format("2006-01-02T15:04:05Z")

	// 生成上传图片的 Bearer Token，传入空字节数组、上传路径 "/storage/upload" 以及 HTTP 方法 "POST"
	token, err := bearerGenerator.GetBearerNew(modifiedRequestBody, "/chats/stream", formattedDate, "POST")
	fmt.Printf("%s", token)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "生成Bearer Token时发生错误")
		return
	}

	// Build external API request

	apiReq, err := buildHttpRequestNew(modifiedRequestBody, token, formattedDate)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "构建外部API请求时发生错误")
		return
	}

	// Send request to external API
	resp, err := client.Do(apiReq)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "请求外部API时发生错误")
		return
	}
	defer func(Body io.ReadCloser) {
		err := Body.Close()
		if err != nil {

		}
	}(resp.Body)

	if resp.StatusCode != http.StatusOK {
		sendError(w, resp.StatusCode, fmt.Sprintf("API 错误: %d", resp.StatusCode))
		return
	}

	// Handle SSE stream and process image
	var urlBuilder strings.Builder

	scanner := bufio.NewScanner(resp.Body)
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "data: ") {
			data := strings.TrimSpace(line[6:])
			if data == "[DONE]" {
				break // Finished reading
			}

			var sseJson map[string]interface{}
			if err := json.Unmarshal([]byte(data), &sseJson); err != nil {
				log.Println("JSON解析错误:", err)
				continue
			}

			if choices, exists := sseJson["choices"].([]interface{}); exists {
				for _, choice := range choices {
					choiceMap, ok := choice.(map[string]interface{})
					if !ok {
						continue
					}
					if delta, exists := choiceMap["delta"].(map[string]interface{}); exists {
						if content, exists := delta["content"].(string); exists {
							urlBuilder.WriteString(content)
						}
					}
				}
			}
		}
	}

	imageMarkdown := urlBuilder.String()

	// Step 1: Check if Markdown text is empty
	if imageMarkdown == "" {
		log.Println("无法从 SSE 流中构建图像 Markdown。")
		sendError(w, http.StatusInternalServerError, "无法从 SSE 流中构建图像 Markdown。")
		return
	}

	// Step 2: Extract the first image path from Markdown
	extractedPath, err := extractPathFromMarkdown(imageMarkdown)
	if err != nil || extractedPath == "" {
		log.Println("无法从 Markdown 中提取路径。")
		sendError(w, http.StatusInternalServerError, "无法从 Markdown 中提取路径。")
		return
	}

	log.Printf("提取的路径: %s\n", extractedPath)

	// Step 3: Remove the "https://spc.unk/" prefix
	extractedPath = strings.Replace(extractedPath, "https://spc.unk/", "", 1)

	// Step 4: Construct the final storage URL
	storageUrl := fmt.Sprintf("https://api.chaton.ai/storage/%s", extractedPath)
	log.Printf("存储URL: %s\n", storageUrl)

	// Step 5: Fetch the final download URL from storageUrl
	finalDownloadUrl, err := fetchGetUrlFromStorage(storageUrl)
	if err != nil || finalDownloadUrl == "" {
		sendError(w, http.StatusInternalServerError, "无法从 storage URL 获取最终下载链接。")
		return
	}

	log.Printf("Final Download URL: %s\n", finalDownloadUrl)

	// Step 6: Prepare the response based on response_format
	if strings.EqualFold(responseFormat, "b64_json") {
		// Download the image
		imageBytes, err := downloadImage(finalDownloadUrl)
		if err != nil {
			sendError(w, http.StatusInternalServerError, "无法从 URL 下载图像。")
			return
		}

		// Convert image to Base64
		imageBase64 := base64.StdEncoding.EncodeToString(imageBytes)

		responseJson := map[string]interface{}{
			"created": time.Now().Unix(),
			"data": []interface{}{
				map[string]interface{}{
					"b64_json": imageBase64,
				},
			},
		}

		responseBody, _ := json.Marshal(responseJson)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, err = w.Write(responseBody)
		if err != nil {
			return
		}
	} else {
		// Return the URL directly
		responseJson := map[string]interface{}{
			"created": time.Now().Unix(),
			"data": []interface{}{
				map[string]interface{}{
					"url": finalDownloadUrl,
				},
			},
		}

		responseBody, _ := json.Marshal(responseJson)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, err := w.Write(responseBody)
		if err != nil {
			return
		}
	}
}

// Handle Normal Response
// handleNormalResponse 处理非流式响应并构建 OpenAI 风格的 JSON 响应
func handleNormalResponse(w http.ResponseWriter, resp *http.Response, model string) {
	bodyBytes, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "读取API响应时发生错误")
		return
	}

	// 解析 SSE 行
	scanner := bufio.NewScanner(bytes.NewReader(bodyBytes))
	var contentBuilder strings.Builder
	completionTokens := 0

	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "data: ") {
			data := strings.TrimSpace(line[6:])
			if data == "[DONE]" {
				break
			}

			var sseJson map[string]interface{}
			if err := json.Unmarshal([]byte(data), &sseJson); err != nil {
				log.Println("JSON解析错误:", err)
				continue
			}

			// 检查是否包含 'choices' 数组
			if choices, exists := sseJson["choices"].([]interface{}); exists {
				for _, choice := range choices {
					if choiceMap, ok := choice.(map[string]interface{}); ok {
						if delta, exists := choiceMap["delta"].(map[string]interface{}); exists {
							if content, exists := delta["content"].(string); exists {
								contentBuilder.WriteString(content)
								completionTokens += len(content) // 简单估计 token 数
								print(content)
							}
						}
					}
				}
			}
		}
	}

	if err := scanner.Err(); err != nil {
		log.Println("读取响应体错误:", err)
		sendError(w, http.StatusInternalServerError, "读取响应体时发生错误")
		return
	}

	// 构建 OpenAI API 风格的响应 JSON
	openAIResponse := map[string]interface{}{
		"id":      "chatcmpl-" + strings.ReplaceAll(uuid.New().String(), "-", ""),
		"object":  "chat.completion",
		"created": getUnixTime(),
		"model":   model,
		"choices": []interface{}{
			map[string]interface{}{
				"index":         0,
				"message":       map[string]interface{}{"role": "assistant", "content": contentBuilder.String()},
				"refusal":       nil, // 添加 'refusal' 字段
				"logprobs":      nil, // 添加 'logprobs' 字段
				"finish_reason": "stop",
			},
		},
		"usage": map[string]interface{}{
			"prompt_tokens":     16, // 示例值，可以根据实际计算
			"completion_tokens": completionTokens,
			"total_tokens":      16 + completionTokens,
			"prompt_tokens_details": map[string]interface{}{
				"cached_tokens": 0,
				"audio_tokens":  0,
			},
			"completion_tokens_details": map[string]interface{}{
				"reasoning_tokens":           0,
				"audio_tokens":               0,
				"accepted_prediction_tokens": 0,
				"rejected_prediction_tokens": 0,
			},
		},
		"system_fingerprint": "fp_" + strings.ReplaceAll(uuid.New().String(), "-", "")[:12],
	}

	// 构建 JSON 响应体
	responseBody, err := json.Marshal(openAIResponse)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "构建响应时发生错误")
		return
	}

	// 发送响应
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, err = w.Write(responseBody)
	if err != nil {
		log.Println("写入响应失败:", err)
		return
	}

	// 日志记录接收到的内容
	log.Printf("从 API 接收到的内容: %s\n", contentBuilder.String())
}

// shouldFilterOut 根据自定义逻辑过滤消息
func shouldFilterOut(sseJson map[string]interface{}) bool {
	// 过滤包含 "ping" 字段的消息
	if _, exists := sseJson["ping"]; exists {
		return true
	}

	// 检查 "data" 字段
	if data, exists := sseJson["data"].(map[string]interface{}); exists {
		// 过滤包含 "analytics" 的消息
		if _, exists := data["analytics"]; exists {
			return true
		}
		// 过滤同时包含 "operation" 和 "message" 的消息
		if _, opExists := data["operation"]; opExists {
			if _, msgExists := data["message"]; msgExists {
				return true
			}
		}
	}

	return false
}

// generateId 生成长度为24的随机字符串
func generateId() string {
	return strings.ReplaceAll(uuid.New().String(), "-", "")[:24]
}

// handleStreamResponse 处理流式响应并添加新功能
func handleStreamResponse(w http.ResponseWriter, resp *http.Response) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		sendError(w, http.StatusInternalServerError, "Streaming unsupported")
		return
	}

	// 设置响应头
	w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.WriteHeader(http.StatusOK)
	flusher.Flush()

	// 用于累计图片 Markdown 的内容和最终拼接的完整内容
	var imageMarkdownBuffer, finalContent strings.Builder
	inImageMode := false

	scanner := bufio.NewScanner(resp.Body)
	for scanner.Scan() {
		line := scanner.Text()
		if !strings.HasPrefix(line, "data: ") {
			continue
		}
		data := strings.TrimSpace(line[6:])
		if data == "" {
			continue
		}

		// 如果收到 [DONE] 信号，则判断是否处于图片模式
		if data == "[DONE]" {
			// 输出调试日志
			log.Printf("最终内容: %s\n", finalContent.String())
			// 检查是否包含图片 Markdown 标记
			if strings.Contains(finalContent.String(), "![Image](https://spc.unk/") {
				log.Printf("图片模式: %s\n", imageMarkdownBuffer.String())
				// 提取 Markdown 中的图片路径
				extractedPath, err := extractPathFromMarkdown(finalContent.String())
				if err != nil || extractedPath == "" {
					log.Println(" - 无法从 Markdown 中提取路径。")
					// 直接转发 [DONE]
					_, _ = w.Write([]byte("data: " + data + "\n\n"))
					flusher.Flush()
					break
				}
				// 过滤掉 "https://spc.unk/" 前缀
				extractedPath = strings.Replace(extractedPath, "https://spc.unk/", "", 1)
				log.Printf(" - 提取的路径: %s\n", extractedPath)
				// 拼接最终的存储 URL
				storageUrl := fmt.Sprintf("https://api.chaton.ai/storage/%s", extractedPath)
				log.Printf(" - 存储URL: %s\n", storageUrl)
				// 请求 storageUrl 获取最终下载链接
				finalDownloadUrl, err := fetchGetUrlFromStorage(storageUrl)
				if err != nil || finalDownloadUrl == "" {
					sendError(w, http.StatusInternalServerError, "无法从 storage URL 获取最终下载链接。")
					return
				}
				// 构造新的 SSE 消息，将图片 Markdown 替换为最终下载链接
				newSseJson := map[string]interface{}{
					"id":      generateId(),
					"object":  "chat.completion.chunk",
					"created": getUnixTime(),
					"model":   "gpt-4o", // 或根据实际情况从 JSON 中提取
					"choices": []interface{}{
						map[string]interface{}{
							"index": 0,
							"delta": map[string]interface{}{
								"content": fmt.Sprintf("\n\n![Image](%s)\n", finalDownloadUrl),
							},
							"finish_reason": nil,
						},
					},
					"system_fingerprint": "fp_" + strings.ReplaceAll(uuid.New().String(), "-", "")[:12],
				}
				newSseLine, err := json.Marshal(newSseJson)
				if err != nil {
					log.Println("JSON编码错误:", err)
					_, _ = w.Write([]byte("data: " + data + "\n\n"))
					flusher.Flush()
					break
				}
				_, err = w.Write([]byte("data: " + string(newSseLine) + "\n\n"))
				if err != nil {
					return
				}
				flusher.Flush()
				// 最后发送 [DONE]
				_, _ = w.Write([]byte("data: [DONE]\n\n"))
				flusher.Flush()
				break
			} else {
				// 非图片模式直接转发 [DONE]
				_, _ = w.Write([]byte("data: " + data + "\n\n"))
				flusher.Flush()
				break
			}
		} else {
			// 非 [DONE] 的消息，解析 JSON
			var jsonObj map[string]interface{}
			if err := json.Unmarshal([]byte(data), &jsonObj); err != nil {
				log.Println("JSON解析错误:", err)
				continue
			}
			// 过滤掉不需要转发的消息
			if shouldFilterOut(jsonObj) {
				continue
			}

			// 处理包含 web sources 的消息（与原逻辑类似）
			if dataField, exists := jsonObj["data"]; exists {
				if dataMap, ok := dataField.(map[string]interface{}); ok {
					if webField, exists := dataMap["web"]; exists {
						if webMap, ok := webField.(map[string]interface{}); ok {
							if sources, exists := webMap["sources"].([]interface{}); exists {
								var contentBuilder strings.Builder

								for _, source := range sources {
									if sourceMap, ok := source.(map[string]interface{}); ok {
										title, hasTitle := sourceMap["title"].(string)
										url, hasUrl := sourceMap["url"].(string)

										if hasTitle && hasUrl {
											contentBuilder.WriteString("\n### ")
											contentBuilder.WriteString(title)
											contentBuilder.WriteString("*\n*")
											contentBuilder.WriteString(url)
											contentBuilder.WriteString("*\n")
										}
									}
								}

								content := contentBuilder.String()
								if content != "" {
									log.Println("从 API 接收到的内容:", content)
									// 构造新的 SSE 消息
									model, _ := getString(jsonObj, "model", "gpt-4o")
									newSseJson := map[string]interface{}{
										"id":      generateId(),
										"object":  "chat.completion.chunk",
										"created": getUnixTime(),
										"model":   model,
										"choices": []interface{}{
											map[string]interface{}{
												"index": 0,
												"delta": map[string]interface{}{
													"content": content,
												},
												"finish_reason": nil,
											},
										},
										"system_fingerprint": "fp_" + strings.ReplaceAll(uuid.New().String(), "-", "")[:12],
									}

									newSseLine, err := json.Marshal(newSseJson)
									if err != nil {
										log.Println("JSON编码错误:", err)
										continue
									}
									_, err = w.Write([]byte("data: " + string(newSseLine) + "\n\n"))
									if err != nil {
										return
									}
									flusher.Flush()
									continue
								}
							}
						}
					}
				}
			}

			if choices, exists := jsonObj["choices"].([]interface{}); exists {
				for _, choice := range choices {
					choiceMap, ok := choice.(map[string]interface{})
					if !ok {
						continue
					}
					if delta, exists := choiceMap["delta"].(map[string]interface{}); exists {
						if content, exists := delta["content"].(string); exists {
							finalContent.WriteString(content)
							// 如果内容中包含图片 Markdown 标记，则进入图片模式
							if !inImageMode && (strings.Contains(content, "\n\n![") || strings.Contains(content, "spc.unk")) {
								inImageMode = true
								imageMarkdownBuffer.WriteString(content)
								log.Printf("进入图片模式，累计内容: %s\n", imageMarkdownBuffer.String())
								// 跳过当前这条消息，不直接转发
								break
							}
							// 如果已经在图片模式中，则累计图片内容，不直接转发
							if inImageMode {
								imageMarkdownBuffer.WriteString(content)
								continue
							}

							// 非图片消息直接转发原始消息
							if !inImageMode {
								_, err := w.Write([]byte("data: " + data + "\n\n"))
								if err != nil {
									return
								}
								flusher.Flush()
							}

						}
					}
				}
			} else {
				// 如果没有 choices 字段，直接转发该行
				_, err := w.Write([]byte("data: " + data + "\n\n"))
				if err != nil {
					return
				}
				flusher.Flush()
			}
		}
	}

	if err := scanner.Err(); err != nil {
		log.Println("读取响应体错误:", err)
	}
}

func fetchURL(url string) string {
	base64URL := "/urls/" + base64.StdEncoding.EncodeToString([]byte(url))
	fetchURL := "https://api.chaton.ai" + base64URL
	formattedDate := time.Now().UTC().Format("2006-01-02T15:04:05Z")

	// Generate bearer token for URL fetching
	bearerToken, err := bearerGenerator.GetBearerNew("", base64URL, formattedDate, "GET")
	if err != nil {
		log.Printf("Error generating bearer token: %v", err)
		return ""
	}

	// Build HTTP request
	req, err := http.NewRequest("GET", fetchURL, nil)
	if err != nil {
		log.Printf("Error creating request: %v", err)
		return ""
	}

	req.Header.Set("Authorization", bearerToken)
	req.Header.Set("Date", formattedDate)
	req.Header.Set("Client-time-zone", "-04:00")
	req.Header.Set("User-Agent", os.Getenv("USER_AGENT"))
	req.Header.Set("Accept-language", "en-US")
	req.Header.Set("X-Cl-Options", "hb")
	req.Header.Set("Accept-Encoding", "gzip")

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		log.Printf("Error fetching URL content: %v", err)
		return ""
	}
	defer resp.Body.Close()

	// Handle gzipped response
	var reader io.ReadCloser = resp.Body
	if resp.Header.Get("Content-Encoding") == "gzip" {
		reader, err = gzip.NewReader(resp.Body)
		if err != nil {
			log.Printf("Error creating gzip reader: %v", err)
			return ""
		}
		defer reader.Close()
	}

	// Read and process the response
	content := new(strings.Builder)
	scanner := bufio.NewScanner(reader)
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "data: ") {
			if line == "data: [DONE]" {
				log.Println("Data Done")
				continue
			}

			data := strings.TrimSpace(line[6:])
			var responseJson map[string]interface{}
			if err := json.Unmarshal([]byte(data), &responseJson); err != nil {
				continue
			}

			if dataJson, ok := responseJson["data"].(map[string]interface{}); ok {
				if contentDelta, exists := dataJson["content_delta"].(string); exists {
					content.WriteString(contentDelta)
					log.Printf("Content Delta: %s", contentDelta)
				}
			}
		}
	}

	if err := scanner.Err(); err != nil {
		log.Printf("Error scanning response: %v", err)
	}

	return content.String()
}

func getInt(m map[string]interface{}, key string, defaultVal int) (int, bool) {
	if val, exists := m[key]; exists {
		switch v := val.(type) {
		case float64:
			return int(v), true
		case int:
			return v, true
		}
	}
	return defaultVal, false
}

func getString(m map[string]interface{}, key string, defaultVal string) (string, bool) {
	if val, exists := m[key]; exists {
		if s, ok := val.(string); ok {
			return s, true
		}
	}
	return defaultVal, false
}

func getBool(m map[string]interface{}, key string, defaultVal bool) (bool, bool) {
	if val, exists := m[key]; exists {
		if b, ok := val.(bool); ok {
			return b, true
		}
	}
	return defaultVal, false
}

// Function to extract image path from Markdown
func extractPathFromMarkdown(markdown string) (string, error) {
	// Regular expression to match ![Image](URL)
	re := regexp.MustCompile(`!\[.*?]\((.*?)\)`)
	matches := re.FindStringSubmatch(markdown)
	if len(matches) < 2 {
		return "", errors.New("无法匹配Markdown中的图片路径")
	}
	return matches[1], nil
}

// Function to fetch the final download URL from storage
func fetchGetUrlFromStorage(storageUrl string) (string, error) {
	client := &http.Client{
		Timeout: 10 * time.Second,
	}
	req, err := http.NewRequest("GET", storageUrl, nil)
	if err != nil {
		return "", err
	}

	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer func(Body io.ReadCloser) {
		err := Body.Close()
		if err != nil {

		}
	}(resp.Body)

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("获取 storage URL 失败，状态码: %d", resp.StatusCode)
	}

	bodyBytes, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}

	var jsonResponse map[string]interface{}
	if err := json.Unmarshal(bodyBytes, &jsonResponse); err != nil {
		return "", err
	}

	if getUrl, exists := jsonResponse["getUrl"].(string); exists {
		return getUrl, nil
	}

	return "", errors.New("JSON 响应中缺少 'getUrl' 字段")
}

// Function to download image from URL
func downloadImage(imageUrl string) ([]byte, error) {
	client := &http.Client{
		Timeout: 30 * time.Second,
	}
	req, err := http.NewRequest("GET", imageUrl, nil)
	if err != nil {
		return nil, err
	}

	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("下载图像失败，状态码: %d", resp.StatusCode)
	}

	imageBytes, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	return imageBytes, nil
}

// uploadImage 上传 Base64 解码后的图片数据到 storage 服务，返回最终可访问的图片 URL
func uploadImage(imageBytes []byte, extension string) (string, error) {
	// 生成文件名：使用当前时间戳（毫秒）作为文件名，例如 "1740205782603.jpg"
	filename := fmt.Sprintf("%d.%s", time.Now().UnixMilli(), extension)

	// 生成随机 boundary（不带前导 "--"）
	boundary := uuid.New().String()

	// 格式化当前日期，格式类似 "2025-02-22T06:29:51Z"
	formattedDate := time.Now().UTC().Format("2006-01-02T15:04:05Z")

	// 生成上传图片的 Bearer Token，传入空字节数组、上传路径 "/storage/upload" 以及 HTTP 方法 "POST"
	uploadBearerToken, err := bearerGenerator.GetBearerNew("", "/storage/upload", formattedDate, "POST")
	if err != nil {
		return "", fmt.Errorf("生成上传Bearer Token失败: %v", err)
	}

	// 如果扩展名为 "jpg"，则 Content-Type 需要设置为 "image/jpeg"，否则使用扩展名
	contentType := extension
	if extension == "jpg" {
		contentType = "jpeg"
	}

	// 构造 multipart/form-data 请求体（手动构造，模拟 Java 的逻辑）
	var baos bytes.Buffer
	lineBreak := "\r\n"
	twoHyphens := "--"
	// 构造文件部分头部
	filePartHeader := twoHyphens + boundary + lineBreak +
		`Content-Disposition: form-data; name="file"; filename="` + filename + `"` + lineBreak +
		"Content-Type: image/" + contentType + lineBreak + lineBreak
	baos.WriteString(filePartHeader)
	baos.Write(imageBytes)
	baos.WriteString(lineBreak)
	// 结束 boundary
	endBoundary := twoHyphens + boundary + twoHyphens + lineBreak
	baos.WriteString(endBoundary)
	multipartBody := baos.Bytes()

	// 构建 HTTP 请求
	req, err := http.NewRequest("POST", "https://api.chaton.ai/storage/upload", bytes.NewReader(multipartBody))
	if err != nil {
		return "", err
	}
	req.Header.Set("Date", formattedDate)
	req.Header.Set("Client-time-zone", "-04:00")
	req.Header.Set("Authorization", uploadBearerToken)
	req.Header.Set("User-Agent", os.Getenv("USER_AGENT"))
	req.Header.Set("Accept-language", "en-US")
	req.Header.Set("X-Cl-Options", "hb")
	req.Header.Set("Content-Type", "multipart/form-data; boundary="+boundary)
	req.Header.Set("Accept-Encoding", "gzip")

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("图片上传失败，状态码: %d", resp.StatusCode)
	}

	// 处理可能的 gzip 压缩响应
	var responseBodyBytes []byte
	if strings.EqualFold(resp.Header.Get("Content-Encoding"), "gzip") {
		gzipReader, err := gzip.NewReader(resp.Body)
		if err != nil {
			return "", err
		}
		defer func(gzipReader *gzip.Reader) {
			err := gzipReader.Close()
			if err != nil {

			}
		}(gzipReader)
		responseBodyBytes, err = ioutil.ReadAll(gzipReader)
		if err != nil {
			return "", err
		}
	} else {
		responseBodyBytes, err = ioutil.ReadAll(resp.Body)
		if err != nil {
			return "", err
		}
	}

	// 解析返回的 JSON，提取 getUrl 字段
	var jsonResponse map[string]interface{}
	if err := json.Unmarshal(responseBodyBytes, &jsonResponse); err != nil {
		return "", err
	}
	getUrl, ok := jsonResponse["getUrl"].(string)
	if !ok || getUrl == "" {
		return "", errors.New("上传响应中缺少 'getUrl' 字段")
	}

	return getUrl, nil
}

// Helper function to build assistant content with images
func buildAssistantContent(content string, imageUrls []string) string {
	var sb strings.Builder
	sb.WriteString(content)
	for _, url := range imageUrls {
		sb.WriteString(fmt.Sprintf("\n[Image: %s]", url))
	}
	return sb.String()
}

// Helper function to get current Unix time
func getUnixTime() int64 {
	return time.Now().Unix()
}

// Function to create HTTP server with port fallback
func createHTTPServer(initialPort int) (*http.Server, int, error) {
	var srv *http.Server
	var finalPort = initialPort

	for finalPort <= 65535 {
		addr := fmt.Sprintf("0.0.0.0:%d", finalPort)
		listener, err := net.Listen("tcp", addr)
		if err != nil {
			if strings.Contains(err.Error(), "address already in use") {
				log.Printf("端口 %d 已被占用，尝试端口 %d\n", finalPort, finalPort+1)
				finalPort++
				continue
			} else {
				return nil, 0, err
			}
		}
		mux := http.NewServeMux()
		mux.HandleFunc("/v1/chat/completions", completionHandler)
		mux.HandleFunc("/v1/images/generations", textToImageHandler)
		mux.HandleFunc("/v1/models", func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusOK)
			_, err := w.Write([]byte(`{"object":"list","data":[{"id":"gpt-4o","object":"model"},{"id":"gpt-4o-mini","object":"model"},{"id":"claude","object":"model"},{"id":"claude-3-haiku","object":"model"}]}`))
			if err != nil {
				return
			}
		})

		srv = &http.Server{
			Handler: mux,
		}

		log.Printf("服务器已启动，监听端口 %d\n", finalPort)
		go func() {
			if err := srv.Serve(listener); err != nil && err != http.ErrServerClosed {
				log.Fatalf("服务器启动失败: %v\n", err)
			}
		}()
		return srv, finalPort, nil
	}

	return nil, 0, fmt.Errorf("所有端口从 %d 到 65535 都被占用，无法启动服务器", initialPort)
}

func main() {
	//判断是否使用代理，并打印信息
	if http.ProxyFromEnvironment == nil {
		log.Println("未使用代理")
	} else {

	}
	// 解析命令行参数
	if len(os.Args) > 1 {
		p, err := strconv.Atoi(os.Args[1])
		if err == nil && p > 0 && p <= 65535 {
			initialPort = p
		} else {
			log.Printf("无效的端口号: %s，使用默认端口 %d\n", os.Args[1], initialPort)
		}
	}
	// 创建 HTTP 服务器
	var srv *http.Server
	port := initialPort
	for {
		var err error
		srv, _, err = createHTTPServer(port)
		if err == nil {
			break // 端口绑定成功，退出循环
		}
		log.Printf("端口 %d 被占用，尝试下一个端口...\n", port)
		port++
		if port > 65535 {
			log.Printf("端口超过 65535，重置为 1024 继续尝试...\n")
			port = 1024
		}
	}
	// 优雅关闭服务器的信号处理
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, os.Interrupt, syscall.SIGTERM)
	<-quit
	log.Println("正在关闭服务器...")

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Fatalf("服务器关闭失败: %v\n", err)
	}

	log.Println("服务器已成功关闭")
}
