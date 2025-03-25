//image_uploader.h
#ifndef IMAGE_UPLOADER_H
#define IMAGE_UPLOADER_H
#include "global.h"
#include <string>

// 上传 data URL 格式的图片，返回上传后图片的 URL
std::string uploadImageFromDataUrl(const std::string& dataUrl);

#endif // IMAGE_UPLOADER_H
