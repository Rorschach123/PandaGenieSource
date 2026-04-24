import { readFileSync } from 'fs';
import { basename } from 'path';
import https from 'https';
import http from 'http';
import tls from 'tls';
import { URL } from 'url';

const SERVER = 'https://cf.pandagenie.ai';
const PROXY = 'http://127.0.0.1:7890';
const modPath = process.argv[2];
const changelog = process.argv[3] || '';
const changelogZh = process.argv[4] || '';

if (!modPath) { console.error('Usage: node upload_module_direct.mjs <mod_path> [changelog] [changelog_zh]'); process.exit(1); }

function httpsViaProxy(url, options, body) {
  return new Promise((resolve, reject) => {
    const target = new URL(url);
    const proxy = new URL(PROXY);
    const connectReq = http.request({
      host: proxy.hostname, port: proxy.port,
      method: 'CONNECT', path: `${target.hostname}:443`,
      headers: { Host: `${target.hostname}:443` }
    });
    connectReq.on('connect', (res, socket) => {
      if (res.statusCode !== 200) { reject(new Error(`CONNECT failed: ${res.statusCode}`)); return; }
      const tlsSocket = tls.connect({
        host: target.hostname, socket, servername: target.hostname,
        rejectUnauthorized: true,
        minVersion: 'TLSv1.2'
      }, () => {
        let reqLine = `${options.method || 'GET'} ${target.pathname}${target.search || ''} HTTP/1.1\r\n`;
        reqLine += `Host: ${target.hostname}\r\n`;
        if (options.headers) {
          for (const [k, v] of Object.entries(options.headers)) { reqLine += `${k}: ${v}\r\n`; }
        }
        if (body) { reqLine += `Content-Length: ${Buffer.byteLength(body)}\r\n`; }
        reqLine += `Connection: close\r\n\r\n`;
        tlsSocket.write(reqLine);
        if (body) tlsSocket.write(body);
        let data = '';
        tlsSocket.on('data', c => data += c);
        tlsSocket.on('end', () => {
          const bodyStart = data.indexOf('\r\n\r\n');
          resolve(bodyStart >= 0 ? data.substring(bodyStart + 4) : data);
        });
        tlsSocket.on('error', reject);
      });
    });
    connectReq.on('error', reject);
    connectReq.end();
  });
}

async function directFetch(url, options = {}) {
  return new Promise((resolve, reject) => {
    const target = new URL(url);
    const req = https.request({
      hostname: target.hostname, port: 443,
      path: target.pathname + (target.search || ''),
      method: options.method || 'GET',
      headers: options.headers || {},
    }, res => {
      let data = '';
      res.on('data', c => data += c);
      res.on('end', () => resolve(data));
    });
    req.on('error', reject);
    if (options.body) req.write(options.body);
    req.end();
  });
}

async function run() {
  const fileData = readFileSync(modPath);
  const boundary = '----NodeFormBoundary' + Date.now().toString(36);
  const fname = basename(modPath);
  const parts = [];
  parts.push(`--${boundary}\r\nContent-Disposition: form-data; name="modfile"; filename="${fname}"\r\nContent-Type: application/octet-stream\r\n\r\n`);
  const bodyParts = [Buffer.from(parts[0]), fileData, Buffer.from(`\r\n--${boundary}--\r\n`)];
  const body = Buffer.concat(bodyParts);

  console.log(`Uploading: ${fname} (${(fileData.length/1024).toFixed(1)} KB) ...`);
  let upText;
  try {
    upText = await directFetch(`${SERVER}/submit/upload`, {
      method: 'POST',
      headers: { 'Content-Type': `multipart/form-data; boundary=${boundary}` },
      body
    });
  } catch (e) {
    console.log('Direct failed, trying proxy...');
    upText = await httpsViaProxy(`${SERVER}/submit/upload`, {
      method: 'POST',
      headers: { 'Content-Type': `multipart/form-data; boundary=${boundary}` },
    }, body);
  }
  console.log('Upload:', upText.substring(0, 300));

  let upJson;
  try { upJson = JSON.parse(upText); } catch { console.error('Invalid JSON response'); process.exit(1); }
  if (!upJson.temp_key) { console.error('No temp_key'); process.exit(1); }

  const pubBody = JSON.stringify({ temp_key: upJson.temp_key, changelog, changelog_zh: changelogZh });
  console.log('Publishing ...');
  let pubText;
  try {
    pubText = await directFetch(`${SERVER}/submit/publish`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(pubBody).toString() },
      body: pubBody
    });
  } catch (e) {
    pubText = await httpsViaProxy(`${SERVER}/submit/publish`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
    }, pubBody);
  }
  console.log('Publish:', pubText.substring(0, 300));
}

run().catch(e => { console.error(e.message); process.exit(1); });
