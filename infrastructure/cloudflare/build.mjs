import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { marked } from "marked";
const dir = path.dirname(fileURLToPath(import.meta.url)),
  root = path.resolve(dir, "../.."),
  out = path.join(dir, "public");
const esc = (s) =>
  String(s)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });
fs.mkdirSync(path.join(dir, ".generated"), { recursive: true });
const write = (name, data) => {
  const p = path.join(out, name);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, data);
};
const files = execFileSync("git", ["ls-files", "-z"], { cwd: root })
  .toString()
  .split("\0")
  .filter(Boolean);
// Include new cloud guide before the first commit; never sweep arbitrary local files.
if (!files.includes("docs/cloud-presentation.md"))
  files.push("docs/cloud-presentation.md");
const docs = files.filter(
  (p) => (p === "README.md" || p.startsWith("docs/")) && p.endsWith(".md"),
);
const page = (title, body) =>
  `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)} · Turaco reference</title><meta name="description" content="A synthetic insurance engineering reference: live Java/Python cloud demo, architecture and verified release."><link rel="icon" href="/favicon.svg"><link rel="stylesheet" href="/presentation.css"></head><body><header><a class="brand" href="/">t. <span>Turaco reference</span></a><span class="badge">SYNTHETIC DEMO</span></header><main>${body}</main><footer>Independent engineering portfolio · No real insurance, messages or money transfers. <a href="https://github.com/kimtour/turaco-insurance">Public source</a></footer></body></html>`;
marked.use({
  renderer: {
    html({ text }) {
      return esc(text);
    },
  },
});
for (const file of docs) {
  let text = fs.readFileSync(path.join(root, file), "utf8");
  text = text.replace(/\]\(([^\s)]+)(?:\s+"[^"]*")?\)/g, (all, href) => {
    if (/^http:\/\/(localhost|127\.0\.0\.1)(:|\/)/.test(href))
      return "](/workspace/)";
    if (/^(https?:|#|mailto:)/.test(href)) return all;
    if (href.startsWith("/Users/") || href.startsWith("file:")) return "](#)";
    const [name, fragment] = href.split("#");
    const target = path.posix.normalize(
      path.posix.join(path.posix.dirname(file), name),
    );
    if (target.endsWith(".md") && docs.includes(target))
      return (
        "](/documents/" +
        target.replace(/\.md$/, ".html") +
        (fragment ? "#" + fragment : "") +
        ")"
      );
    if (target.startsWith("docs/") && target.endsWith(".json"))
      return "](/documents/" + target + ")";
    if (files.includes(target))
      return (
        "](https://github.com/kimtour/turaco-insurance/blob/codex/full-sdlc/" +
        target.split("/").map(encodeURIComponent).join("/") +
        (fragment ? "#" + fragment : "") +
        ")"
      );
    return all;
  });
  const title = text.match(/^#\s+(.+)$/m)?.[1] || file;
  write(
    "documents/" + file.replace(/\.md$/, ".html"),
    page(
      title,
      `<a class="back" href="/">← Presentation hub</a><article>${marked.parse(text)}</article>`,
    ),
  );
}
for (const file of files.filter(
  (p) => p.startsWith("docs/") && p.endsWith(".json"),
))
  write("documents/" + file, fs.readFileSync(path.join(root, file)));
const release = path.join(root, "release/0.2.0");
const downloadIndex = {};
let releaseLinks = "";
const allowed = [
  "SHA256SUMS",
  "manifest.json",
  "java-sbom.cdx.json",
  "claims-dashboard-0.2.0.tar.gz",
  "turaco-insurance-0.2.0-source.tar.gz",
  "insurance-api-0.2.0.jar",
];
if (fs.existsSync(release)) {
  execFileSync(
    "python3",
    [path.join(root, "scripts/verify-release.py"), "--directory", release],
    { cwd: root, stdio: "pipe" },
  );
  for (const name of allowed) {
    const bytes = fs.readFileSync(path.join(release, name));
    if (name.endsWith(".jar")) {
      const parts = [];
      for (let i = 0; i < bytes.length; i += 8 * 1024 * 1024) {
        const part = "/release-parts/" + name + "/" + parts.length;
        write(part.slice(1), bytes.subarray(i, i + 8 * 1024 * 1024));
        parts.push(part);
      }
      downloadIndex[name] = {
        name,
        parts,
        bytes: bytes.length,
        sha256: crypto.createHash("sha256").update(bytes).digest("hex"),
      };
    } else write("downloads/" + name, bytes);
    releaseLinks += `<a href="/downloads/${esc(name)}">${esc(name)}</a>`;
  }
}
fs.writeFileSync(
  path.join(dir, ".generated/downloads.json"),
  JSON.stringify(downloadIndex),
);
write("evidence/hosted-billing.png", fs.readFileSync(path.join(root, "docs/evidence/hosted-billing.png")));
write("evidence/rebuild-billing.png", fs.readFileSync(path.join(root, "docs/evidence/rebuild-billing.png")));
const screenshot = path.join(
  root,
  ".cache/verification/workspace-reviewed.png",
);
if (fs.existsSync(screenshot))
  write("evidence/workspace-reviewed.png", fs.readFileSync(screenshot));
for (const name of ["cloud-presentation.png", "cloud-workspace.png"]) {
  const screenshot = path.join(root, ".cache/verification", name);
  if (fs.existsSync(screenshot)) write("evidence/" + name, fs.readFileSync(screenshot));
}
const cloudProof = path.join(root, ".cache/verification/cloud-check.json");
if (fs.existsSync(cloudProof) && fs.statSync(cloudProof).size) {
  const proof = JSON.parse(fs.readFileSync(cloudProof));
  if (proof.result === "passed")
    write("evidence/cloud-check.json", JSON.stringify(proof, null, 2));
}
const links = docs
  .map(
    (file) =>
      `<a href="/documents/${file.replace(/\.md$/, ".html")}">${esc(file)}</a>`,
  )
  .join("");
write(
  "library/index.html",
  page(
    "Document library",
    `<a class="back" href="/">← Presentation hub</a><h1>Engineering documents</h1><p>Architecture, decisions, contracts and handoff. These are reference documents; the live cloud links are in the presentation hub.</p><div class="link-list">${links}</div>`,
  ),
);
const deck = JSON.parse(fs.readFileSync(path.join(root, "docs/presentation/slides.json"), "utf8"));
const slideMarkup = deck.map((slide, i) => `<section class="slide" id="slide-${i + 1}" aria-label="Slide ${i + 1}"><h1>${esc(slide.title)}</h1><p class="subtitle">${esc(slide.subtitle)}</p><ol>${slide.rows.map(row => `<li>${esc(row)}</li>`).join("")}</ol><details><summary>Presenter notes</summary><p>${esc(slide.notes)}</p></details><p class="page-number">${i + 1} / ${deck.length}</p></section>`).join("");
write("deck/index.html", `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Hospital cash claims | Interview presentation</title><link rel="stylesheet" href="/deck/deck.css"></head><body><nav><a href="/">Hub</a><a href="/workspace/" target="_blank" rel="noopener">Live demo</a><a href="/deck/turaco-interview.pptx">Original claims PowerPoint</a><button id="previous" aria-label="Previous slide">Previous</button><span id="position" aria-live="polite"></span><button id="next" aria-label="Next slide">Next</button><button id="fullscreen">Full screen</button></nav><main>${slideMarkup}</main><script src="/deck/deck.js" defer></script></body></html>`);
write("deck/deck.css", fs.readFileSync(path.join(dir, "presentation/deck.css")));
write("deck/deck.js", fs.readFileSync(path.join(dir, "presentation/deck.js")));
write("deck/turaco-interview.pptx", fs.readFileSync(path.join(root, "docs/presentation/turaco-interview.pptx")));
const handoff = fs.readFileSync(path.join(root, "docs/AI-HANDOFF.md"), "utf8");
write("handoff/AI-HANDOFF.md", handoff);
write("handoff/index.html", page("AI handoff", `<a class="back" href="/">← Presentation hub</a><p><a href="/handoff/AI-HANDOFF.md" download>Download the handoff for another AI app</a></p><article>${marked.parse(handoff)}</article>`));
write(
  "index.html",
  page(
    "Cloud presentation",
    fs
      .readFileSync(path.join(dir, "presentation/index.html"), "utf8")
      .replace("{{releaseLinks}}", releaseLinks),
  ),
);
write("portfolio/index.html", page("Insurance operations case study", fs.readFileSync(path.join(dir, "presentation/portfolio.html"), "utf8")));
write(
  "presentation.js",
  `document.querySelector('#check-status')?.addEventListener('click',async()=>{const button=document.querySelector('#check-status'),status=document.querySelector('#demo-status');button.disabled=true;status.textContent='Checking the cloud gateway…';try{const response=await fetch('/demo/status');const result=await response.json();status.textContent=result.ready?'Ready · live Java stack is responding':'Sleeping or starting · resume the workspace below';status.dataset.ready=String(result.ready);if(result.workspace_url)document.querySelector('#start-workspace').href=result.workspace_url;}catch{status.textContent='Status unavailable · open GitHub Codespaces to resume';}finally{button.disabled=false;}});fetch('/demo/status').then(r=>r.json()).then(r=>{if(r.workspace_url)document.querySelector('#start-workspace').href=r.workspace_url;}).catch(()=>{document.querySelector('#demo-status').textContent='Readiness check unavailable. Use GitHub Codespaces to inspect the workspace.';});`,
);
write(
  "favicon.svg",
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="12" fill="#254c3c"/><text x="18" y="48" font-family="Georgia" font-size="48" font-weight="bold" fill="#d9e8bd">t</text></svg>',
);
write(
  "presentation.css",
  fs.readFileSync(path.join(dir, "presentation/style.css")),
);
write(
  "404.html",
  page(
    "Page not found",
    '<h1>That page is not here.</h1><p><a href="/">Return to the presentation hub →</a></p>',
  ),
);
console.log(
  `Built ${docs.length} cloud documents, presentation guide, handoff and ${allowed.length} verified release links.`,
);
