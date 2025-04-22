这是一个OpenAI 类服务端程序

由👇分析而来

  <a href="https://play.google.com/store/apps/details?id=ai.chat.gpt.bot">ChatOn</a>


本项目是一个类 OpenAI 服务端程序，向API发送请求，然后模拟OpenAI API标准的响应

可与多种前端应用（如 NextChat、ChatBox 等）无缝集成

## Docker 部署

    docker pull patchescamera/chaton2api:latest
    docker run -d -p 8080:80 patchescamera/chaton2api:latest
    ## 可设置环境变量作为代理，例如
    docker run -d -p 8080:80 -e http_proxy=127.0.0.1:7890 patchescamera/chaton2api

#### 支持的模型

gpt-4o✅

gpt-4o-mini✅

claude-3-5-sonnet✅

claude Haiku✅

deekseek-r1✅

sonar-reasoning-pro✅（这个是网络搜索的，并非推理模型）

支持的功能

Completions: （可联网搜索）

	/v1/chat/completions


TextToImage:（仅限 gpt-4o 和 gpt-4o-mini 模型可画图）

	/v1/images/generations

ImageToText：可传直链，如果传base64编码的图片服务需要部署在公网

Usage:

	--port # 指定的端口
 
测试示例

 	curl --request POST 'http://127.0.0.1:8080/v1/chat/completions' \
 	--header 'Content-Type: application/json' \
 	--data '{"top_p":1,"stream":false,"temperature":0,"messages":[{"role":"user","content":"hello"}],"model":"gpt-4o"}'
  
画图示例

	curl --request POST 'http://127.0.0.1:8080/v1/images/generations' \
	--header 'Content-Type: application/json' \
	--data '{"prompt":"girl","response_format":"b64_json","model":"gpt-4o","style":"vivid"}'
 
或

 	curl --request POST 'http://127.0.0.1:8080/v1/images/generations' \
	--header 'Content-Type: application/json' \
	--data '{"prompt": "girl", "model": "gpt-4o", "n": 1, "size": "1024x1024"}'

# 关于环境变量

去下载apk，然后抓包、Authorization的格式为"Bearer <>.<>"。把一段用Base64解码后，用16进制编辑器（HeX/WinHex）查就知道了。

User-Agent自己抓包就能看出来了

~~要查哪个文件就不要问了~~

~~其实某些工具可以直接查目录下包含所需内容的文件~~
