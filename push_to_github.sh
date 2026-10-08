#!/usr/bin/env bash
# 一键推送 MoMA 安卓客户端到你的 GitHub 并触发云编译。
# 使用方法：
#   1) 先在终端执行 `gh auth login`（浏览器登录你的 GitHub 账号，勾选 repo 权限）
#   2) 把本目录放到一台能联网的电脑上（已装 git + gh）
#   3) 在此目录运行：  bash push_to_github.sh
#
# 脚本会：创建公开仓库 -> 关联 remote -> 推送 main -> 打开 Actions 页面

set -e

REPO_NAME="${1:-moma-android-client}"   # 可传参自定义仓库名
VISIBILITY="${2:-public}"               # public / private

echo ">>> 检查 gh 登录..."
gh auth status >/dev/null 2>&1 || { echo "请先运行: gh auth login"; exit 1; }

echo ">>> 创建 GitHub 仓库: $REPO_NAME ($VISIBILITY)"
gh repo create "$REPO_NAME" --"$VISIBILITY" --source=. --remote=origin --push || {
  echo "（仓库可能已存在，尝试仅推送）"
  git remote add origin "https://github.com/$(gh api user --jq .login)/$REPO_NAME.git" 2>/dev/null || true
  git push -u origin main || git push -u origin master
}

echo ">>> 完成！在浏览器查看构建进度："
gh repo view --web 2>/dev/null || true
echo "Actions 页面: https://github.com/$(gh api user --jq .login)/$REPO_NAME/actions"

echo ""
echo "构建完成后，到 Actions -> 最新一次 run -> Artifacts 下载 app-debug.apk 安装即可。"
