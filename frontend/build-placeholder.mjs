// Placeholder until the customer journey SPA lands (WG.09): one static page into the BFF's static folder.
import { mkdirSync, writeFileSync } from 'node:fs';
const out = new URL('../target/classes/static/', import.meta.url);
mkdirSync(out, { recursive: true });
writeFileSync(new URL('index.html', out), `<!doctype html>
<html lang="en-IN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Ondaro Bank: apply for a credit card</title></head>
<body><main><h1>Apply for a credit card</h1><p>The application pages arrive in the next release. The API is at /bff.</p></main></body></html>
`);
