#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成「UGOS Pro 图形界面直接导入」的 NAS 常驻部署包。

绿联 UGOS Pro 的「容器 → 项目」如果只上传一个 docker-compose.yml，
compose 里的 build: context: . 会找不到 Dockerfile / console.py / static，
构建必然失败。这里把运行所需的全部文件按仓库原有目录结构打成一个 zip，
解压到 NAS 共享文件夹后，图形界面里选中这个文件夹就能直接部署。

    python tools/make_nas_bundle.py                  # 预置本机 nas.local.json（含 SMB 凭据）
    python tools/make_nas_bundle.py --no-secrets    # 不含凭据，首次打开页面自己填连接设置

产物：dist/kidtv-console-ugospro.zip（含访问码和可选凭据，不进 Git）
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import secrets
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "dist" / "kidtv-console-ugospro.zip"
PREFIX = "kidtv-console"

# 保持和仓库一致的相对路径：console.py 用 __file__.parent.parent 定位 static / examples
FILES = [
    "tools/console.py",
    "tools/requirements.txt",
    "tools/static/index.html",
    "tools/static/desktop.html",
    "examples/tv.json",
    "nas.example.json",
]

DOCKERFILE = """# 小剧场片单控制台 · UGOS Pro 常驻镜像
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \\
    TZ=Asia/Shanghai \\
    KIDTV_CONFIG=/app/config/nas.local.json \\
    KIDTV_PORT=8765

WORKDIR /app

COPY tools/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt

COPY tools/console.py /app/tools/console.py
COPY tools/static /app/tools/static
COPY examples/tv.json /app/examples/tv.json
COPY nas.example.json /app/nas.example.json

RUN mkdir -p /app/config
VOLUME ["/app/config"]
EXPOSE 8765

CMD ["python", "/app/tools/console.py", "--lan", "--no-browser"]
"""

DOCKERIGNORE = """# 凭据和本地状态绝不进镜像构建上下文
config/
.env
*.log
__pycache__/
.git/
"""


def compose(token: str, trust_proxy: str = "0") -> str:
    return f"""# 小剧场片单控制台 · UGOS Pro「容器 → 项目」直接导入
# 项目路径必须选本文件所在的 kidtv-console 文件夹（Dockerfile、tools/、examples/ 都在里面）。
# 访问码已固定，重建容器 / 换 NAS 后手机书签与外网登录仍然有效。
services:
  kidtv-console:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: kidtv-console
    restart: unless-stopped
    ports:
      - "8765:8765"
    environment:
      TZ: Asia/Shanghai
      KIDTV_CONFIG: /app/config/nas.local.json
      KIDTV_TOKEN: "{token}"
      # 走 UGREENlink / Cloudflare Tunnel 等反向代理时改成 1（按真实客户端限速）；直连保持 0
      KIDTV_TRUST_PROXY: "{trust_proxy}"
    volumes:
      # 连接设置和访问码放在 NAS 文件夹里，重建容器不丢，也方便备份
      - ./config:/app/config
    healthcheck:
      test: ["CMD", "python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8765/api/desktop/ping', timeout=3)"]
      interval: 30s
      timeout: 5s
      retries: 3
"""


def compose_nobuild(token: str, trust_proxy: str = "0") -> str:
    """备用：不构建镜像，直接挂目录运行（图形界面不支持 build 时用这个）。"""
    return f"""# 备用方案：免 build。把本文件的内容作为「项目」的 YAML 粘贴即可。
# 项目路径仍要选 kidtv-console 文件夹；首次启动会联网装 pysmb/qrcode，约十几秒。
services:
  kidtv-console:
    image: python:3.12-slim
    container_name: kidtv-console
    restart: unless-stopped
    working_dir: /app
    ports:
      - "8765:8765"
    environment:
      TZ: Asia/Shanghai
      KIDTV_CONFIG: /app/config/nas.local.json
      KIDTV_TOKEN: "{token}"
      KIDTV_TRUST_PROXY: "{trust_proxy}"
    volumes:
      - ./:/app
      - ./config:/app/config
    command: >
      sh -c "pip install --no-cache-dir -q -r /app/tools/requirements.txt &&
             python /app/tools/console.py --lan --no-browser"
    healthcheck:
      test: ["CMD", "python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8765/api/desktop/ping', timeout=3)"]
      interval: 30s
      timeout: 5s
      retries: 3
"""


def env_file(token: str) -> str:
    return (
        "# 命令行 docker compose up -d --build 时会读这个文件；\n"
        "# 图形界面「项目」已把同样的值写进 docker-compose.yml，无需再改。\n"
        f"KIDTV_TOKEN={token}\n"
        "KIDTV_PORT=8765\n"
        "KIDTV_TRUST_PROXY=0\n"
    )


def install_sh() -> str:
    return """#!/bin/sh
# 可选：在 NAS 上开 SSH 后，把本文件夹整个传上去，执行 ./install.sh
set -e
cd "$(dirname "$0")"
docker compose up -d --build
echo "---- 状态 ----"
docker compose ps
echo "家里打开: http://$(hostname -I 2>/dev/null | awk '{print $1}'):8765/"
echo "看日志  : docker compose logs -f kidtv-console"
"""


def guide(token: str, cfg: dict, has_secrets: bool) -> str:
    host = cfg.get("host") or "192.168.31.72"
    user = cfg.get("user") or "（你的 SMB 账号）"
    share = cfg.get("playlistShare") or "data"
    path = cfg.get("playlistPath") or "curated/tv.json"
    secret_note = (
        "⚠️ **本包 `config/nas.local.json` 含 NAS 明文账号密码**：只在自己电脑和 NAS 之间传，"
        "不要发微信 / 网盘 / 邮件；部署完成后可以删掉电脑上的这个 zip。\n"
        if has_secrets else
        "本包不含任何凭据：首次打开页面点「连接设置」自己填一次 NAS 账号密码。\n"
    )
    return f"""# 小剧场控制台 · UGOS Pro 部署包

{secret_note}
**访问码：`{token}`**（首次打开页面输入一次即可，浏览器会记住；外网请用不带 `?k=` 的网址）
重新生成 zip 会换访问码，想保留旧码用 `python tools/make_nas_bundle.py --token {token}`。

---

## 一、把文件夹放到 NAS

1. 绿联云 / 文件管理，进入一个共享文件夹（例如 `data`），新建目录 `kidtv-console`。
2. 把本 zip 解压出来的 `kidtv-console/` 里**全部文件**上传进去，保持目录结构：

```
kidtv-console/
├── docker-compose.yml          ← 主用
├── docker-compose.nobuild.yml  ← 备用（图形界面不支持 build 时）
├── Dockerfile
├── .dockerignore
├── .env
├── install.sh                  ← 可选：SSH 命令行部署
├── nas.example.json
├── examples/tv.json
├── tools/console.py
├── tools/requirements.txt
├── tools/static/index.html
├── tools/static/desktop.html
└── config/nas.local.json       ← 连接设置（可能含密码）
```

> ⚠️ **最常见的失败**：只把 `docker-compose.yml` 一个文件传上去 / 只粘贴 YAML 内容，
> 却没有把 `Dockerfile`、`tools/`、`examples/` 放在同一目录，会报
> `Dockerfile not found` 或 `COPY failed`。绿联「项目」的**路径要选这个文件夹本身**。

## 二、在 UGOS Pro 里创建项目

1. 打开 UGOS Pro → **控制面板 → 容器（Docker）→ 项目** → **创建**。
2. 项目名称填 `kidtv-console`；**存放路径 / 项目路径选第一步那个 `kidtv-console` 文件夹**。
3. Compose 配置选「**使用现有 docker-compose.yml**」或「上传」，选目录里的 `docker-compose.yml`；
   如果界面只能粘贴文本，就把该文件内容整段粘进去。
4. 点「完成 / 部署」。首次会拉取 `python:3.12-slim` 并构建，几分钟，属正常。
5. 状态变成 running 后，家里电脑或手机打开 **http://{host}:8765/**。

**如果第 4 步构建报错**（有些固件版本的图形化 Docker 不支持 `build`）：
删掉这个项目，重新创建，改用 `docker-compose.nobuild.yml` 的内容 —— 它直接用官方 Python 镜像
挂载目录运行，不需要构建，首次启动会自动装 `pysmb` / `qrcode`。

**如果会开 SSH**：把整个文件夹传到 NAS，`cd kidtv-console && sh ./install.sh` 一条命令搞定。

## 三、首次使用

- 页面提示「需要访问码」→ 输入 `{token}`。只存在浏览器本地，网址里不留密钥。
- 点右上角 / 连接设置，核对这几项（本包已预置，一般不用改）：

| 项 | 值 |
|---|---|
| NAS 地址 | `{host}` |
| 账号 | `{user}` |
| 清单共享 | `{share}` |
| 清单路径 | `{path}` |

- 点「**检测连接**」应显示已连接并能读到 `tv.json`；之后正常编排、保存到 NAS 即可，
  保存前会自动备份。电视端按菜单「重新读取清单」生效。

> 容器通过局域网 IP 访问 NAS 自己的 SMB。若「检测连接」失败，
> 把 NAS 地址试成 `host.docker.internal` 或 Docker 网桥网关 `172.17.0.1`，
> 并确认 UGOS Pro 防火墙没拦 445。

## 四、外网也能改（二选一）

**方案 B · UGREENlink（最省事）**
1. 控制面板 → 设备连接 → 远程访问 → 开启 UGREENlink，绑定绿联账号。
2. 在容器详情里给 `kidtv-console` 的 **8765 端口创建桌面快捷方式**（部分版本叫「快捷方式 / 添加到桌面」）。
3. 外网浏览器登录绿联账号，在 UGOS 桌面点这个快捷方式即可；HTTPS 由绿联提供。
4. 把 `docker-compose.yml` 里 `KIDTV_TRUST_PROXY` 改成 `"1"` 后重新部署，
   这样经反向代理时能按真实客户端做访问码限速。
5. 外网书签用根地址（**不要带 `?k=`**），访问码只在首次输入。

> 若你的 1.21 界面没有「容器快捷方式」入口，直接走方案 A。

**方案 A · Tailscale（更安全，不对外开端口）**
见仓库 README「外网也能改片单」和 `docker-compose.tailscale.yml`。

**✗ 不要**在路由器把 8765 映射到公网、也不要用 DDNS 裸奔：这个页面能读 NAS 明文账号密码、
改家长 PIN 和整份片单。

## 五、排错

| 现象 | 处理 |
|---|---|
| `Dockerfile not found` / `COPY failed` | 只上传了 compose 文件；把整个解压目录放到项目路径 |
| 镜像拉取很慢 / 超时 | 在 UGOS Pro 的 Docker 设置里配国内镜像加速，或等几分钟重试 |
| 8765 打不开 | 容器日志有没有报错；端口是否被别的服务占用；绿联防火墙 |
| 检测连接失败 | 换 `host.docker.internal` / `172.17.0.1`；确认 SMB 账号密码、445 可达 |
| 页面反复要访问码 | 用的是根地址，输一次访问码后浏览器会记住；别清站点数据 |
| 忘记访问码 | 看本文件开头，或 `config/.console_token` / `docker-compose.yml` |
| 改完电视没变 | 电视菜单里点「重新读取清单」 |

访问码连错 5 次会锁 5 分钟（429），等一会儿或重启容器即可。
"""


def verify(entries: dict[str, str | bytes], token: str) -> None:
    """打包前自检：Dockerfile 的 COPY 源、compose 的引用、目录结构必须齐全。"""
    names = set(entries)
    for src in re.findall(r"^COPY\s+(\S+)", DOCKERFILE, re.M):
        key = f"{PREFIX}/{src}"
        hit = key in names or any(n.startswith(key + "/") for n in names)
        if not hit:
            raise SystemExit(f"Dockerfile 引用的 {src} 不在部署包里，构建必失败")
    for required in (
        "Dockerfile", ".dockerignore", "docker-compose.yml",
        "docker-compose.nobuild.yml", ".env", "install.sh", "使用说明.md",
        "tools/console.py", "tools/requirements.txt",
        "tools/static/index.html", "tools/static/desktop.html",
        "examples/tv.json", "nas.example.json",
    ):
        if f"{PREFIX}/{required}" not in names:
            raise SystemExit(f"部署包缺少 {required}")
    if f'KIDTV_TOKEN: "{token}"' not in entries[f"{PREFIX}/docker-compose.yml"]:
        raise SystemExit("compose 里的访问码与 .env 不一致")
    if token not in entries[f"{PREFIX}/使用说明.md"]:
        raise SystemExit("说明文件里的访问码不一致")


def main() -> int:
    ap = argparse.ArgumentParser(description="生成 UGOS Pro NAS 常驻部署包")
    ap.add_argument("--no-secrets", action="store_true", help="不把本机 nas.local.json 放进包里")
    ap.add_argument("--token", help="指定访问码（默认随机生成 24 位十六进制）")
    ap.add_argument("--output", default=str(OUT), help="zip 输出路径")
    args = ap.parse_args()

    token = args.token or secrets.token_hex(12)
    cfg: dict = {}
    has_secrets = False
    src_cfg = ROOT / "nas.local.json"
    if src_cfg.exists() and not args.no_secrets:
        cfg = json.loads(src_cfg.read_text(encoding="utf-8"))
        has_secrets = True

    out = pathlib.Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)

    entries: dict[str, str | bytes] = {}
    for rel in FILES:
        src = ROOT / rel
        if not src.exists():
            raise SystemExit(f"缺少文件：{src}")
        entries[f"{PREFIX}/{rel}"] = src.read_bytes()

    entries[f"{PREFIX}/Dockerfile"] = DOCKERFILE
    entries[f"{PREFIX}/.dockerignore"] = DOCKERIGNORE
    entries[f"{PREFIX}/docker-compose.yml"] = compose(token)
    entries[f"{PREFIX}/docker-compose.nobuild.yml"] = compose_nobuild(token)
    entries[f"{PREFIX}/.env"] = env_file(token)
    entries[f"{PREFIX}/install.sh"] = install_sh()
    entries[f"{PREFIX}/使用说明.md"] = guide(token, cfg, has_secrets)
    if has_secrets:
        entries[f"{PREFIX}/config/nas.local.json"] = src_cfg.read_bytes()
    else:
        entries[f"{PREFIX}/config/.gitkeep"] = b""

    verify(entries, token)

    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name, data in entries.items():
            z.writestr(name, data)

    summary = {
        "zip": str(out),
        "size": out.stat().st_size,
        "token": token,
        "files": len(entries),
        "has_secrets": has_secrets,
        "nas_host": cfg.get("host", ""),
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
