这是一个OpenAI API 兼容服务端程序

由👇分析而来

  <a href="https://play.google.com/store/apps/details?id=ai.chat.gpt.bot">ChatOn</a>


本项目是一个 OpenAI API 兼容的服务端程序

可与NextChat、ChatBox 等前端应用兼容

## Docker 部署

    docker pull patchescamera/chaton2api:latest
    docker run -d -p 8080:80 patchescamera/chaton2api:latest
    ## 可设置环境变量作为代理，例如
    docker run -d -p 8080:80 -e http_proxy=127.0.0.1:7890 patchescamera/chaton2api

#### 支持的模型id

gpt-4o✅

gpt-4o-mini✅

claude-3.5-sonnet✅

claude✅(claude 3 haiku)

deekseek-r1✅

sonar-reasoning-pro✅（这个是网络搜索的，推理基于deepseek-r1）

支持的功能

Completions: 

	/v1/chat/completions

TextToImage:

	/v1/images/generations

不支持function calling，默认支持网络搜索、dall-e画图、python运行代码，stream为true时可在对话中画图
 
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

~~要查哪个文件就不要问了~~

~~其实某些工具可以直接查目录下包含所需内容的文件~~
