# 常驻在 NAS / 小主机上，手机随时打开改片单，不用开电脑。
# 构建（在仓库根目录执行）：docker compose up -d --build
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    TZ=Asia/Shanghai \
    KIDTV_CONFIG=/app/config/nas.local.json \
    KIDTV_PORT=8765

WORKDIR /app

COPY tools/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt

COPY tools/console.py /app/tools/console.py
COPY tools/static /app/tools/static
COPY examples/tv.json /app/examples/tv.json
COPY nas.example.json /app/nas.example.json

# 凭据和访问码存在命名卷里（见 docker-compose.yml），不写进镜像
RUN mkdir -p /app/config
VOLUME ["/app/config"]
EXPOSE 8765

CMD ["python", "/app/tools/console.py", "--lan", "--no-browser"]
