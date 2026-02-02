#!/bin/bash
# 这个脚本会在你添加 SSH key 到 GitHub 后执行

set -e

echo "步骤 1: 测试 SSH 连接..."
ssh -T git@github.com || true

echo ""
echo "步骤 2: 切换到 SSH URL..."
cd /home/user/workspace/LlamacppServer
git remote set-url origin git@github.com:csSone/LlamacppServer.git

echo ""
echo "步骤 3: 推送分支到 GitHub..."
git push -u origin fix/model-name-mismatch-in-chat-completions

echo ""
echo "✅ 完成！分支已推送到你的 fork 仓库"
echo ""
echo "你可以在以下地址查看："
echo "https://github.com/csSone/LlamacppServer/tree/fix/model-name-mismatch-in-chat-completions"
