这是一个OpenAI 类服务端程序

由👇分析而来

  <a href="https://play.google.com/store/apps/details?id=ai.chat.gpt.bot">ChatOn</a>


本项目是一个类 OpenAI 服务端程序，向API发送请求，然后模拟OpenAI API标准的响应

可与多种前端应用（如 NextChat、ChatBox 等）无缝集成


#### 支持的模型

gpt-4o✅

gpt-4o-mini✅

claude-3-5-sonnet✅

claude Haiku✅

deekseek-r1✅

支持的功能

Completions: （可联网搜索）

	/v1/chat/completions


TextToImage:（仅限 gpt-4o 和 gpt-4o-mini 模型可画图）

	/v1/images/generations

ImageToText：可传直链，如果传base64编码的图片服务需要部署在公网

Usage:

	--port # 指定的端口，默认80
 	--base_url
 
测试示例

 	curl --request POST 'http://127.0.0.1:8080/v1/chat/completions' \
 	--header 'Content-Type: application/json' \
 	--header "Authorization: Bearer 123" \
 	--data '{"top_p":1,"stream":false,"temperature":0,"messages":[{"role":"user","content":"hello"}],"model":"gpt-4o"}'
  
画图示例

	curl --request POST 'http://127.0.0.1:8080/v1/images/generations' \
	--header 'Content-Type: application/json' \
	--header "Authorization: Bearer 123" \
	--data '{"prompt":"girl","response_format":"b64_json","model":"gpt-4o","style":"vivid"}'
 
或

 	curl --request POST 'http://127.0.0.1:8080/v1/images/generations' \
	--header 'Content-Type: application/json' \
	--header "Authorization: Bearer 123" \
	--data '{"prompt": "girl", "model": "gpt-4o", "n": 1, "size": "1024x1024"}'
