import { readFileSync } from 'fs';
import { basename } from 'path';

const SERVER = 'https://pandagenie-server.rorschach123.workers.dev';
const modPath = process.argv[2];
const changelog = process.argv[3] || '';
const changelogZh = process.argv[4] || '';

if (!modPath) { console.error('Usage: node upload_module.mjs <mod_path> [changelog] [changelog_zh]'); process.exit(1); }

async function run() {
  const fileData = readFileSync(modPath);
  const formData = new FormData();
  formData.append('file', new Blob([fileData]), basename(modPath));

  console.log(`Uploading: ${basename(modPath)} ...`);
  const upResp = await fetch(`${SERVER}/module-market/submit/upload`, { method: 'POST', body: formData });
  const upJson = await upResp.json();
  console.log('Upload:', JSON.stringify(upJson));

  if (!upJson.temp_key) { console.error('No temp_key'); process.exit(1); }

  const pubBody = { temp_key: upJson.temp_key, changelog, changelog_zh: changelogZh };
  console.log('Publishing ...');
  const pubResp = await fetch(`${SERVER}/module-market/submit/publish`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(pubBody)
  });
  const pubJson = await pubResp.json();
  console.log('Publish:', JSON.stringify(pubJson));
}

run().catch(e => { console.error(e.message); process.exit(1); });
