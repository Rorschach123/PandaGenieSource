import { readFileSync } from 'fs';
import { basename } from 'path';

const SERVER = 'https://cf.pandagenie.ai';
const modPath = process.argv[2];
const changelog = process.argv[3] || '';
const changelogZh = process.argv[4] || '';

if (!modPath) { console.error('Usage: node upload_module.mjs <mod_path> [changelog] [changelog_zh]'); process.exit(1); }

async function run() {
  const fileData = readFileSync(modPath);
  const formData = new FormData();
  formData.append('modfile', new Blob([fileData]), basename(modPath));

  console.log(`Uploading: ${basename(modPath)} (${(fileData.length/1024).toFixed(1)} KB) ...`);
  const upResp = await fetch(`${SERVER}/submit/upload`, { method: 'POST', body: formData });
  const upJson = await upResp.json();

  if (!upJson.success) {
    console.error('Upload failed:', upJson.error || JSON.stringify(upJson));
    process.exit(1);
  }

  const tempKey = upJson.data?.temp_key || upJson.temp_key;
  if (!tempKey) { console.error('No temp_key in response'); process.exit(1); }
  console.log(`Upload OK: ${upJson.data?.module_id} v${upJson.data?.version}`);

  const pubBody = { temp_key: tempKey, changelog, changelog_zh: changelogZh };
  console.log('Publishing ...');
  const pubResp = await fetch(`${SERVER}/submit/publish`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(pubBody)
  });
  const pubJson = await pubResp.json();

  if (pubJson.success) {
    console.log(`Published: ${pubJson.data?.module_id || 'ok'} v${pubJson.data?.version || ''}`);
  } else {
    console.error('Publish failed:', pubJson.error || JSON.stringify(pubJson));
    process.exit(1);
  }
}

run().catch(e => { console.error(e.message); process.exit(1); });
