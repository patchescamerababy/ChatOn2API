import base64
import hmac
import hashlib
import os
from datetime import datetime
from typing import List, Optional

class BearerTokenGenerator:
    keyA = os.environ.get('KEY_A', '').encode('utf-8')
    keyB = os.environ.get('KEY_B', '').encode('utf-8')
    
    @staticmethod
    def get_bearer(body_content: str, source: str = "/chats/stream", method: str = "POST", formatted_date: str = "") -> Optional[str]:
        """
        根据传入的 source（路径参数）和 body_content 生成 Bearer Token 和格式化的日期字符串。

        :param body_content: 请求体的字符串形式
        :param source: 用于生成签名的路径参数（例如：/chats/stream 或 /storage/upload）
        :return: Bearer Token；如果失败则返回 None
        """
        try:
            # 检查密钥是否存在
            if not BearerTokenGenerator.keyA or not BearerTokenGenerator.keyB:
                raise ValueError("Missing required environment variables KEY_A or KEY_B")

            #formatted_date = BearerTokenGenerator.get_formatted_date()
            # 根据 source 参数构造签名字符串
            combined_string = f"{method}:{source}:{formatted_date}\n"
            combined_bytes = combined_string.encode('utf-8') + body_content.encode('utf-8')
            # 生成 HMAC-SHA256 签名
            signature = hmac.new(BearerTokenGenerator.keyB, combined_bytes, hashlib.sha256).digest()
            encoded_signature = base64.b64encode(signature).decode('utf-8')
            encoded_keyA = base64.b64encode(BearerTokenGenerator.keyA).decode('utf-8')
            bearer_token = f"Bearer {encoded_keyA}.{encoded_signature}"
            print(f"Generated Token for source '{source}\n': {bearer_token}")
            print(f"Date: {formatted_date}")
            return bearer_token
        except Exception as e:
            print(f"Error generating Bearer Token: {e}")
            return None

    @staticmethod
    def get_formatted_date() -> str:
        return datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")

    @staticmethod
    def get_user_agent() -> str:
        return os.environ.get('USER_AGENT', '')
