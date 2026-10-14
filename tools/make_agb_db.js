// Builds app/src/main/assets/db_agb.tsv from FlashGBX's config/db_AGB.json (GPL-3.0, Lesserkuma/FlashGBX).
// usage: node tools/make_agb_db.js <path to db_AGB.json> <output tsv>
const fs = require('fs');
const [,, input, output] = process.argv;
const db = JSON.parse(fs.readFileSync(input, 'utf8'));
const lines = [
  '# GBA cartridge database for GBFlash, derived from FlashGBX config/db_AGB.json',
  '# Copyright (C) Lesserkuma and the FlashGBX contributors, licensed GPL-3.0: https://github.com/lesserkuma/FlashGBX',
  '# columns (TAB separated): header SHA-1 | FlashGBX save type index | flags (V = GBA Video 3D memory cart) | game code | name',
];
for (const [sha1, e] of Object.entries(db)) {
  const name = (e.gn + (e.ne ? ' ' + e.ne : '')).replace(/[\t\r\n]+/g, ' ');
  lines.push([sha1, e.st, e['3d'] ? 'V' : '', e.gc, name].join('\t'));
}
fs.writeFileSync(output, lines.join('\n') + '\n', 'utf8');
console.log(Object.keys(db).length + ' entries');
