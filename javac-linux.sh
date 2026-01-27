#!/bin/bash
# 设置Java目录，适合多JDK环境的用户，但是默认不要设置，我们默认用户的系统中有JAVA_HOME
JAVA_HOME=/opt/jdk-24.0.2/
# 设置项目根目录（确保从项目根路径执行）
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$PROJECT_ROOT/src/main/java"
RES_DIR_1="$PROJECT_ROOT/src/main/resources"
RES_DIR_2="$PROJECT_ROOT/resources"
CLASSES_DIR="$PROJECT_ROOT/build/classes"
LIB_DIR="$PROJECT_ROOT/lib"
# === 1. 强制要求 JAVA_HOME 已设置且有效 ===
if [ -z "$JAVA_HOME" ]; then
    echo "❌ 错误：环境变量 JAVA_HOME 未设置。请指定 JDK 21 安装路径。"
    echo "   示例: export JAVA_HOME=/usr/lib/jvm/jdk-21"
    exit 1
fi
if [ ! -d "$JAVA_HOME" ]; then
    echo "❌ 错误：JAVA_HOME 指向的目录不存在: $JAVA_HOME"
    exit 1
fi
JAVAC="$JAVA_HOME/bin/javac"
if [ ! -f "$JAVAC" ] || [ ! -x "$JAVAC" ]; then
    echo "❌ 错误：找不到可执行的 javac: $JAVAC"
    echo "   请确认 JAVA_HOME 指向正确的 JDK 21 安装目录。"
    exit 1
fi
# 验证版本是否为 JDK 21
JAVA_VERSION=$("$JAVAC" -version 2>&1)
if [[ "$JAVA_VERSION" != *"21."* ]]; then
    echo "⚠️ 警告：检测到 Java 编译器版本不是 JDK 21: $JAVA_VERSION"
    echo "   建议使用 JDK 21 以确保语言特性和性能优化兼容。"
fi
# === 2. 清理并创建输出目录，同时复制lib文件 ===
rm -rf "$CLASSES_DIR"
mkdir -p "$CLASSES_DIR"
mkdir -p "$PROJECT_ROOT/build/lib"
cp "$LIB_DIR"/*.jar "$PROJECT_ROOT/build/lib/" 2>/dev/null || true
if [ -d "$RES_DIR_1" ]; then
    echo "📦 正在复制资源文件: $RES_DIR_1 -> $CLASSES_DIR"
    cp -a "$RES_DIR_1/." "$CLASSES_DIR/" 2>/dev/null || true
fi
if [ -d "$RES_DIR_2" ]; then
    echo "📦 正在复制资源文件: $RES_DIR_2 -> $CLASSES_DIR"
    cp -a "$RES_DIR_2/." "$CLASSES_DIR/" 2>/dev/null || true
fi
# === 3. 构建 classpath（lib/ 下所有 .jar 文件）===
CLASSPATH=""
for jar in "$LIB_DIR"/*.jar; do
    if [ -f "$jar" ]; then
        if [ -z "$CLASSPATH" ]; then
            CLASSPATH="$jar"
        else
            CLASSPATH="$CLASSPATH:$jar"
        fi
    fi
done
if [ -z "$CLASSPATH" ]; then
    echo "⚠️ 警告：lib/ 目录下未找到任何 .jar 文件。若项目无依赖可忽略。"
fi
# === 4. 执行编译（使用绝对路径 javac）===
echo "🔧 正在使用 JDK 21 编译源码到 $CLASSES_DIR..."
shopt -s globstar
"$JAVAC" \
    -source 21 \
    -target 21 \
    -encoding UTF-8 \
    -d "$CLASSES_DIR" \
    -cp "$CLASSPATH" \
    "$SRC_DIR"/**/*.java
# === 5. 检查结果，并创建启动脚本 ===
if [ $? -eq 0 ]; then
    RUN_SCRIPT="$PROJECT_ROOT/build/run.sh"
    cat > "$RUN_SCRIPT" << 'EOF'
    #!/bin/bash
    java -Xms64m -Xmx64m -classpath "./classes:./lib/*" org.mark.llamacpp.server.LlamaServer
EOF

    chmod +x "$RUN_SCRIPT"

    echo "✅ 启动脚本已生成: $RUN_SCRIPT"

    echo "✅ 编译成功！"
    echo "   输出目录: $CLASSES_DIR"
    echo "   使用编译器: $JAVAC ($JAVA_VERSION)"
    if [ -n "$CLASSPATH" ]; then
        echo "   类路径包含: $(echo "$CLASSPATH" | tr ':' '\n' | sed 's/^/    /')"
    fi
else
    echo "❌ 编译失败。"
    exit 1
fi
