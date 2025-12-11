#!/bin/bash

# Config script
IMAGE_NAME=bookmarkify-api # image name
DOCKER_FILE_DIR=/root/docker/jenkins/workspace/deploy/bookmarkify-api # directory containing Dockerfile
# Possible issues:
# 1. SSH key not distributed; container restart may drop keys in image
# 2. First SSH attempt fails; manually SSH from Jenkins container once and accept host key
# 3. If it still fails after distribution, try restarting the Jenkins container


# Step 1: find running container and stop it
container_id=$(docker ps | grep $IMAGE_NAME | awk '{print $1}')

if [ -n "$container_id" ]; then
  echo "[redeploy.sh] Stopped container: $container_id"
  docker stop "$container_id"
else
  echo "[redeploy.sh] No container found: $IMAGE_NAME"
fi

# Step 2: find image and remove it
image_id=$(docker images | grep $IMAGE_NAME | awk '{print $3}')

if [ -n "$image_id" ]; then
  echo "[redeploy.sh] Removed image: $image_id"
  docker rmi -f "$image_id"
else
  echo "[redeploy.sh] No image $IMAGE_NAME"
fi

# Step 3: rebuild image
echo "[redeploy.sh] Start building image: $IMAGE_NAME"
docker build -t $IMAGE_NAME $DOCKER_FILE_DIR

# Step 4: rerun
docker compose -f /root/docker/compose.yml up $IMAGE_NAME -d

# Step 5: clean docker data
docker system prune -f
echo "[redeploy.sh] DONE!"