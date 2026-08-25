#!/usr/bin/env node

// `tsc` only emits compiled .js files; pug templates and static assets need to
// be copied into dist/ alongside them so the compiled app can run standalone
// (see express-app.ts, which resolves "templates" and "public" via __dirname).

const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..');

for (const dir of ['templates', 'public']) {
  fs.cpSync(path.join(root, 'src', dir), path.join(root, 'dist', dir), { recursive: true });
}
