// Publie un article statique SEO à partir d'un fichier JSON.
// Usage : node tools/new-article.mjs article.json
// JSON : { title, slug, description, tag, emoji, readMin, keywords:[...], body:"<h2>...</h2><p>...</p>" }
// Effets : crée blog/<slug>.html, ajoute la carte en tête de blog.html, l'URL au sitemap.xml, une ligne à blog/_topics.md
import fs from 'node:fs';
import path from 'node:path';

const file = process.argv[2];
if (!file) { console.error('usage: node tools/new-article.mjs article.json'); process.exit(1); }
const a = JSON.parse(fs.readFileSync(file, 'utf8'));
for (const k of ['title', 'slug', 'description', 'tag', 'emoji', 'body']) if (!a[k]) { console.error('champ manquant :', k); process.exit(1); }
if (!/^[a-z0-9-]{8,80}$/.test(a.slug)) { console.error('slug invalide (a-z, 0-9, tirets, 8-80 caractères)'); process.exit(1); }
if (a.description.length > 160) { console.error('description > 160 caractères'); process.exit(1); }
const out = path.join('blog', `${a.slug}.html`);
if (fs.existsSync(out)) { console.error('existe déjà :', out); process.exit(1); }
const words = a.body.replace(/<[^>]+>/g, ' ').split(/\s+/).filter(Boolean).length;
if (words < 700) { console.error(`article trop court : ${words} mots (minimum 700)`); process.exit(1); }
if (/<script|onclick=|javascript:/i.test(a.body)) { console.error('script interdit dans le corps'); process.exit(1); }
const readMin = a.readMin || Math.max(3, Math.round(words / 200));
const now = new Date();
const dateIso = now.toISOString().slice(0, 10);
const dateFr = now.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'Europe/Paris' });
const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

// Articles liés : les 3 derniers statiques publiés (hors celui-ci)
const existing = fs.readdirSync('blog').filter(f => f.endsWith('.html') && !f.startsWith('_') && f !== `${a.slug}.html`)
  .map(f => ({ f, t: fs.statSync(path.join('blog', f)).mtimeMs })).sort((x, y) => y.t - x.t).slice(0, 3)
  .map(({ f }) => { const h = fs.readFileSync(path.join('blog', f), 'utf8'); const m = h.match(/<h1>([^<]+)<\/h1>/); return `  <a href="/blog/${f}">${m ? m[1] : f}</a>`; });
const related = existing.length ? existing.join('\n') : '  <a href="/blog.html">Tous les articles du blog</a>';

let html = fs.readFileSync('blog/_template.html', 'utf8');
const rep = { TITLE: esc(a.title), TITLE_JSON: JSON.stringify(a.title), DESCRIPTION: esc(a.description), DESCRIPTION_JSON: JSON.stringify(a.description), SLUG: a.slug, TAG: esc(a.tag), EMOJI: a.emoji, DATE_ISO: dateIso, DATE_FR: dateFr, READ_MIN: String(readMin), BODY: a.body.trim(), RELATED: related };
html = html.replace(/\{\{(\w+)\}\}/g, (_, k) => rep[k] ?? '');
fs.writeFileSync(out, html);

// Carte en tête de la liste du blog (lien vers la page statique)
let blog = fs.readFileSync('blog.html', 'utf8');
const card = `      <a class="card" href="/blog/${a.slug}.html">
        <div class="card-img">${a.emoji}</div>
        <div class="card-body">
          <div class="card-tag">${esc(a.tag)}</div>
          <h2>${esc(a.title)}</h2>
          <p>${esc(a.description)}</p>
          <div class="card-meta">${readMin} min de lecture · ${dateFr}</div>
        </div>
      </a>
`;
if (!blog.includes('<div class="grid">')) { console.error('grille introuvable dans blog.html'); process.exit(1); }
blog = blog.replace('<div class="grid">\n', '<div class="grid">\n' + card);
if (!blog.includes('a.card{')) blog = blog.replace('</style>', '    a.card{display:block;color:inherit;}\n    a.card:hover{text-decoration:none;}\n  </style>');
fs.writeFileSync('blog.html', blog);

// Sitemap
let sm = fs.readFileSync('sitemap.xml', 'utf8');
const url = `https://parlonsecoute.fr/blog/${a.slug}.html`;
if (!sm.includes(url)) sm = sm.replace('</urlset>', `  <url>\n    <loc>${url}</loc>\n    <lastmod>${dateIso}</lastmod>\n    <changefreq>monthly</changefreq>\n    <priority>0.7</priority>\n  </url>\n</urlset>`);
fs.writeFileSync('sitemap.xml', sm);

// Journal
fs.appendFileSync('blog/_topics.md', `- ${dateIso} · ${a.tag} : ${a.title} → /blog/${a.slug}.html · mots-clés : ${(a.keywords || []).join(', ')}\n`);
console.log(`OK : ${out} (${words} mots, ${readMin} min) · carte ajoutée à blog.html · sitemap · journal`);
