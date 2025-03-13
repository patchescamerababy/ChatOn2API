# 编译器及编译选项
CXX      := g++
CXXFLAGS := -std=c++20 -Wall -Wextra -I/usr/local/include -I/usr/local/include/httplib -I/usr/local/include/nlohmann

# 链接时搜索路径（根据需要，可修改）
LDFLAGS  := -L/usr/local/lib

# 需要链接的库
LIBS     := -lz -lcurl -lssl -lcrypto -lsqlite3

# 目录设置
SRC_DIR  := src/main/cpp
OBJ_DIR  := build/obj
BIN_DIR  := build/bin
TARGET   := $(BIN_DIR)/ChatOn

# 查找所有源文件，并生成对应的目标文件
SOURCES  := $(wildcard $(SRC_DIR)/*.cpp)
OBJECTS  := $(patsubst $(SRC_DIR)/%.cpp, $(OBJ_DIR)/%.o, $(SOURCES))

# 默认目标：生成可执行文件
all: $(TARGET)

# 链接生成最终可执行文件，注意库链接顺序
$(TARGET): $(OBJECTS)
	@mkdir -p $(BIN_DIR)
	$(CXX) $(OBJECTS) -o $(TARGET) $(LDFLAGS) $(LIBS)

# 编译 .cpp 文件生成 .o 文件
$(OBJ_DIR)/%.o: $(SRC_DIR)/%.cpp
	@mkdir -p $(OBJ_DIR)
	$(CXX) $(CXXFLAGS) -c $< -o $@

# 清理编译产物
clean:
	rm -rf $(OBJ_DIR) $(BIN_DIR)

.PHONY: all clean