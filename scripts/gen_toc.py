#!/usr/bin/env python3
"""为 README.md 生成 GitHub 兼容的可点击目录(TOC), 插入到 <!-- TOC --> 占位处。"""
import re

PATH = "/home/ubuntu/claude-workspace/companion-agent/README.md"

def slug(text: str) -> str:
    # 模拟 GitHub slugger
    t = re.sub(r"<[^>]*>", "", text)
    t = t.lower()
    t = re.sub(r"[^\w一-鿿 \-_]", "", t, flags=re.UNICODE)  # 保留中文/字母/数字/空格/连字符/下划线
    t = t.replace(" ", "-")
    return t

with open(PATH, encoding="utf-8") as f:
    lines = f.read().split("\n")

seen = {}
toc_lines = ["## 📖 目录\n"]
for line in lines:
    m = re.match(r"^(#{2,4})\s+(.*)$", line)
    if not m:
        continue
    level = len(m.group(1))
    title = m.group(2).strip()
    anchor = slug(title)
    # 重复锚点加后缀
    if anchor in seen:
        seen[anchor] += 1
        anchor = f"{anchor}-{seen[anchor]}"
    else:
        seen[anchor] = 1
    indent = "  " * (level - 2)
    toc_lines.append(f"{indent}- [{title}](#{anchor})")

toc = "\n".join(toc_lines)

with open(PATH, encoding="utf-8") as f:
    content = f.read()

if "<!-- TOC -->" in content:
    content = content.replace("<!-- TOC -->", toc)
else:
    # 插到第一个 ## 之前
    idx = content.find("\n## ")
    content = content[:idx] + "\n" + toc + "\n" + content[idx:]

with open(PATH, "w", encoding="utf-8") as f:
    f.write(content)

print(f"✅ TOC 已生成: {len(toc_lines)-1} 个条目")
