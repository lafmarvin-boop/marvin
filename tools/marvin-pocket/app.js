const $ = (id) => document.getElementById(id);
const api = (path, opts) => fetch(path, opts).then(r => r.json());

// ---------- tabs ----------
document.querySelectorAll(".tab").forEach(btn => {
  btn.addEventListener("click", () => {
    const view = btn.dataset.view;
    document.querySelectorAll(".tab").forEach(b => b.classList.toggle("active", b === btn));
    document.querySelectorAll(".view").forEach(v => v.classList.toggle("active", v.id === `view-${view}`));
  });
});

// ---------- recap ----------
async function loadRecap() {
  $("recap-body").textContent = "Chargement…";
  try {
    const data = await api("/api/recap");
    const g = data.graph || {};
    $("recap-stats").innerHTML =
      `<strong>${data.conversation_count || 0}</strong> conversations · ` +
      `<strong>${g.nodes || 0}</strong> nœuds · ` +
      `<strong>${g.edges || 0}</strong> arêtes` +
      (g.central && g.central.length ? `<br>Nœuds centraux : ${g.central.join(", ")}` : "");
    $("recap-title").textContent = data.resume?.title || "Pas encore de résumé";
    $("recap-body").textContent = data.resume?.body || "Lance d'abord un /reprise-de-session pour créer un RESUME_*.md.";
  } catch (e) {
    $("recap-body").textContent = "Erreur : serveur Marvin injoignable.";
  }
}
$("refresh-recap").addEventListener("click", loadRecap);

// ---------- voice (Web Speech API) ----------
const SpeechRec = window.SpeechRecognition || window.webkitSpeechRecognition;

function attachMic(btn, target, statusEl) {
  if (!SpeechRec) {
    btn.disabled = true;
    btn.title = "Web Speech API non dispo (essaie Chrome Android)";
    return;
  }
  let rec = null;
  let listening = false;
  btn.addEventListener("click", () => {
    if (listening) { rec.stop(); return; }
    rec = new SpeechRec();
    rec.lang = "fr-FR";
    rec.continuous = true;
    rec.interimResults = true;
    let finalText = target.value ? target.value.trimEnd() + " " : "";
    rec.onresult = (e) => {
      let interim = "";
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const t = e.results[i][0].transcript;
        if (e.results[i].isFinal) finalText += t + " ";
        else interim += t;
      }
      target.value = (finalText + interim).trimStart();
    };
    rec.onerror = (e) => {
      if (statusEl) { statusEl.textContent = "Micro : " + e.error; statusEl.className = "status err"; }
    };
    rec.onend = () => {
      listening = false;
      btn.classList.remove("recording");
      btn.textContent = btn.dataset.label || btn.textContent;
    };
    btn.dataset.label = btn.textContent;
    btn.textContent = "⏹ Stop";
    btn.classList.add("recording");
    listening = true;
    rec.start();
  });
}

attachMic($("capture-mic"), $("capture-text"), $("capture-status"));
attachMic($("ask-mic"), $("ask-question"), $("ask-status"));

// ---------- capture ----------
$("capture-save").addEventListener("click", async () => {
  const text = $("capture-text").value.trim();
  const status = $("capture-status");
  if (!text) {
    status.textContent = "Vide.";
    status.className = "status err";
    return;
  }
  status.textContent = "Enregistrement…";
  status.className = "status";
  try {
    const r = await api("/api/capture", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ text }),
    });
    if (r.ok) {
      status.textContent = "✔ sauvegardé : " + r.file;
      status.className = "status ok";
      $("capture-text").value = "";
    } else {
      status.textContent = "Erreur : " + (r.error || "inconnue");
      status.className = "status err";
    }
  } catch (e) {
    status.textContent = "Erreur réseau.";
    status.className = "status err";
  }
});

// ---------- ask ----------
$("ask-send").addEventListener("click", async () => {
  const question = $("ask-question").value.trim();
  const status = $("ask-status");
  const answer = $("ask-answer");
  if (!question) return;
  status.textContent = "Marvin réfléchit…";
  status.className = "status";
  answer.textContent = "";
  try {
    const r = await api("/api/ask", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ question }),
    });
    if (r.ok) {
      answer.textContent = r.answer;
      status.textContent = r.model || "";
      status.className = "status ok";
    } else {
      status.textContent = "Erreur : " + (r.error || "inconnue");
      status.className = "status err";
    }
  } catch (e) {
    status.textContent = "Erreur réseau.";
    status.className = "status err";
  }
});

// ---------- search ----------
let searchTimer = null;
$("search-input").addEventListener("input", () => {
  clearTimeout(searchTimer);
  const q = $("search-input").value.trim();
  searchTimer = setTimeout(() => runSearch(q), 250);
});

async function runSearch(q) {
  const list = $("search-results");
  if (!q || q.length < 2) { list.innerHTML = ""; return; }
  try {
    const r = await api("/api/search?q=" + encodeURIComponent(q));
    list.innerHTML = "";
    if (!r.results.length) {
      list.innerHTML = "<li><div class='snippet'>Aucun résultat.</div></li>";
      return;
    }
    const re = new RegExp(q.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "gi");
    for (const item of r.results) {
      const li = document.createElement("li");
      const title = document.createElement("div");
      title.className = "title";
      title.textContent = item.title;
      const snippet = document.createElement("div");
      snippet.className = "snippet";
      snippet.innerHTML = item.snippet
        .replace(/[<>&]/g, c => ({ "<": "&lt;", ">": "&gt;", "&": "&amp;" }[c]))
        .replace(re, m => `<mark>${m}</mark>`);
      li.appendChild(title);
      li.appendChild(snippet);
      list.appendChild(li);
    }
  } catch (e) {
    list.innerHTML = "<li><div class='snippet'>Erreur réseau.</div></li>";
  }
}

// ---------- service worker ----------
if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});
}

loadRecap();
