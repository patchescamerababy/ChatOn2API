这是一个OpenAI API 兼容服务端程序

来源

  <a href="https://play.google.com/store/apps/details?id=ai.chat.gpt.bot">Google play</a> <a href="https://apps.apple.com/us/app/chaton-ai-chat-bot-assistant/id1661308505">
App Store</a>


本项目是一个 OpenAI API 兼容的服务端程序

可与NextChat、ChatBox 等前端应用兼容

#### 支持的模型id

gpt-4o✅

gpt-4o-mini✅

claude-3-5-sonnet✅

claude-3-7-sonnet✅

claude✅(claude 3 haiku)

deekseek-r1✅

sonar-reasoning-pro✅

支持的功能

Completions: 

	/v1/chat/completions

TextToImage:

	/v1/images/generations
 
 TextToSpeech:
 
	/v1/audio/speech
 
不支持function calling，支持网络搜索、dall-e画图、python运行代码，分别传入function_web_search、function_image_gen、function_code_interpreter为true即可

TTS可用的模型：**tts-1-hd**、**tts-1**，可用的voice：alloy、echo、fable、onyx、nova（默认）、shimmer
 
测试示例

 	curl -X POST 'http://127.0.0.1:8080/v1/chat/completions' \
 	--header 'Content-Type: application/json' \
 	--data '{"stream":false,"messages":[{"role":"user","content":"hello"}],"model":"gpt-4o"}'
  

传图（base64图片自动上传至图床，或直接传直连，支持多张图片同时上传）：

	curl -X POST http://127.0.0.1:8080/v1/chat/completions \
	 --header 'Content-Type: application/json' \
	 --data '{"messages":[{"role":"user","content":[{"type":"text","text":"What is this"},{"type":"image_url","image_url":{"url":"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAYAAADED76LAAAABGdBTUEAALGPC/xhBQAAAEBJREFUGNNjYACCBAWF/yCMzmaACVy4cOG/g4MDWAJEw9hwBTBBZAxXECwtjVUBSBxuDboiFEl0RVglkRUxkAoA6pU6bjl6zpsAAAAASUVORK5CYII="}}]}],"model":"gpt-4o","stream":false}'



/v1/images/generations画图示例

	curl -X POST 'http://127.0.0.1:8080/v1/images/generations' \
	--header 'Content-Type: application/json' \
	--data '{"prompt":"girl","response_format":"b64_json","model":"gpt-4o","style":"vivid"}'
 
或

 	curl -X POST 'http://127.0.0.1:8080/v1/images/generations' \
	--header 'Content-Type: application/json' \
	--data '{"prompt": "girl", "model": "gpt-4o", "n": 1, "size": "1024x1024"}'

 目前当用户的对话中含有URL时，将通过他们的爬虫获取页面信息并发送

 有并发限制，如果出现429，则更换代理

# 关于环境变量

去下载apk，然后抓包、Authorization的格式为"Bearer <>.<>"。把一段用Base64解码后，用16进制编辑器（HeX/WinHex）查就知道了。

User-Agent自己抓包就能看出来了

~~此方法是总结出来的~~
