// Builds app/src/main/assets/db_dmg.tsv from FlashGBX's config/db_DMG.json (GPL-3.0, Lesserkuma/FlashGBX).
// usage: node tools/make_dmg_db.js <path to db_DMG.json> <output tsv>
const fs = require('fs');
const [,, input, output] = process.argv;
const db = JSON.parse(fs.readFileSync(input, 'utf8'));
const lines = [
  '# Game Boy / Game Boy Color cartridge database for GBFlash, derived from FlashGBX config/db_DMG.json',
  '# Copyright (C) Lesserkuma and the FlashGBX contributors, licensed GPL-3.0: https://github.com/lesserkuma/FlashGBX',
  '# columns (TAB separated): header SHA-1 (bytes 0x000-0x17F) | game code | name',
];
for (const [sha1, e] of Object.entries(db)) {
  const name = (e.gn + (e.ne ? ' ' + e.ne : '')).replace(/[\t\r\n]+/g, ' ');
  lines.push([sha1, e.gc || '', name].join('\t'));
}
fs.writeFileSync(output, lines.join('\n') + '\n', 'utf8');
console.log(Object.keys(db).length + ' entries');
