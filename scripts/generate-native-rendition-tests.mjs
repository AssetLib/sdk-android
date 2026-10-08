// Synthetic test signatures only. Never use this public fixture seed in a service.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createPrivateKey, sign, createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
const base = new URL('../sdk/src/test/resources/', import.meta.url);
const read = async file => JSON.parse(await readFile(new URL(`fixtures/${file}`,base),'utf8'));
const source = await read('manifests/valid-renditions-seq4.json');
const seed = (await readFile(new URL('fixtures/keys/TEST_ONLY_seed.hex',base),'utf8')).trim();
const key = createPrivateKey({key:Buffer.from(`302e020100300506032b657004220420${seed}`,'hex'),type:'pkcs8',format:'der'});
const output = new URL('rendition-native/',base); await mkdir(output,{recursive:true});
async function save(name,payload) {
  const text = JSON.stringify(payload);
  await writeFile(new URL(name,output),JSON.stringify({...source,payload:text,signature:sign(null,Buffer.from(text),key).toString('base64')},null,2)+'\n');
}
let payload = JSON.parse(source.payload);
payload.slots[0].renditions[0].width = 240; payload.slots[0].renditions[0].height = 180;
await save('wrong-pixels.json',payload);
payload = JSON.parse(source.payload); payload.slots[0].renditions[0].mime = 'image/webp';
await save('wrong-mime.json',payload);
payload = JSON.parse((await read('manifests/valid-seq2.json')).payload); payload.sequence=5; payload.createdAt='2026-10-07T12:04:00.000Z';
await save('next-seq5.json',payload);
const corrupt = await readFile(new URL('fixtures/assets/small.png',base)); corrupt[41] ^= 255;
const hash = createHash('sha256').update(corrupt).digest('hex');
await writeFile(new URL('invalid-deflate.png',output),corrupt);
payload = JSON.parse(source.payload);
const r=payload.slots[0].renditions[0]; r.url=r.url.replace(r.sha256,hash); r.sha256=hash;
await save('invalid-deflate.json',payload);
console.log(`Generated five Android-only rendition regression fixtures in ${fileURLToPath(output)}.`);
