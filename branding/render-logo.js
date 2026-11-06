const { chromium } = require('playwright');
const fs = require('fs');
const uri = (f) => 'data:image/png;base64,' + fs.readFileSync(f).toString('base64');
const OUT = process.argv[2];
const MINT = '#34D399', INK = '#0E1217', DEEP = '#0E2A21', ON_MINT = '#06281A', MUTED = '#97A3B0', WHITE = '#EEF2F6';
const shield = (c, w) => `<path d="M12,2l8,3v6c0,5 -3.4,9.3 -8,11c-4.6,-1.7 -8,-6 -8,-11V5l8,-3z" fill="none" stroke="${c}" stroke-width="${w}" stroke-linejoin="round"/><path d="M9,12l2,2l4,-4" fill="none" stroke="${c}" stroke-width="${w}" stroke-linecap="round" stroke-linejoin="round"/>`;
const power = (c, w) => `<path d="M12 3.2v8.3" fill="none" stroke="${c}" stroke-width="${w}" stroke-linecap="round"/><path d="M17.7 6.6a8 8 0 1 1-11.4.04" fill="none" stroke="${c}" stroke-width="${w}" stroke-linecap="round"/>`;

// Each mark is an SVG in a 100x100 box.
const marks = {
  shield: `<svg viewBox="0 0 100 100"><rect x="4" y="4" width="92" height="92" rx="26" fill="${DEEP}" stroke="${MINT}" stroke-width="4"/><g transform="translate(17,17) scale(2.75)">${shield(MINT, 2.0)}</g></svg>`,
  monogram: `<svg viewBox="0 0 100 100"><text x="50" y="69" text-anchor="middle" font-family="Inter" font-weight="900" font-size="54" letter-spacing="-2" fill="${ON_MINT}">ATS</text><rect x="20" y="78" width="60" height="5" rx="2.5" fill="${ON_MINT}"/></svg>`,
  tunnel: `<svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="46" fill="none" stroke="#1F5C47" stroke-width="3"/><circle cx="50" cy="50" r="37" fill="none" stroke="#258A66" stroke-width="3"/><circle cx="50" cy="50" r="28" fill="none" stroke="${MINT}" stroke-width="3"/><circle cx="50" cy="50" r="19" fill="${MINT}"/><text x="50" y="55.5" text-anchor="middle" font-family="Inter" font-weight="900" font-size="15" letter-spacing="0.5" fill="${ON_MINT}">ATS</text></svg>`,
  route: `<svg viewBox="0 0 100 100"><path d="M30 92 V58" stroke="${WHITE}" stroke-width="7" stroke-linecap="round" fill="none"/><path d="M30 38 V14" stroke="${MUTED}" stroke-width="6" stroke-linecap="round" stroke-dasharray="0.1 12" fill="none"/><path d="M30 58 C30 34 70 44 70 18" stroke="${MINT}" stroke-width="7" stroke-linecap="round" fill="none"/><circle cx="70" cy="14" r="9" fill="${MINT}"/><circle cx="30" cy="58" r="6" fill="${WHITE}"/></svg>`,
  power: `<svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="44" fill="${INK}"/><circle cx="50" cy="50" r="44" fill="none" stroke="${INK}" stroke-width="2"/><g transform="translate(20,20) scale(2.5)">${power(MINT, 1.9)}</g></svg>`,
};
const ALL = [
  { id: '1-shield', name: 'Щит', bg: INK, fg: WHITE, accent: MINT, mark: marks.shield, word: true, tag: MUTED },
  { id: '2-monogram', name: 'Монограмма', bg: MINT, fg: ON_MINT, accent: ON_MINT, mark: marks.monogram, word: false, tag: ON_MINT, noSplashWord: true },
  { id: '3-tunnel', name: 'Туннель', bg: INK, fg: WHITE, accent: MINT, mark: marks.tunnel, word: false, tag: MUTED },
  { id: '4-route', name: 'Развилка', bg: DEEP, fg: WHITE, accent: MINT, mark: marks.route, word: true, tag: '#9FB8AE' },
  { id: '5-power', name: 'Кнопка', bg: MINT, fg: ON_MINT, accent: ON_MINT, mark: marks.power, word: true, tag: ON_MINT },
];
const V = ALL.filter(v => v.id === '1-shield');
const base = (bg) => `<!doctype html><html><head><meta charset="utf-8"><style>
*{margin:0;padding:0;box-sizing:border-box}html,body{background:${bg};font-family:Inter,sans-serif}
svg{display:block;width:100%;height:100%}</style></head><body>`;
const avatar = (v) => base(v.bg) + `<div style="width:640px;height:640px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:${v.word ? 22 : 0}px">
  <div style="width:${v.word ? 360 : 470}px;height:${v.word ? 360 : 470}px">${v.mark}</div>
  ${v.word ? `<div style="font-weight:900;font-size:124px;letter-spacing:16px;margin-left:16px;line-height:1;color:${v.fg}">ATS</div>` : ''}
</div></body></html>`;
const splash = (v) => base(v.bg) + `<div style="width:640px;height:360px;display:flex;align-items:center;gap:40px;padding:0 56px">
  <div style="width:210px;height:210px;flex-shrink:0">${v.mark}</div>
  <div style="display:flex;flex-direction:column;gap:14px">
    ${v.noSplashWord ? '' : `<div style="font-weight:900;font-size:96px;letter-spacing:10px;line-height:.9;color:${v.fg}">ATS</div>`}
    <div style="font-weight:700;font-size:28px;line-height:1.25;color:${v.accent}">Личный VPN</div>
  </div>
</div></body></html>`;
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  for (const v of V) {
    for (const [kind, w, h, html] of [['avatar', 640, 640, avatar(v)], ['splash', 640, 360, splash(v)]]) {
      await page.setViewportSize({ width: w, height: h });
      await page.setContent(html); await page.evaluate(() => document.fonts.ready);
      await page.screenshot({ path: `${OUT}/ATS-logo-${v.id}-${kind}-${w}x${h}.png` });
    }
  }
  // Contact sheet: avatar shown in a circle like Telegram does, plus the splash.
  const cells = V.map(v => `<div class="row"><div class="lbl">${v.id[0]}. ${v.name}</div>
    <img class="av" src="${uri(`${OUT}/ATS-logo-${v.id}-avatar-640x640.png`)}"><img class="sp" src="${uri(`${OUT}/ATS-logo-${v.id}-splash-640x360.png`)}"></div>`).join('');
  await page.setViewportSize({ width: 1060, height: 100 });
  await page.setContent(`<!doctype html><html><head><meta charset="utf-8"><style>body{margin:0;padding:28px;background:#1A1F26;font-family:Inter;color:#EEF2F6}
    .row{display:flex;align-items:center;gap:28px;margin-bottom:26px}.lbl{width:150px;font-weight:700;font-size:22px}
    .av{width:200px;height:200px;border-radius:50%}.sp{width:560px;height:315px;border-radius:14px}</style></head><body>${cells}</body></html>`);
  await page.evaluate(() => Promise.all([...document.images].map(i => i.decode())));
  await page.screenshot({ path: `${OUT}/ATS-logos-preview.png`, fullPage: true });
  await browser.close();
})();
