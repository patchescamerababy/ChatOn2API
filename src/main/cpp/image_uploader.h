//image_uploader.h
#ifndef IMAGE_UPLOADER_H
#define IMAGE_UPLOADER_H
#include "global.h"
#include <string>

// �ϴ� data URL ��ʽ��ͼƬ�������ϴ���ͼƬ�� URL
std::string uploadImageFromDataUrl(const std::string& dataUrl);

#endif // IMAGE_UPLOADER_H
