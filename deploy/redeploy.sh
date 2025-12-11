#!/bin/bash

# 配置脚本
IMAGE_NAME=bookmarkify-api #镜像名称
DOCKER_FILE_DIR=/root/docker/jenkins/workspace/deploy/bookmarkify-api #存放Dockerfile的目录
# 可能出现的问题:
# 1.SSH密钥未分发,可能每次重启jenkins容器都会删除镜像中的密钥信息
# 2.首次SSH连接失败,首次手动从jenkins容器连接到目标服务器,手动确认保存目标服务器指纹
# 3.分发以后依旧失败,重启Jenkins容器试试


# Step 1: 找到运行中的容器并停用
container_id=$(docker ps | grep $IMAGE_NAME | awk '{print $1}')

if [ -n "$container_id" ]; then
  echo "[redeploy.sh] 停止了容器: $container_id"
  docker stop "$container_id"
else
  echo "[redeploy.sh] 没有找到容器: $IMAGE_NAME"
fi

# Step 2: 找到镜像并删除
image_id=$(docker images | grep $IMAGE_NAME | awk '{print $3}')

if [ -n "$image_id" ]; then
  echo "[redeploy.sh] 移除了镜像: $image_id"
  docker rmi -f "$image_id"
else
  echo "[redeploy.sh] 没有镜像 $IMAGE_NAME"
fi

# Step 3: 重新打包镜像
echo "[redeploy.sh] 开始打包镜像: $IMAGE_NAME"
docker build -t $IMAGE_NAME $DOCKER_FILE_DIR

# Step 4: 重新运行
docker compose -f /root/docker/compose.yml up $IMAGE_NAME -d

# Step 5: 清理docker数据
docker system prune -f
echo "[redeploy.sh] DONE!"