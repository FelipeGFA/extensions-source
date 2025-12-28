import html
import sys
import json
from pathlib import Path
import shutil

REMOTE_REPO: Path = Path.cwd()
LOCAL_REPO: Path = REMOTE_REPO.parent.joinpath("main/repo")

to_delete: list[str] = json.loads(sys.argv[1])

# Load indexes first to check for downgrades
with REMOTE_REPO.joinpath("index.json").open() as remote_index_file:
    remote_index = json.load(remote_index_file)

with LOCAL_REPO.joinpath("index.min.json").open() as local_index_file:
    local_index = json.load(local_index_file)

# Create lookup dict for remote packages
remote_pkg_to_item = {item["pkg"]: item for item in remote_index}

downgraded_modules = set()

# Identify downgrades
for item in local_index:
    pkg = item["pkg"]
    if pkg in remote_pkg_to_item:
        remote_item = remote_pkg_to_item[pkg]
        if item["code"] < remote_item["code"]:
            print(f"Downgrade detectado para {pkg}: nova versão {item['version']} (code {item['code']}) < existente {remote_item['version']} (code {remote_item['code']}). Pulando atualização.")
            # Extract module name from pkg to match to_delete format
            # pkg: eu.kanade.tachiyomi.extension.pt.example
            # module: pt.example
            module_name = pkg.replace("eu.kanade.tachiyomi.extension.", "")
            downgraded_modules.add(module_name)

# Remove downgraded modules from deletion list (preserve old version)
to_delete = [m for m in to_delete if m not in downgraded_modules]

# Delete old files (only for non-downgraded modules)
for module in to_delete:
    apk_name = f"tachiyomi-{module}-v*.*.*.apk"
    icon_name = f"eu.kanade.tachiyomi.extension.{module}.png"
    for file in REMOTE_REPO.joinpath("apk").glob(apk_name):
        print(f"Removing old APK: {file.name}")
        file.unlink(missing_ok=True)
    for file in REMOTE_REPO.joinpath("icon").glob(icon_name):
        print(f"Removing old Icon: {file.name}")
        file.unlink(missing_ok=True)

# Copy new files (skip downgrades)
# Helper to check if file belongs to a downgraded module
def is_downgrade(filename):
    for mod in downgraded_modules:
        # Check APK pattern: tachiyomi-{module}-v...
        if filename.startswith(f"tachiyomi-{mod}-"):
            return True
        # Check Icon pattern: eu.kanade.tachiyomi.extension.{module}.png
        if filename == f"eu.kanade.tachiyomi.extension.{mod}.png":
            return True
    return False

# Copy APKs
remote_apk_dir = REMOTE_REPO.joinpath("apk")
remote_apk_dir.mkdir(exist_ok=True)
for file in LOCAL_REPO.joinpath("apk").glob("*"):
    if not is_downgrade(file.name):
        shutil.copy2(file, remote_apk_dir / file.name)
    else:
        print(f"Skipping copy of downgraded APK: {file.name}")

# Copy Icons
remote_icon_dir = REMOTE_REPO.joinpath("icon")
remote_icon_dir.mkdir(exist_ok=True)
for file in LOCAL_REPO.joinpath("icon").glob("*"):
    if not is_downgrade(file.name):
        shutil.copy2(file, remote_icon_dir / file.name)
    else:
        print(f"Skipping copy of downgraded Icon: {file.name}")


# Build final index
# 1. Keep remote items that are NOT in to_delete (this keeps downgrades too, because we removed them from to_delete)
index = [
    item for item in remote_index
    if not any([item["pkg"].endswith(f".{module}") for module in to_delete])
]

# 2. Add local items that are NOT downgrades
filtered_local_index = []
for item in local_index:
    pkg = item["pkg"]
    module_name = pkg.replace("eu.kanade.tachiyomi.extension.", "")
    if module_name not in downgraded_modules:
        filtered_local_index.append(item)

index.extend(filtered_local_index)
index.sort(key=lambda x: x["pkg"])

# Save files
with REMOTE_REPO.joinpath("index.json").open("w", encoding="utf-8") as index_file:
    json.dump(index, index_file, ensure_ascii=False, indent=2)

for item in index:
    for source in item["sources"]:
        source.pop("versionId", None)

with REMOTE_REPO.joinpath("index.min.json").open("w", encoding="utf-8") as index_min_file:
    json.dump(index, index_min_file, ensure_ascii=False, separators=( ",", ":"))

with REMOTE_REPO.joinpath("index.html").open("w", encoding="utf-8") as index_html_file:
    index_html_file.write('<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>apks</title>\n</head>\n<body>\n<pre>\n')
    for entry in index:
        apk_escaped = 'apk/' + html.escape(entry["apk"])
        name_escaped = html.escape(entry["name"])
        index_html_file.write(f'<a href="{apk_escaped}">{name_escaped}</a>\n')
    index_html_file.write('</pre>\n</body>\n</html>\n')