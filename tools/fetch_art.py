#!/usr/bin/env python3
"""下载内置名画缩略图（公共领域作品）到 app/src/main/assets/art/。

图片来自 Wikimedia Commons，通过官方 API 取 800px 缩略图，
只用于电视锁屏上做名画欣赏，全部是作者逝世逾百年的公共领域作品。
源文件清单与出处写到 docs/ART_SOURCES.md。

用法：
    python tools/fetch_art.py                    # 只补缺的文件
    python tools/fetch_art.py --proxy 127.0.0.1:7890
    python tools/fetch_art.py --force           # 重新下载全部
    python tools/fetch_art.py --check           # 只校验本地图片完整性

ART 里的 Commons 文件名来自英文维基条目的主图（pageimages），
写入时用下划线代替空格，脚本会还原成 MediaWiki 的标准文件名。
仓库里已经带了下载好的图片，正常开发/编译不需要跑这个脚本。
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import sys
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "assets" / "art"
SOURCES = ROOT / "docs" / "ART_SOURCES.md"
API = "https://commons.wikimedia.org/w/api.php"
THUMB_WIDTH = 800

# (本地文件名, Commons 文件名(下划线=空格), 作者 / 说明)
# 顺序与 app/.../data/builtin/Arts.kt 中的卡片一一对应。
ART = [
    ("starry_night.jpg", "Van Gogh - Starry Night - Google Art Project.jpg", "文森特·梵高《星月夜》1889"),
    ("mona_lisa.jpg", "Mona Lisa.jpg", "列奥纳多·达·芬奇《蒙娜丽莎》约 1503"),
    ("great_wave.jpg", "The Great Wave off Kanagawa.jpg", "葛饰北斋《神奈川冲浪里》约 1831"),
    ("pearl_earring.jpg", "1665 Girl with a Pearl Earring.jpg", "约翰内斯·维米尔《戴珍珠耳环的少女》约 1665"),
    ("sunflowers.jpg", "Vincent van Gogh - Sunflowers - VGM F458.jpg", "文森特·梵高《向日葵》1888"),
    ("water_lilies.jpg", "Claude Monet - Water Lilies - 1906, Ryerson.jpg", "克劳德·莫奈《睡莲》1906"),
    ("the_scream.jpg", "Edvard Munch, 1893, The Scream, oil, tempera and pastel on cardboard, 91 x 73 cm, National Gallery of Norway.jpg", "爱德华·蒙克《呐喊》1893"),
    ("grande_jatte.jpg", "A Sunday on La Grande Jatte, Georges Seurat, 1884.jpg", "乔治·修拉《大碗岛的星期天下午》1884"),
    ("birth_of_venus.jpg", "Sandro Botticelli - La nascita di Venere - Google Art Project - edited.jpg", "桑德罗·波提切利《维纳斯的诞生》约 1485"),
    ("qingming.jpg", "Along the River During the Qingming Festival (detail of original).jpg", "张择端《清明上河图》局部，北宋"),

    ("last_supper.jpg", "The_Last_Supper_-_Leonardo_Da_Vinci_-_High_Resolution_32x16.jpg", "列奥纳多·达·芬奇《最后的晚餐》1495—1498"),
    ("creation_adam.jpg", "Michelangelo_-_Creation_of_Adam_(cropped).jpg", "米开朗基罗《创造亚当》局部，约 1512"),
    ("school_athens.jpg", '"The_School_of_Athens"_by_Raffaello_Sanzio_da_Urbino.jpg', "拉斐尔《雅典学院》1509—1511"),
    ("night_watch.jpg", "La_ronda_de_noche,_por_Rembrandt_van_Rijn.jpg", "伦勃朗《夜巡》1642"),
    ("milkmaid.png", "Johannes_Vermeer_-_Het_melkmeisje_-_Google_Art_Project.png", "约翰内斯·维米尔《倒牛奶的女仆》约 1658"),
    ("las_meninas.jpg", "Las_Meninas,_by_Diego_Velázquez,_from_Prado_in_Google_Earth.jpg", "迭戈·委拉斯开兹《宫娥》1656"),
    ("arnolfini.jpg", "The_Arnolfini_portrait_(1434).jpg", "扬·凡·艾克《阿尔诺芬尼夫妇像》1434"),
    ("hay_wain.jpg", "John_Constable_-_The_Hay_Wain_(1821).jpg", "约翰·康斯特布尔《干草车》1821"),
    ("temeraire.jpg", "The_Fighting_Temeraire,_JMW_Turner,_National_Gallery.jpg", "约瑟夫·透纳《被拖去解体的战舰无畏号》1839"),
    ("impression_sunrise.jpg", "Monet_-_Impression,_Sunrise.jpg", "克劳德·莫奈《印象·日出》1872"),
    ("ballet_class.jpg", "Edgar_Degas_-_La_Classe_de_danse.jpg", "埃德加·德加《舞蹈课》1874"),
    ("card_players.jpg", "Les_Joueurs_de_cartes,_par_Paul_Cézanne.jpg", "保罗·塞尚《玩纸牌者》1890—1895"),
    ("bedroom_arles.jpg", "Vincent_van_Gogh_-_De_slaapkamer_-_Google_Art_Project.jpg", "文森特·梵高《阿尔勒的卧室》1888"),
    ("cafe_terrace.jpg", "Van_Gogh_-_Terrace_of_a_Café_at_Night_(Place_du_Forum)_1888.jpg", "文森特·梵高《夜间咖啡馆露台》1888"),
    ("irises.jpg", "Irises-Vincent_van_Gogh.jpg", "文森特·梵高《鸢尾花》1889"),
    ("almond_blossom.jpg", "Vincent_van_Gogh_-_Almond_blossom_-_Google_Art_Project.jpg", "文森特·梵高《盛开的杏花》1890"),
    ("the_kiss.jpg", "The_Kiss_-_Gustav_Klimt_-_Google_Cultural_Institute.jpg", "古斯塔夫·克里姆特《吻》1908"),
    ("wanderer.jpeg", "Caspar_David_Friedrich_-_Wanderer_above_the_Sea_of_Fog.jpeg", "卡斯帕·大卫·弗里德里希《雾海上的旅人》约 1818"),
    ("tower_babel.jpg", "Pieter_Bruegel_the_Elder_-_The_Tower_of_Babel_(Vienna)_-_Google_Art_Project_-_edited.jpg", "老彼得·勃鲁盖尔《巴别塔》1563"),
    ("hunters_snow.jpg", "Pieter_Bruegel_the_Elder_-_Hunters_in_the_Snow_(Winter)_-_Google_Art_Project.jpg", "老彼得·勃鲁盖尔《雪中猎人》1565"),
    ("primavera.jpg", "Botticelli-primavera.jpg", "桑德罗·波提切利《春》约 1480"),
    ("sistine_madonna.jpg", "RAFAEL_-_Madonna_Sixtina_(Gemäldegalerie_Alter_Meister,_Dresden,_1513-14._Óleo_sobre_lienzo,_265_x_196_cm).jpg", "拉斐尔《西斯廷圣母》1513—1514"),
    ("the_swing.jpg", "The_Swing_(P430).jpg", "让-奥诺雷·弗拉戈纳尔《秋千》1767"),
    ("moulin_galette.jpg", "Renoir,_Pierre-Auguste_-_Dance_at_Le_Moulin_de_la_Galette,_1876.jpg", "皮埃尔-奥古斯特·雷诺阿《煎饼磨坊的舞会》1876"),
    ("two_sisters.jpg", "Two_Sisters_(On_the_Terrace).jpg", "皮埃尔-奥古斯特·雷诺阿《两姐妹》1881"),
    ("goldfinch.jpg", "Fabritius-vink.jpg", "卡雷尔·法布里蒂乌斯《金翅雀》1654"),
    ("flaming_june.jpg", "Flaming_June,_by_Frederic_Lord_Leighton_(1830-1896).jpg", "弗雷德里克·莱顿《燃烧的六月》1895"),
    ("red_fuji.jpg", "「富嶽三十六景_凱風快晴」-South_Wind,_Clear_Sky_(Gaifū_kaisei),_also_known_as_Red_Fuji,_from_the_series_Thirty-six_Views_of_Mount_Fuji_(Fugaku_sanjūrokkei)_MET_DP141062.jpg", "葛饰北斋《凯风快晴》（红富士）约 1831"),
    ("early_spring.jpg", "Guo_Xi_-_Early_Spring_(large).jpg", "郭熙《早春图》1072，北宋"),
    ("fuchun.jpg", "Dwelling_in_the_Fuchun_Mountains_(first_half).JPG", "黄公望《富春山居图》前半卷，元"),
    ("thousand_li.jpg", "A_Thousand_Li_of_Rivers_and_Mountains_part.jpg", "王希孟《千里江山图》局部，北宋"),
    ("night_revels.jpg", "Gu_Hongzhong's_Night_Revels_1_edit_(cropped2)_(cropped)_(cropped).jpg", "顾闳中《韩熙载夜宴图》局部，五代"),
    ("magpie.jpg", "Claude_Monet_-_The_Magpie_-_Google_Art_Project.jpg", "克劳德·莫奈《喜鹊》1868—1869"),
    ("monet_garden.jpg", "Monet_-_Monets_Garten_in_Giverny.jpg", "克劳德·莫奈《吉维尼的花园》1900"),
    ("blue_dancers.jpg", "Edgar_Degas_-_Blue_Dancers_(1898_Pushkin_Museum).jpg", "埃德加·德加《蓝色舞者》约 1898"),
    ("childs_bath.jpg", "Mary_Cassatt_-_The_Child's_Bath_-_Google_Art_Project.jpg", "玛丽·卡萨特《孩子的沐浴》1893"),
    ("mont_sainte.jpg", "Paul_Cézanne_-_Montagne_Saint-victoire_-_Google_Art_Project.jpg", "保罗·塞尚《圣维克多山》约 1904"),
    ("basket_apples.jpg", "Paul_Cézanne_-_The_Basket_of_Apples_-_1926.252_-_Art_Institute_of_Chicago.jpg", "保罗·塞尚《一篮苹果》1893"),
    ("tiger_storm.jpg", "Surprised-Rousseau.jpg", "亨利·卢梭《热带风暴中的老虎（惊喜！）》1891"),
    ("whistlejacket.jpg", "Whistlejacket_by_George_Stubbs_edit.jpg", "乔治·斯塔布斯《惠斯尔杰克特》1762"),
]


def commons_title(s: str) -> str:
    """维基文件名里下划线就是空格；首字母大小写交给 API 规范化。"""
    return s.replace("_", " ")


def opener_for(proxy: str | None):
    proxies = {}
    p = proxy or os.environ.get("HTTPS_PROXY") or os.environ.get("HTTP_PROXY")
    if p:
        if not p.startswith("http"):
            p = "http://" + p
        proxies = {"http": p, "https": p}
        print("使用代理:", p)
    return urllib.request.build_opener(urllib.request.ProxyHandler(proxies))


def get_json(op, params: dict) -> dict:
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": "KidTvCurated/1.0 (art assets fetcher)"})
    with op.open(req, timeout=40) as r:
        return json.load(r)


def thumb_urls(op) -> dict[str, tuple[str, str]]:
    """分批查询，避免超长文件名把 GET URL 撑爆。"""
    titles = [commons_title(t) for _, t, _ in ART]
    out: dict[str, tuple[str, str]] = {}
    for i in range(0, len(titles), 12):
        batch = titles[i:i + 12]
        data = get_json(op, {
            "action": "query", "prop": "imageinfo", "iiprop": "url",
            "iiurlwidth": str(THUMB_WIDTH), "format": "json",
            "titles": "|".join("File:" + t for t in batch),
        })
        for page in data.get("query", {}).get("pages", {}).values():
            ii = (page.get("imageinfo") or [{}])[0]
            if ii.get("thumburl"):
                key = page["title"].removeprefix("File:").replace("_", " ")
                out[key] = (ii["thumburl"], ii.get("descriptionurl", ""))
    return out


def valid_image(blob: bytes) -> bool:
    return len(blob) >= 5000 and (
        blob.startswith(b"\xff\xd8")
        or blob.startswith(b"\x89PNG\r\n\x1a\n")
        or (blob.startswith(b"RIFF") and blob[8:12] == b"WEBP")
    )


def page_url(title: str) -> str:
    return "https://commons.wikimedia.org/wiki/File:" + urllib.parse.quote(
        commons_title(title).replace(" ", "_"))


def write_sources() -> None:
    lines = [
        "# 内置名画图片来源",
        "",
        "全部来自 Wikimedia Commons，作者逝世均已超过著作权保护期，属公共领域作品。",
        f"图片由 `tools/fetch_art.py` 通过 Commons API 下载 {THUMB_WIDTH}px 缩略图，",
        "编译进 APK 的 `assets/art/`，电视不联网也能显示。",
        "",
        f"共 {len(ART)} 幅。含中国画 5 幅、日本浮世绘 2 幅，题材以风景、动物、儿童与日常生活为主。",
        "",
        "| 本地文件 | 作品 | Commons 页面 |",
        "|---|---|---|",
    ]
    for local, title, desc in ART:
        lines.append("| `%s` | %s | [链接](%s) |" % (local, desc, page_url(title)))
    SOURCES.parent.mkdir(parents=True, exist_ok=True)
    SOURCES.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--proxy", help="例如 127.0.0.1:7890")
    ap.add_argument("--force", action="store_true", help="已存在也重新下载")
    ap.add_argument("--check", action="store_true", help="只校验本地图片，不联网")
    args = ap.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    if args.check:
        bad = [local for local, _, _ in ART
               if not (OUT / local).exists()
               or not valid_image((OUT / local).read_bytes())]
        print("本地图片: %d/%d 有效" % (len(ART) - len(bad), len(ART)))
        for f in bad:
            print("  缺失或损坏:", f)
        return 0 if not bad else 2

    op = opener_for(args.proxy)
    try:
        urls = thumb_urls(op)
    except Exception as e:
        print("取图片地址失败:", e, file=sys.stderr)
        return 1

    missing_titles = [t for _, t, _ in ART if commons_title(t) not in urls]
    if missing_titles:
        print("Commons 没找到这些文件名（不会写入对应图片）:", file=sys.stderr)
        for t in missing_titles:
            print("  ", t, file=sys.stderr)

    ok = 0
    for local, title, desc in ART:
        dst = OUT / local
        info = urls.get(commons_title(title))
        if dst.exists() and not args.force:
            print("跳过（已存在）:", local)
            ok += 1
            continue
        if not info:
            continue
        try:
            req = urllib.request.Request(info[0], headers={"User-Agent": "KidTvCurated/1.0 (art assets fetcher)"})
            with op.open(req, timeout=60) as r:
                blob = r.read()
            if not valid_image(blob):
                raise ValueError("不是有效图片或过小：%d 字节" % len(blob))
            dst.write_bytes(blob)
            print("下载:", local, "%.0f KB" % (len(blob) / 1024))
            ok += 1
        except Exception as e:
            print("下载失败:", local, e, file=sys.stderr)

    write_sources()
    print("完成：%d/%d 张，来源清单 %s" % (ok, len(ART), SOURCES.relative_to(ROOT)))
    return 0 if ok == len(ART) else 2


if __name__ == "__main__":
    raise SystemExit(main())
