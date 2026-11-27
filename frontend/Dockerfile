FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
# 배포 이미지는 서버 연결 모드로 빌드한다(VITE_ 값은 빌드할 때 코드에 박혀 실행 중에는 바꿀 수 없음).
# 데모 이미지가 필요하면 --build-arg VITE_CANVAS_SYNC=local. API 주소(VITE_API_BASE)는 비워 같은 출처(Ingress /api)로 부른다
ARG VITE_CANVAS_SYNC=server
ENV VITE_CANVAS_SYNC=$VITE_CANVAS_SYNC
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
