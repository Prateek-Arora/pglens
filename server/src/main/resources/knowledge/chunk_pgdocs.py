"""Chunk PostgreSQL 16 docs pages by section heading into ~300-token passages (JSON Lines).

Pages: indexes-intro indexes-types indexes-multicolumn indexes-ordering indexes-bitmap-scans
indexes-expressional indexes-partial indexes-index-only-scans indexes-examine using-explain
planner-stats explicit-joins planner-optimizer gin-intro datatype-json (jsonb Indexing only).

Each passage keeps the section title and the section's URL (#anchor). Code examples (<pre>) are kept
as text; navigation, footnotes and the page header are dropped.
"""
import html, json, re, sys
from html.parser import HTMLParser

MAX = 1300  # ~300 tokens

class Doc(HTMLParser):
    def __init__(self):
        super().__init__(); self.sections=[]; self.cur=None; self.depth=0; self.in_content=False
        self.skip=0; self.title_mode=False; self.stack=[]
    def handle_starttag(self, tag, a):
        a=dict(a)
        if a.get("id")=="docContent": self.in_content=True
        if not self.in_content: return
        cls=a.get("class","")
        if tag=="div" and re.match(r"sect\d|chapter|refsect", cls or "") and a.get("id"):
            self.cur={"anchor":a["id"],"title":"","text":[]}; self.sections.append(self.cur)
        if tag in ("h2","h3","h4") and self.cur is not None and not self.cur["title"]:
            self.title_mode=True
        if tag in ("div",) and cls in ("navheader","navfooter","footnotes"): self.skip+=1; self.stack.append("skip")
        elif tag=="div": self.stack.append("div")
        if tag in ("p","li","pre","dt","dd","tr") and self.cur is not None: self.cur["text"].append("\n")
    def handle_endtag(self, tag):
        if tag in ("h2","h3","h4"): self.title_mode=False
        if tag=="div" and self.stack:
            if self.stack.pop()=="skip": self.skip-=1
    def handle_data(self, d):
        if not self.in_content or self.skip or self.cur is None: return
        if self.title_mode: self.cur["title"]+=d
        else: self.cur["text"].append(d)

out=[]
for page in sys.argv[2:]:
    name=page.split("/")[-1].replace(".html","")
    p=Doc(); p.feed(open(page).read())
    for s in p.sections:
        title=re.sub(r"\s+"," ",s["title"]).strip()
        title=re.sub(r"^[\d.]+\.?\s*","",title).rstrip(" #")
        text=re.sub(r"[ \t]+"," ","".join(s["text"]))
        paras=[x.strip() for x in re.split(r"\n\s*\n|\n",text) if x.strip()]
        # the page footer ("Submit correction …") isn't documentation
        cut=next((i for i,x in enumerate(paras) if x.startswith("Submit correction")),None)
        if cut is not None: paras=paras[:cut]
        if name=="datatype-json" and "INDEXING" not in s["anchor"].upper(): continue
        chunk=""
        n=0
        def emit(c):
            global n
            out.append({"id":f"{name}-{sum(1 for o in out if o['id'].startswith(name+'-'))+1:02d}","url":f"https://www.postgresql.org/docs/16/{name}.html#{s['anchor']}",
                        "title":title,"text":c.strip()})
        for para in paras:
            if len(chunk)+len(para)>MAX and chunk:
                emit(chunk); chunk=""
            chunk+=para+"\n"
        if len(chunk.strip())>80: emit(chunk)
with open(sys.argv[1],"w") as f:
    for o in out: f.write(json.dumps(o,ensure_ascii=False)+"\n")
print(len(out),"chunks; chars:",sum(len(o["text"]) for o in out))
