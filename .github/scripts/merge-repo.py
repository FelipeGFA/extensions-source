import html
import sys
import json
from pathlib import Path
import shutil

REMOTE_REPO: Path = Path.cwd()
LOCAL_REPO: Path = REMOTE_REPO.parent.joinpath("main/repo")

to_delete: list[str] = json.loads(sys.argv[1])

for module in to_delete:
    apk_name = f"tachiyomi-{module}-v*.*.*.apk"
    icon_name = f"eu.kanade.tachiyomi.extension.{module}.png"
    for file in REMOTE_REPO.joinpath("apk").glob(apk_name):
        print(file.name)
        file.unlink(missing_ok=True)
    for file in REMOTE_REPO.joinpath("icon").glob(icon_name):
        print(file.name)
        file.unlink(missing_ok=True)

shutil.copytree(src=LOCAL_REPO.joinpath("apk"), dst=REMOTE_REPO.joinpath("apk"), dirs_exist_ok = True)
shutil.copytree(src=LOCAL_REPO.joinpath("icon"), dst=REMOTE_REPO.joinpath("icon"), dirs_exist_ok = True)

with REMOTE_REPO.joinpath("index.json").open() as remote_index_file:
    remote_index = json.load(remote_index_file)

with LOCAL_REPO.joinpath("index.min.json").open() as local_index_file:
    local_index = json.load(local_index_file)

# Criar um dicionário para lookup rápido por pkg
remote_pkg_to_item = {item["pkg"]: item for item in remote_index}

filtered_local_index = []
downgrades_detected = False

for item in local_index:
    pkg = item["pkg"]
    if pkg in remote_pkg_to_item:
        remote_item = remote_pkg_to_item[pkg]
        if item["code"] < remote_item["code"]:
            print(f"Downgrade detectado para {pkg}: nova versão {item['version']} (code {item['code']}) < existente {remote_item['version']} (code {remote_item['code']}). Pulando atualização.")
            downgrades_detected = True
            continue
    filtered_local_index.append(item)

if downgrades_detected:
    print("Downgrades detectados. Abortando merge para evitar push de versões mais antigas.")
    sys.exit(1)

index = [
    item for item in remote_index
    if not any([item["pkg"].endswith(f".{module}") for module in to_delete])
]
index.extend(filtered_local_index)
index.sort(key=lambda x: x["pkg"])

with REMOTE_REPO.joinpath("index.json").open("w", encoding="utf-8") as index_file:
    json.dump(index, index_file, ensure_ascii=False, indent=2)

for item in index:
    for source in item["sources"]:
        source.pop("versionId", None)

with REMOTE_REPO.joinpath("index.min.json").open("w", encoding="utf-8") as index_min_file:
    json.dump(index, index_min_file, ensure_ascii=False, separators=(",", ":"))

with REMOTE_REPO.joinpath("index.html").open("w", encoding="utf-8") as index_html_file:
    index_html_file.write('<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>apks</title>\n</head>\n<body>\n<pre>\n')
    for entry in index:
        apk_escaped = 'apk/' + html.escape(entry["apk"])
        name_escaped = html.escape(entry["name"])
        index_html_file.write(f'<a href="{apk_escaped}">{name_escaped}</a>\n')
    index_html_file.write('</pre>\n</body>\n</html>\n')
