import asyncio
import json
import sys
import uuid
import base64
import re
import os
import argparse
from datetime import datetime, timezone, timedelta
from typing import List, Optional
import hmac  # Add this import
import hashlib  # Add this import
import httpx
import uvicorn

from fastapi import (
    BackgroundTasks,
    FastAPI,
    HTTPException,
    Request,
    status,
)
from fastapi.responses import HTMLResponse, JSONResponse, StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from bearer_token import BearerTokenGenerator


# 默认端口
INITIAL_PORT = 8080

# API的URL
EXTERNAL_API_URL = "https://api.chaton.ai/chats/stream"




app = FastAPI()
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["Content-Type", "Authorization"],
)

def send_error_response(message: str, status_code: int = 400):
    error_json = {"error": message}
    headers = {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
        "Access-Control-Allow-Headers": "Content-Type, Authorization",
    }
    return JSONResponse(status_code=status_code, content=error_json, headers=headers)

def extract_path_from_markdown(markdown: str) -> Optional[str]:
    pattern = re.compile(r'!\[.*?\]\(https://spc\.unk/(.*?)\)')
    match = pattern.search(markdown)
    if match:
        return match.group(1)
    return None

async def fetch_get_url_from_storage(storage_url: str) -> Optional[str]:
    async with httpx.AsyncClient() as client:
        try:
            response = await client.get(storage_url)
            if response.status_code != 200:
                # print(f"获取 storage URL 失败，状态码: {response.status_code}")
                return None
            json_response = response.json()
            return json_response.get("getUrl")
        except Exception as e:
            print(f"Error fetching getUrl from storage: {e}")
            return None

async def download_image(image_url: str) -> Optional[bytes]:
    async with httpx.AsyncClient() as client:
        try:
            response = await client.get(image_url)
            if response.status_code == 200:
                return response.content
            else:
                # print(f"下载图像失败，状态码: {response.status_code}")
                return None
        except Exception as e:
            print(f"Error downloading image: {e}")
            return None

def is_base64_image(url: str) -> bool:
    return url.startswith("data:image/")

# 上传 Base64 图片函数，调用 BearerTokenGenerator 时传入 source="/storage/upload"
async def upload_base64_image(base64_str: str) -> Optional[str]:
    try:
        image_data = base64.b64decode(base64_str)
    except Exception as e:
        # print(f"Base64解码失败: {e}")
        return None

    # 生成随机 boundary 和当前时间戳作为文件名
    boundary = str(uuid.uuid4())
    filename = f"{int(datetime.utcnow().timestamp() * 1000)}.jpg"
    multipart_body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: image/jpeg\r\n\r\n"
    ).encode('utf-8') + image_data + f"\r\n--{boundary}--\r\n".encode('utf-8')

    # 生成上传图片专用的 Bearer Token（source 为 /storage/upload）
    formatted_date=BearerTokenGenerator.get_formatted_date()
    tmp_token = BearerTokenGenerator.get_bearer("", source="/storage/upload",method="POST",formatted_date=formatted_date)
    if not tmp_token:
        # print("无法生成上传图片的 Bearer Token")
        return None
    #bearer_token, formatted_date = tmp_token

    headers = {
        "Date": formatted_date,
        "Client-time-zone": "-04:00",
        "Authorization": tmp_token,
        "User-Agent": BearerTokenGenerator.get_user_agent(),
        "Accept-language": "en-US",
        "X-Cl-Options": "hb",
        "Content-Type": f"multipart/form-data; boundary={boundary}",
    }

    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(10.0)) as client:
            response = await client.post("https://api.chaton.ai/storage/upload", headers=headers, content=multipart_body)
            response.raise_for_status()
            json_response = response.json()
            get_url = json_response.get("getUrl")
            # print(f"Uploaded image URL: {get_url}")
            return get_url
    except Exception as e:
        print(f"上传图片失败: {e}")
        return None

# 辅助函数：非流式响应，按照 SSE 流中接收顺序整合文本和 web source 数据
async def handle_non_streaming_response(modified_request_body: str, headers: dict, model: str, cleaned_messages: list, has_image: bool):
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(10.0)) as client:
            response = await client.post(EXTERNAL_API_URL, headers=headers, content=modified_request_body)
            response.raise_for_status()
            
            sse_lines = response.text.splitlines()
            fragments = []  # 按接收顺序保存片段

            for line in sse_lines:
                if line.startswith("data: "):
                    data = line[6:].strip()
                    if data == "[DONE]":
                        break
                    try:
                        sse_json = json.loads(data)
                        if "data" in sse_json and "web" in sse_json["data"]:
                            web_data = sse_json["data"]["web"]
                            if "sources" in web_data:
                                sources = web_data["sources"]
                                urls_list = []
                                for source in sources:
                                    if "url" in source:
                                        urls_list.append(source["url"])
                                if urls_list:
                                    fragments.append("\n\n".join(urls_list))
                        if "choices" in sse_json:
                            for choice in sse_json["choices"]:
                                delta = choice.get("delta", {})
                                content = delta.get("content")
                                if content:
                                    fragments.append(content)
                    except json.JSONDecodeError:
                        print("JSON解析错误")
                        continue

            content_builder = "".join(fragments)
            openai_response = {
                "id": f"chatcmpl-{uuid.uuid4()}",
                "object": "chat.completion",
                "created": int(datetime.now(timezone.utc).timestamp()),
                "model": model,
                "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": content_builder},
                    "finish_reason": "stop",
                }],
            }
            if has_image:
                images = []
                for message in cleaned_messages:
                    if "images" in message:
                        for img in message["images"]:
                            images.append({"data": img["data"]})
                openai_response["choices"][0]["message"]["images"] = images
            return openai_response

    except httpx.RequestError as e:
        # print(f"Request Error: {e}")
        raise HTTPException(status_code=500, detail="请求失败: 无法连接外部API。")
    except httpx.HTTPStatusError as e:
        # print(f"HTTP Error: {e}")
        raise HTTPException(status_code=e.response.status_code, detail=f"外部API错误: {e.response.text}")
    except Exception as e:
        # print(f"General Error: {e}")
        raise HTTPException(status_code=500, detail=f"内部服务器错误: {str(e)}")

@app.get("/", response_class=HTMLResponse)
async def read_root():
    html_content = """
    <html>
        <head><title>Welcome to API</title></head>
        <body>
            <h1>Welcome to API</h1>
            <p>You can send messages to the model and receive responses.</p>
        </body>
    </html>
    """
    return HTMLResponse(content=html_content, status_code=200)

@app.post("/v1/chat/completions")
async def chat_completions(request: Request, background_tasks: BackgroundTasks):
    try:
        request_body = await request.json()
    except json.JSONDecodeError:
        raise HTTPException(status_code=400, detail="Invalid JSON")

    # print("Received Completion JSON:", json.dumps(request_body, ensure_ascii=False))
    messages = request_body.get("messages", [])
    #temperature = request_body.get("temperature", 1.0)
    max_tokens = request_body.get("max_tokens", 8000)
    model = request_body.get("model", "gpt-4o")
    is_stream = request_body.get("stream", False)

    if model == "claude-3.5-sonnet":
        model = "claude-3-5-sonnet"
    if model == "GPT 4o":
        model = "gpt-4o"


    has_image = False
    has_text = False
    cleaned_messages = []
    for message in messages:
        content = message.get("content", "")
        if isinstance(content, list):
            text_parts = []
            images = []
            for item in content:
                if "text" in item:
                    text_parts.append(item.get("text", ""))
                elif "image_url" in item:
                    has_image = True
                    image_info = item.get("image_url", {})
                    url = image_info.get("url", "")
                    if is_base64_image(url):
                        try:
                            base64_str = url.split(",")[1]
                            # 上传 Base64 图片后获得最终 URL
                            uploaded_url = await upload_base64_image(base64_str)
                            if uploaded_url:
                                images.append({"data": uploaded_url})
                                print("\n可访问的链接: \n" + uploaded_url+"\n")
                            else:
                                print("上传Base64图片失败")
                        except Exception as e:
                            print(f"处理Base64图片失败: {e}")
                            continue
                    else:
                        images.append({"data": url})
            extracted_content = " ".join(text_parts).strip()
            if extracted_content:
                has_text = True
                message["content"] = extracted_content
                if images:
                    message["images"] = images
                cleaned_messages.append(message)
                # print("Extracted:", extracted_content)
            else:
                if images:
                    has_image = True
                    message["content"] = ""
                    message["images"] = images
                    cleaned_messages.append(message)
                    # print("Extracted image only.")
                else:
                    print("Deleted message with empty content.")
        elif isinstance(content, str):
            content_str = content.strip()
            if content_str:
                has_text = True
                message["content"] = content_str
                cleaned_messages.append(message)
                # print("Retained content:", content_str)
            else:
                print("Deleted message with empty content.")
        else:
            print("Deleted non-expected type of content message.")

    if not cleaned_messages:
        raise HTTPException(status_code=400, detail="所有消息的内容均为空。")

    new_request_json = {
        "function_image_gen": False,
        "function_web_search": True,
        "max_tokens": max_tokens,
        "model": model,
        "source": "chat/pro",
        "messages": cleaned_messages,
        "web_search_engine": "auto"
    }
    modified_request_body = json.dumps(new_request_json, ensure_ascii=False)
    # print("Modified Request JSON:", modified_request_body)
    # 调用 BearerTokenGenerator 时传入 source="/chats/stream"
    formatted_date=BearerTokenGenerator.get_formatted_date()
    tmp_token = BearerTokenGenerator.get_bearer(modified_request_body, source="/chats/stream",method="POST",formatted_date=formatted_date)
    if not tmp_token:
        raise HTTPException(status_code=500, detail="无法生成 Bearer Token")
    #bearer_token, formatted_date = tmp_token
    headers = {
        "Date": formatted_date,
        "Client-time-zone": "-04:00",
        "Authorization": tmp_token,
        "User-Agent": BearerTokenGenerator.get_user_agent(),
        "Accept-Language": "en-US",
        "X-Cl-Options": "hb",
        "Content-Type": "application/json; charset=UTF-8",
    }

    if is_stream:
        import uuid
        from datetime import datetime, timezone

        def should_filter_out(json_data):
            if 'ping' in json_data:
                return True
            if 'data' in json_data:
                data = json_data['data']
                if 'analytics' in data:
                    return True
                if 'operation' in data and 'message' in data:
                    return True
            return False

        def generate_id():
            return uuid.uuid4().hex[:24]

        async def event_generator():
            async with httpx.AsyncClient(timeout=None) as client_stream:
                try:
                    async with client_stream.stream("POST", EXTERNAL_API_URL, headers=headers, content=modified_request_body) as streamed_response:
                        async for line in streamed_response.aiter_lines():
                            if line.startswith("data: "):
                                data = line[6:].strip()
                                if data == "[DONE]":
                                    yield "data: [DONE]\n\n"
                                    break
                                try:
                                    sse_json = json.loads(data)
                                    if should_filter_out(sse_json):
                                        continue
                                    if 'data' in sse_json and 'web' in sse_json['data']:
                                        web_data = sse_json['data']['web']
                                        if 'sources' in web_data:
                                            sources = web_data['sources']
                                            urls_list = [None] * len(sources)    # 预分配列表大小
                                            titles_list = [None] * len(sources)  # 预分配列表大小
                                            
                                            # 收集数据
                                            for i, source in enumerate(sources):
                                                if 'url' in source:
                                                    urls_list[i] = source['url']
                                                if 'title' in source:
                                                    titles_list[i] = source['title']
                                            
                                            # 构建content
                                            content = ""
                                            for i in range(len(sources)):
                                                if titles_list[i] and urls_list[i]:
                                                    content += f"\n### {titles_list[i]}\n{urls_list[i]}\n"
                                            
                                            # 创建新的SSE JSON
                                            new_sse_json = {
                                                "id": generate_id(),
                                                "object": "chat.completion.chunk",
                                                "created": int(datetime.now(timezone.utc).timestamp()),
                                                "model": "gpt-4o",  # 直接使用固定值，与Java代码一致
                                                "choices": [{
                                                    "delta": {"content": content},
                                                    "index": 0,
                                                    "finish_reason": None
                                                }]
                                            }
                                            
                                            new_sse_line = f"data: {json.dumps(new_sse_json, ensure_ascii=False)}\n\n"
                                            yield new_sse_line

                                    else:
                                        if 'choices' in sse_json:
                                            for choice in sse_json['choices']:
                                                delta = choice.get('delta', {})
                                                content = delta.get('content')
                                                if content:
                                                    print(content, end='')
                                        yield f"data: {data}\n\n"
                                except json.JSONDecodeError as e:
                                    print(f"JSON解析错误: {e}")
                                    continue
                            else:
                                continue
                except httpx.RequestError as exc:
                    # print(f"外部API请求失败: {exc}")
                    yield f"data: {{\"error\": \"外部API请求失败: {str(exc)}\"}}\n\n"

        return StreamingResponse(
            event_generator(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "Connection": "keep-alive",
            },
        )
    else:
        openai_response = await handle_non_streaming_response(modified_request_body, headers, model, cleaned_messages, has_image)
        return JSONResponse(content=openai_response, status_code=200)

@app.post("/v1/images/generations")
async def images_generations(request: Request):
    try:
        request_body = await request.json()
    except json.JSONDecodeError:
        return send_error_response("Invalid JSON", status_code=400)

    # # print("Received Image Generations JSON:", json.dumps(request_body, ensure_ascii=False))
    if "prompt" not in request_body:
        return send_error_response("缺少必需的字段: prompt", status_code=400)

    user_prompt = request_body.get("prompt", "").strip()
    response_format = request_body.get("response_format", "").strip().lower()
    model = request_body.get("model", "gpt-4o")
    n = request_body.get("n", 1)
    size = request_body.get("size", "1024x1024")
    is_base64_response = response_format == "b64_json"

    if not user_prompt:
        return send_error_response("Prompt 不能为空。", status_code=400)

    # print(f"Prompt: {user_prompt}")
    # print(f"Response Format: {response_format}")
    # print(f"Number of images to generate (n): {n}")
    # print(f"Size: {size}")
    
    max_attempts = 2 * n
    # print(f"Max Attempts: {max_attempts}")
    final_download_urls: List[str] = []

    async def attempt_generate_image(attempt: int) -> Optional[str]:
        try:
            text_to_image_json = {
                "function_image_gen": True,
                "function_web_search": True,
                #"image_style": "photographic",
                "max_tokens": 8000,
                "messages": [
                    {
                        "content": "Your role is that of a smart and creative assistant. Do not mention that you are a chatbot or AI assistant. Consider the terms when communicating: 1. The length of your response: Auto. 2. The tone style of your speech: Default. This dialog box has an option to generate images. The function should be called only when the user explicitly requests it - for example, using any related words associated with image generation requests. In other cases - the call of the image generation function should not be called.",
                        "role": "system"
                    },{
                        "content": "Draw: " + user_prompt,
                        "role": "user"
                    }
                ],
                "model": "gpt-4o",
                "source": "chat/free"
            }
            modified_request_body = json.dumps(text_to_image_json, ensure_ascii=False)
            # print(f"Attempt {attempt} - Modified Request JSON: {modified_request_body}")
            formatted_date=BearerTokenGenerator.get_formatted_date()
            tmp_token = BearerTokenGenerator.get_bearer(modified_request_body, source="/chats/stream",method="POST",formatted_date=formatted_date)
            if not tmp_token:
                # print(f"Attempt {attempt} - 无法生成 Bearer Token")
                return None
            #bearer_token, formatted_date = tmp_token
            headers = {
                "Date": formatted_date,
                "Client-time-zone": "-04:00",
                "Authorization": tmp_token,
                "User-Agent": BearerTokenGenerator.get_user_agent(),
                "Accept-Language": "en-US",
                "X-Cl-Options": "hb",
                "Content-Type": "application/json; charset=UTF-8",
            }
            async with httpx.AsyncClient(timeout=httpx.Timeout(10.0)) as client:
                response = await client.post(EXTERNAL_API_URL, headers=headers, content=modified_request_body)
                response.raise_for_status()
                sse_lines = response.text.splitlines()
                image_markdown = ""
                for line in sse_lines:
                    if line.startswith("data: "):
                        data = line[6:].strip()
                        if data == "[DONE]":
                            break
                        try:
                            sse_json = json.loads(data)
                            if "choices" in sse_json:
                                for choice in sse_json["choices"]:
                                    delta = choice.get("delta", {})
                                    content = delta.get("content")
                                    if content:
                                        image_markdown += content
                        except json.JSONDecodeError:
                            print(f"Attempt {attempt} - JSON解析错误")
                            continue
                if not image_markdown:
                    # print(f"Attempt {attempt} - 无法从 SSE 流中构建图像 Markdown。")
                    return None
                extracted_path = extract_path_from_markdown(image_markdown)
                if not extracted_path:
                    # print(f"Attempt {attempt} - 无法从 Markdown 中提取路径。")
                    return None
                # print(f"Attempt {attempt} - 提取的路径: {extracted_path}")
                storage_url = f"https://api.chaton.ai/storage/{extracted_path}"
                # print(f"Attempt {attempt} - 存储URL: {storage_url}")
                final_download_url = await fetch_get_url_from_storage(storage_url)
                if not final_download_url:
                    # print(f"Attempt {attempt} - 无法从 storage URL 获取最终下载链接。")
                    return None
                # print(f"Attempt {attempt} - Final Download URL: {final_download_url}")
                return final_download_url
        except Exception as e:
            print(f"Attempt {attempt} - 处理响应时发生错误: {e}")
            return None

    semaphore = asyncio.Semaphore(10)
    async def generate_with_retries(attempt: int) -> Optional[str]:
        async with semaphore:
            return await attempt_generate_image(attempt)

    for attempt in range(1, max_attempts + 1):
        needed = n - len(final_download_urls)
        if needed <= 0:
            break
        # print(f"Attempt {attempt} - 需要生成的图像数量: {needed}")
        tasks = [asyncio.create_task(generate_with_retries(attempt)) for _ in range(needed)]
        results = await asyncio.gather(*tasks, return_exceptions=True)
        for result in results:
            if isinstance(result, Exception):
                # print(f"Attempt {attempt} - 任务发生异常: {result}")
                continue
            if result:
                final_download_urls.append(result)
                # print(f"Attempt {attempt} - 收集到下载链接: {result}")
        if len(final_download_urls) >= n:
            break

    if len(final_download_urls) < n:
        # print("已达到最大尝试次数，仍未收集到足够数量的下载链接。")
        return send_error_response("无法生成足够数量的图像。", status_code=500)

    data_array = []
    if is_base64_response:
        for download_url in final_download_urls[:n]:
            try:
                image_bytes = await download_image(download_url)
                if not image_bytes:
                    # print(f"无法从 URL 下载图像: {download_url}")
                    continue
                image_base64 = base64.b64encode(image_bytes).decode('utf-8')
                data_array.append({"b64_json": image_base64})
            except Exception as e:
                print(f"处理图像时发生错误: {e}")
                continue
    else:
        for download_url in final_download_urls[:n]:
            data_array.append({"url": download_url})

    while len(data_array) < n and len(data_array) > 0:
        for item in data_array.copy():
            if len(data_array) >= n:
                break
            data_array.append(item)

    response_json = {
        "created": int(datetime.now(timezone.utc).timestamp()),
        "data": data_array
    }
    if not data_array:
        return send_error_response("无法生成图像。", status_code=500)
    return JSONResponse(content=response_json, status_code=200)

@app.get("/v1/models", response_class=JSONResponse)
async def get_models():
    models_data = {
        "object": "list",
        "data": [
            {"id": "deepseek-r1", "object": "model"},
            {"id": "gpt-4o", "object": "model"},
            {"id": "gpt-4o-mini", "object": "model"},
            {"id": "claude-3-5-sonnet", "object": "model"},
            {"id": "claude", "object": "model"}
        ]
    }
    return models_data

async def get_available_port(start_port: int = INITIAL_PORT, end_port: int = 65535) -> int:
    for port in range(start_port, end_port + 1):
        try:
            server = await asyncio.start_server(lambda r, w: None, host="0.0.0.0", port=port)
            server.close()
            await server.wait_closed()
            return port
        except OSError:
            continue
    raise RuntimeError(f"No available ports between {start_port} and {end_port}")

def main():
    parser = argparse.ArgumentParser(description="启动服务器")
    parser.add_argument('--port', type=int, default=INITIAL_PORT, help='服务器监听端口')
    args = parser.parse_args()
    port = args.port
    try:
        port = asyncio.run(get_available_port(start_port=port))
    except RuntimeError as e:
        # print(e)
        return
    # print(f"Server running on available port: {port}")
    uvicorn.run(app, host="0.0.0.0", port=port)

if __name__ == "__main__":
    main()
